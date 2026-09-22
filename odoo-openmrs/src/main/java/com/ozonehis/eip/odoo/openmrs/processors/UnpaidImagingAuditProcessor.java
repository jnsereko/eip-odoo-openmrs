/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.processors;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import com.ozonehis.eip.odoo.openmrs.RadiologyConcepts;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.TaskHandler;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reports radiology exams that were performed without being paid for.
 *
 * <p>Why this exists (issue #322). The payment gate is real but partial. This bridge marks a
 * ServiceRequest's Task {@code accepted} only once Odoo confirms a paid invoice on that order's own
 * visit, and the Orthanc bridge refuses to put a scan on the modality worklist until it is. That
 * half holds. What has no gate is OpenMRS itself: the results-entry screen never consults the Task,
 * so a technician can open an unpaid order, enter a report, and the order reaches COMPLETED. Measured
 * on UAT 2026-09-22: order ORD-11168, "RX01 - Radiographie thoracique", completed with a stored
 * report while every sale order for that visit was still a draft and no payment existed anywhere.
 *
 * <p>This processor does NOT prevent that — it makes it VISIBLE. An exam performed without payment
 * is revenue that will never be billed, under a contract (Article 6) that pays on monthly-verified
 * volume, at a site running no parallel paper register. Before this, nothing anywhere recorded that
 * it had happened; the order simply looked complete. Each occurrence is now one WARN line carrying
 * everything needed to reconcile it by hand, and the per-poll total is logged so the rate can be
 * watched rather than discovered a quarter later.
 *
 * <p>It is deliberately read-only. It does not void the results, reopen the order, or alter the
 * Task: a clinician's report is not this bridge's to withdraw, and an exam that has physically
 * happened should stay in the record. Prevention belongs at the point of entry — see #322.
 */
@Slf4j
@Setter
@Component
public class UnpaidImagingAuditProcessor implements Processor {

    @Autowired
    private IGenericClient openmrsFhirClient;

    @Autowired
    private TaskHandler taskHandler;

    /**
     * Matches the payment processor's window, for the same reason: an order can sit unpaid for days,
     * and nothing touches the ServiceRequest while it waits. A shorter window would stop reporting an
     * exam precisely when it had been unpaid longest.
     */
    private static final int LOOKBACK_DAYS = 7;

    @Override
    public void process(Exchange exchange) {
        String since = ZonedDateTime.now(ZoneOffset.UTC)
                .minusDays(LOOKBACK_DAYS)
                .format(DateTimeFormatter.ISO_INSTANT);

        Bundle bundle = openmrsFhirClient
                .search()
                .forResource(ServiceRequest.class)
                .where(new ca.uhn.fhir.rest.gclient.DateClientParam("_lastUpdated").after().second(since))
                .returnBundle(Bundle.class)
                .execute();

        if (bundle == null) {
            return;
        }

        int unpaid = 0;
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (!(entry.getResource() instanceof ServiceRequest)) {
                continue;
            }
            ServiceRequest serviceRequest = (ServiceRequest) entry.getResource();

            // Only finished work. An order still ACTIVE and unpaid is the normal waiting state and
            // is exactly what the payment processor is for -- reporting it here would drown the
            // real signal in every order that simply has not been paid yet.
            if (serviceRequest.getStatus() != ServiceRequest.ServiceRequestStatus.COMPLETED) {
                continue;
            }
            if (!RadiologyConcepts.isRadiologyOrder(serviceRequest)) {
                continue;
            }

            String serviceRequestId = serviceRequest.getIdElement().getIdPart();
            Task task;
            try {
                task = taskHandler.getTaskByServiceRequestId(serviceRequestId);
            } catch (Exception e) {
                // Report nothing on a lookup failure. Claiming an exam was unpaid because the Task
                // could not be read would put a false accusation in the log against a named
                // technician's work, and this processor's whole value is that its warnings are true.
                log.warn("Could not read the payment Task for completed ServiceRequest {} - not reporting it "
                        + "either way: {}", serviceRequestId, e.getMessage());
                continue;
            }

            if (task != null && task.getStatus() == Task.TaskStatus.ACCEPTED) {
                continue;
            }

            unpaid++;
            log.warn("UNPAID IMAGING PERFORMED - ServiceRequest {} is COMPLETED but its payment Task is {}. "
                    + "patient={} procedure='{}' authoredOn={}. The exam was carried out and reported without a "
                    + "confirmed payment in Odoo; it will not be billed unless someone reconciles it by hand. "
                    + "See issue #322.",
                    serviceRequestId,
                    task == null ? "ABSENT" : task.getStatus().toCode(),
                    serviceRequest.getSubject() == null
                            ? "unknown"
                            : serviceRequest.getSubject().getReferenceElement().getIdPart(),
                    serviceRequest.getCode() != null && serviceRequest.getCode().getText() != null
                            ? serviceRequest.getCode().getText()
                            : "",
                    serviceRequest.getAuthoredOn());
        }

        if (unpaid > 0) {
            log.warn("{} completed radiology exam(s) in the last {} days have no confirmed payment.",
                    unpaid, LOOKBACK_DAYS);
        } else {
            log.info("No unpaid completed radiology exams in the last {} days.", LOOKBACK_DAYS);
        }
        exchange.getMessage().setHeader("unpaidCompletedImagingCount", unpaid);
    }
}

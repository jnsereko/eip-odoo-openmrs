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
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.RadiologyPaymentEvidenceHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.EncounterHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.TaskHandler;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Reference;
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
 * <p>It also re-checks every ACCEPTED Task against Odoo and reports any whose order has no paid
 * invoice line on its own visit ("ACCEPTED WITHOUT PAYMENT"). An accepted Task is what opens the
 * modality worklist and the results screen, so one accepted in error is the original #322 defect
 * happening again - one payment authorising an order it was not for - and this is where it shows.
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

    @Autowired
    private EncounterHandler encounterHandler;

    @Autowired
    private RadiologyPaymentEvidenceHandler paymentEvidenceHandler;

    /** Upper bound on Task search pages followed per run, so a paging loop cannot run away. */
    private static final int MAX_TASK_PAGES = 100;

    /**
     * Matches the payment processor's window, for the same reason: an order can sit unpaid for days,
     * and nothing touches the ServiceRequest while it waits. A shorter window would stop reporting an
     * exam precisely when it had been unpaid longest.
     */
    private static final int LOOKBACK_DAYS = 7;


    /**
     * When the order was placed.
     *
     * <p>{@code authoredOn} is the obvious field and is EMPTY on these resources -- measured on UAT,
     * ServiceRequest a25f85c6 carried no authoredOn and no occurrenceDateTime, and the first version
     * of this processor duly logged "authoredOn=null", leaving an audit line nobody could reconcile
     * without a second lookup. The date is there, just not in that field: OpenMRS populates
     * occurrencePeriod, whose start matches the order's creation to the second.
     *
     * <p>Falls back through occurrenceDateTime and finally meta.lastUpdated, which for an order that
     * has not been edited is also its creation time. Returns null only if the resource genuinely
     * carries no date at all, and the caller then prints "unknown" rather than a wrong date.
     */
    private String orderedAt(ServiceRequest sr) {
        Instant orderedAt = orderedAtInstant(sr);
        return orderedAt == null ? "unknown" : orderedAt.toString();
    }

    private Instant orderedAtInstant(ServiceRequest sr) {
        if (sr.getAuthoredOn() != null) {
            return sr.getAuthoredOn().toInstant();
        }
        if (sr.hasOccurrencePeriod() && sr.getOccurrencePeriod().getStart() != null) {
            return sr.getOccurrencePeriod().getStart().toInstant();
        }
        if (sr.hasOccurrenceDateTimeType() && sr.getOccurrenceDateTimeType().getValue() != null) {
            return sr.getOccurrenceDateTimeType().getValue().toInstant();
        }
        if (sr.getMeta() != null && sr.getMeta().getLastUpdated() != null) {
            return sr.getMeta().getLastUpdated().toInstant();
        }
        return null;
    }

    /**
     * When the exam was actually carried out -- occurrencePeriod.end, which OpenMRS sets when the
     * order is completed. This is the figure a finance reconciliation cares about: not when someone
     * asked for the scan, but when the hospital did the work it was not paid for.
     */
    private String performedAt(ServiceRequest sr) {
        if (sr.hasOccurrencePeriod() && sr.getOccurrencePeriod().getEnd() != null) {
            return sr.getOccurrencePeriod().getEnd().toInstant().toString();
        }
        return "unknown";
    }

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

        try {
            exchange.getMessage().setHeader("acceptedWithoutPaymentCount", auditAcceptedTasks());
        } catch (Exception e) {
            // The Task search itself failed. Say so, and still run the completed-exam check below.
            log.warn("Could not audit accepted radiology Tasks this run: {}", e.getMessage());
        }

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
                    + "patient={} procedure='{}' ordered={} performed={}. The exam was carried out and reported "
                    + "without a confirmed payment in Odoo; it will not be billed unless someone reconciles it by "
                    + "hand. See issue #322.",
                    serviceRequestId,
                    task == null ? "ABSENT" : task.getStatus().toCode(),
                    serviceRequest.getSubject() == null
                            ? "unknown"
                            : serviceRequest.getSubject().getReferenceElement().getIdPart(),
                    serviceRequest.getCode() != null && serviceRequest.getCode().getText() != null
                            ? serviceRequest.getCode().getText()
                            : "",
                    orderedAt(serviceRequest),
                    performedAt(serviceRequest));
        }

        if (unpaid > 0) {
            log.warn("{} completed radiology exam(s) in the last {} days have no confirmed payment.",
                    unpaid, LOOKBACK_DAYS);
        } else {
            log.info("No unpaid completed radiology exams in the last {} days.", LOOKBACK_DAYS);
        }
        exchange.getMessage().setHeader("unpaidCompletedImagingCount", unpaid);
    }

    /**
     * Re-checks every ACCEPTED Task against Odoo and reports, without writing anything, each one whose
     * order has no paid invoice line on its own visit. Returns the number reported.
     *
     * <p>{@code status} is honoured by OpenMRS's FHIR2 Task search (measured on UAT: 2 of 15 Tasks
     * returned), but it is re-checked here anyway: FHIR2 silently drops search parameters it does not
     * support, and a dropped filter would turn "the accepted Tasks" into "every Task". The same goes
     * for {@code based-on}, which is only honoured in the typed {@code ServiceRequest/<uuid>} form -
     * which is why the ServiceRequest is read by id from basedOn rather than searched for.
     *
     * <p>An order that cannot be checked - ServiceRequest unreadable, visit unresolved, Odoo down - is
     * logged as unverified and NOT counted. A finding here says the hospital let an unpaid exam
     * through; it has to be true.
     */
    int auditAcceptedTasks() {
        int checked = 0;
        int findings = 0;
        Bundle page = openmrsFhirClient
                .search()
                .forResource(Task.class)
                .where(Task.STATUS.exactly().code(Task.TaskStatus.ACCEPTED.toCode()))
                .returnBundle(Bundle.class)
                .execute();

        for (int pages = 0; page != null && pages < MAX_TASK_PAGES; pages++) {
            for (Bundle.BundleEntryComponent entry : page.getEntry()) {
                if (!(entry.getResource() instanceof Task)) {
                    continue;
                }
                Task task = (Task) entry.getResource();
                if (task.getStatus() != Task.TaskStatus.ACCEPTED) {
                    continue;
                }
                String serviceRequestId = serviceRequestIdOf(task);
                if (serviceRequestId == null) {
                    continue;
                }
                Boolean paid = isPaid(task, serviceRequestId);
                if (paid == null) {
                    continue;
                }
                checked++;
                if (!paid) {
                    findings++;
                }
            }
            if (page.getLink(Bundle.LINK_NEXT) == null) {
                break;
            }
            page = openmrsFhirClient.loadPage().next(page).execute();
        }

        if (findings > 0) {
            log.warn("{} of {} accepted radiology Task(s) have no paid invoice line in Odoo.", findings, checked);
        } else {
            log.info("Checked {} accepted radiology Task(s): none accepted without payment.", checked);
        }
        return findings;
    }

    /** The ServiceRequest a Task is based on, or null if it is not based on one. */
    private String serviceRequestIdOf(Task task) {
        for (Reference basedOn : task.getBasedOn()) {
            if (basedOn.getReference() == null) {
                continue;
            }
            IdType id = new IdType(basedOn.getReference());
            if ("ServiceRequest".equals(id.getResourceType()) && id.getIdPart() != null) {
                return id.getIdPart();
            }
        }
        return null;
    }

    /**
     * Whether the Task's order is paid for: TRUE paid, FALSE not (and logged as a finding), null when
     * it could not be determined or the order is not a radiology order.
     */
    private Boolean isPaid(Task task, String serviceRequestId) {
        String taskId = task.getIdElement().getIdPart();
        ServiceRequest serviceRequest;
        try {
            serviceRequest = openmrsFhirClient
                    .read()
                    .resource(ServiceRequest.class)
                    .withId(serviceRequestId)
                    .execute();
        } catch (Exception e) {
            log.warn("Could not read ServiceRequest {} of accepted Task {} - not verified: {}",
                    serviceRequestId, taskId, e.getMessage());
            return null;
        }
        if (!RadiologyConcepts.isRadiologyOrder(serviceRequest)) {
            return null;
        }

        String patientUuid = serviceRequest.getSubject() == null
                ? null
                : serviceRequest.getSubject().getReferenceElement().getIdPart();
        String procedureDesc = serviceRequest.getCode().getText() != null ? serviceRequest.getCode().getText() : "";
        String visitUuid = resolveVisitUuid(serviceRequest);
        if (patientUuid == null || visitUuid == null) {
            log.warn("Accepted Task {} (ServiceRequest {}) has no resolvable patient or visit - not verified.",
                    taskId, serviceRequestId);
            return null;
        }

        boolean paid;
        try {
            paid = paymentEvidenceHandler.hasPaidLineOnOrAfter(
                    patientUuid, visitUuid, procedureDesc, orderedAtInstant(serviceRequest));
        } catch (Exception e) {
            log.warn("Could not read Odoo for accepted Task {} (ServiceRequest {}) - not verified: {}",
                    taskId, serviceRequestId, e.getMessage());
            return null;
        }

        if (!paid) {
            log.warn("ACCEPTED WITHOUT PAYMENT - Task {} is ACCEPTED but ServiceRequest {} has no paid invoice "
                    + "line in Odoo on its own visit. patient={} visit={} procedure='{}' ordered={} performed={}. "
                    + "The payment gate let this order through; nothing has been changed. See issue #322.",
                    taskId,
                    serviceRequestId,
                    patientUuid,
                    visitUuid,
                    procedureDesc,
                    orderedAt(serviceRequest),
                    performedAt(serviceRequest));
        }
        return paid;
    }

    /** The order's visit uuid (its encounter's partOf), or null if it cannot be resolved. */
    private String resolveVisitUuid(ServiceRequest serviceRequest) {
        try {
            if (serviceRequest.getEncounter() == null || serviceRequest.getEncounter().getReference() == null) {
                return null;
            }
            String encounterId = serviceRequest.getEncounter().getReferenceElement().getIdPart();
            Encounter encounter = encounterHandler.getEncounterByEncounterID(encounterId);
            if (encounter == null || encounter.getPartOf() == null || encounter.getPartOf().getReference() == null) {
                return null;
            }
            return encounter.getPartOf().getReferenceElement().getIdPart();
        } catch (Exception e) {
            log.warn("Could not resolve the visit for ServiceRequest {}: {}",
                    serviceRequest.getIdElement().getIdPart(), e.getMessage());
            return null;
        }
    }
}

/*
 * Copyright © 2021, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.processors;

import com.ozonehis.eip.odoo.openmrs.handlers.odoo.PartnerHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.SaleOrderHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.EncounterHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.PatientHandler;
import com.ozonehis.eip.odoo.openmrs.model.Partner;
import com.ozonehis.eip.odoo.openmrs.model.SaleOrder;
import java.util.List;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.openmrs.eip.fhir.Constants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Setter
@Component
public class ServiceRequestProcessor implements Processor {

    @Autowired
    private SaleOrderHandler saleOrderHandler;

    @Autowired
    private PartnerHandler partnerHandler;

    @Autowired
    private PatientHandler patientHandler;

    @Autowired
    private EncounterHandler encounterHandler;

    @Override
    public void process(Exchange exchange) {
        try (ProducerTemplate producerTemplate = exchange.getContext().createProducerTemplate()) {
            Bundle bundle = exchange.getMessage().getBody(Bundle.class);
            List<Bundle.BundleEntryComponent> entries = bundle.getEntry();

            Patient patient = null;
            Encounter encounter = null;
            ServiceRequest serviceRequest = null;
            for (Bundle.BundleEntryComponent entry : entries) {
                Resource resource = entry.getResource();
                if (resource instanceof Patient) {
                    patient = (Patient) resource;
                } else if (resource instanceof Encounter) {
                    encounter = (Encounter) resource;
                } else if (resource instanceof ServiceRequest) {
                    serviceRequest = (ServiceRequest) resource;
                }
            }

            if (serviceRequest == null) {
                throw new CamelExecutionException("Invalid Bundle. Bundle must contain ServiceRequest", exchange);
            }
            if (patient == null) {
                patient = patientHandler.getPatientByPatientID(
                        serviceRequest.getSubject().getReference().split("/")[1]);
            }
            if (encounter == null) {
                encounter = encounterHandler.getEncounterByEncounterID(
                        serviceRequest.getEncounter().getReference().split("/")[1]);
            }
            if (patient == null || encounter == null) {
                throw new CamelExecutionException(
                        "Invalid Bundle. Bundle must contain Patient and Encounter", exchange);
            } else {
                log.debug("Processing ServiceRequest for Patient with UUID {}", patient.getIdPart());
                String eventType = exchange.getMessage().getHeader(Constants.HEADER_FHIR_EVENT_TYPE, String.class);
                if (eventType == null) {
                    throw new IllegalArgumentException("Event type not found in the exchange headers.");
                }
                String encounterVisitUuid = encounter.getPartOf().getReference().split("/")[1];
                Partner partner = partnerHandler.createOrUpdatePartner(producerTemplate, patient);

                log.warn("ServiceRequestProcessor: Processing event type: {}", eventType);
                log.warn(
                        "ServiceRequestProcessor: ServiceRequest ID: {}, Status: {}, Intent: {}",
                        serviceRequest.getIdPart(),
                        serviceRequest.getStatus(),
                        serviceRequest.getIntent());
                log.warn("ServiceRequestProcessor: Encounter Visit UUID: {}", encounterVisitUuid);

                // Determine the actual action based on event type and service request status
                String actualAction = determineActualAction(eventType, serviceRequest);
                log.warn("ServiceRequestProcessor: Determined action: {}", actualAction);

                if ("CREATE".equals(actualAction) || "UPDATE".equals(actualAction)) {
                    boolean isOrderIntent =
                            serviceRequest.getIntent().equals(ServiceRequest.ServiceRequestIntent.ORDER);
                    boolean isActiveStatus =
                            serviceRequest.getStatus().equals(ServiceRequest.ServiceRequestStatus.ACTIVE);
                    boolean isCompletedStatus =
                            serviceRequest.getStatus().equals(ServiceRequest.ServiceRequestStatus.COMPLETED);

                    if (isOrderIntent && (isActiveStatus || isCompletedStatus)) {
                        SaleOrder saleOrder = saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(encounterVisitUuid);
                        if (saleOrder != null) {
                            log.warn("ServiceRequestProcessor: Updating existing sale order");
                            saleOrderHandler.updateSaleOrderIfExistsWithSaleOrderLine(
                                    serviceRequest,
                                    saleOrder,
                                    encounterVisitUuid,
                                    partner.getPartnerId(),
                                    patient.getIdPart(),
                                    producerTemplate);
                        } else {
                            log.warn("ServiceRequestProcessor: Creating new sale order");
                            saleOrderHandler.createSaleOrderWithSaleOrderLine(
                                    serviceRequest,
                                    encounter,
                                    partner,
                                    encounterVisitUuid,
                                    patient.getIdPart(),
                                    producerTemplate);
                        }
                    } else {
                        log.warn(
                                "ServiceRequestProcessor: Deleting sale order line due to non-order intent or inactive status");
                        saleOrderHandler.deleteSaleOrderLine(serviceRequest, encounterVisitUuid, producerTemplate);
                    }
                } else if ("DELETE".equals(actualAction)) {
                    log.warn("ServiceRequestProcessor: Processing actual discontinuation");
                    saleOrderHandler.deleteSaleOrderLine(serviceRequest, encounterVisitUuid, producerTemplate);
                    saleOrderHandler.cancelSaleOrderWhenNoSaleOrderLine(
                            partner.getPartnerId(), encounterVisitUuid, producerTemplate);
                } else {
                    throw new IllegalArgumentException("Unsupported action determined: " + actualAction);
                }
            }
        } catch (Exception e) {
            throw new CamelExecutionException("Error processing ServiceRequest", exchange, e);
        }
    }

    /**
     * Determines the actual action to take based on event type and service request status.
     * This helps distinguish between actual discontinuation and result addition.
     */
    private String determineActualAction(String eventType, ServiceRequest serviceRequest) {
        ServiceRequest.ServiceRequestStatus status = serviceRequest.getStatus();
        ServiceRequest.ServiceRequestIntent intent = serviceRequest.getIntent();

        log.warn("ServiceRequestProcessor - Current status: {}", status);

        if ("c".equals(eventType)) {
            return "CREATE";
        } else if ("u".equals(eventType)) {
            return "UPDATE";
        } else if ("d".equals(eventType)) {
            // Check if this is actually a result addition (completed status) rather than discontinuation
            if (status == ServiceRequest.ServiceRequestStatus.COMPLETED
                    && intent == ServiceRequest.ServiceRequestIntent.ORDER) {
                log.warn("ServiceRequestProcessor: Treating 'd' event as UPDATE due to COMPLETED status");
                return "UPDATE";
            } else if (status == ServiceRequest.ServiceRequestStatus.ENTEREDINERROR
                    || status == ServiceRequest.ServiceRequestStatus.REVOKED) {
                return "DELETE";
            } else {
                // For other statuses with 'd' event, log and treat as update to be safe
                log.warn("ServiceRequestProcessor: 'd' event with status {}, treating as UPDATE", status);
                return "UPDATE";
            }
        }

        return "UNKNOWN";
    }
}

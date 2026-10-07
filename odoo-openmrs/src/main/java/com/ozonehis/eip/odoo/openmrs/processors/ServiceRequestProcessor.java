/*
 * Copyright © 2021, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.processors;

import com.ozonehis.eip.odoo.openmrs.handlers.odoo.CompanyHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.PartnerHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.SaleOrderHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.EncounterHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.OrderHandler;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Setter
@Component
public class ServiceRequestProcessor implements Processor {

    @Value("${enable.encounter.location.to.company.mapping:false}")
    private boolean enableEncounterLocationCompanyMapping;

    @Autowired
    private SaleOrderHandler saleOrderHandler;

    @Autowired
    private PartnerHandler partnerHandler;

    @Autowired
    private PatientHandler patientHandler;

    @Autowired
    private EncounterHandler encounterHandler;

    @Autowired
    private CompanyHandler companyHandler;

    @Autowired
    private OrderHandler orderHandler;

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

                Integer companyId = null;
                if (enableEncounterLocationCompanyMapping) {
                    companyId = companyHandler.getCompanyIdByEncounterLocation(encounter);
                    if (companyId == null) {
                        log.warn(
                                "Skipping ServiceRequest sync as company id is null for ServiceRequest id {}",
                                serviceRequest.getIdPart());
                        return;
                    }
                }

                Partner partner = partnerHandler.createOrUpdatePartner(producerTemplate, patient, companyId);
                if ("c".equals(eventType) || "u".equals(eventType)) {
                    boolean isOrderIntent =
                            serviceRequest.getIntent().equals(ServiceRequest.ServiceRequestIntent.ORDER);
                    boolean isActiveStatus =
                            serviceRequest.getStatus().equals(ServiceRequest.ServiceRequestStatus.ACTIVE);
                    boolean isCompletedStatus =
                            serviceRequest.getStatus().equals(ServiceRequest.ServiceRequestStatus.COMPLETED);

                    if (isOrderIntent
                            && "u".equals(eventType)
                            && isCompletedStatus
                            && !orderHandler.isFulfilled(serviceRequest.getIdPart())) {
                        // FHIR2 reads a ServiceRequest as COMPLETED as soon as the order is stopped, so this is
                        // also the update of an order that was discontinued or revised before it was carried
                        // out. Its line must not be (re)added: the DISCONTINUE event removes it.
                        log.info(
                                "ServiceRequest {} was stopped without being fulfilled. No sale order line added.",
                                serviceRequest.getIdPart());
                    } else if (isOrderIntent) {
                        SaleOrder saleOrder =
                                saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(encounterVisitUuid, companyId);
                        if (isActiveStatus || ("u".equals(eventType) && isCompletedStatus)) {
                            if (saleOrder != null) {
                                saleOrderHandler.updateSaleOrderIfExistsWithSaleOrderLine(
                                        serviceRequest,
                                        saleOrder,
                                        encounterVisitUuid,
                                        partner.getPartnerId(),
                                        patient.getIdPart(),
                                        companyId,
                                        producerTemplate);
                            } else {
                                saleOrderHandler.createSaleOrderWithSaleOrderLine(
                                        serviceRequest,
                                        encounter,
                                        partner,
                                        encounterVisitUuid,
                                        patient.getIdPart(),
                                        companyId,
                                        producerTemplate);
                            }
                        } else {
                            // Executed when MODIFY option is selected in OpenMRS for other statuses
                            saleOrderHandler.deleteSaleOrderLine(
                                    serviceRequest, encounterVisitUuid, companyId, producerTemplate);
                        }
                    } else {
                        // Executed when MODIFY option is selected in OpenMRS
                        saleOrderHandler.deleteSaleOrderLine(
                                serviceRequest, encounterVisitUuid, companyId, producerTemplate);
                    }
                } else if ("d".equals(eventType)) {
                    // Executed when a DISCONTINUE option is selected in OpenMRS, and when the lab app
                    // discontinues an order after entering its result. The ServiceRequest status cannot tell the
                    // two apart (FHIR2 reads every stopped order as COMPLETED), so the order's fulfiller status
                    // decides: a fulfilled order stays billed, any other loses its line on the quotation.
                    String fulfillerStatus = orderHandler.getFulfillerStatus(serviceRequest.getIdPart());
                    if (OrderHandler.FULFILLER_STATUS_COMPLETED.equals(fulfillerStatus)) {
                        log.info(
                                "ServiceRequest {} was discontinued after it was fulfilled. Its sale order line is kept.",
                                serviceRequest.getIdPart());
                    } else {
                        log.info(
                                "ServiceRequest {} was discontinued with fulfiller status {}. Removing its sale order line.",
                                serviceRequest.getIdPart(),
                                fulfillerStatus);
                        saleOrderHandler.removeSaleOrderLineFromQuotation(
                                serviceRequest, encounterVisitUuid, companyId, producerTemplate);
                        saleOrderHandler.cancelSaleOrderWhenNoSaleOrderLine(
                                partner.getPartnerId(), encounterVisitUuid, companyId, producerTemplate);
                    }
                } else {
                    throw new IllegalArgumentException("Unsupported event type: " + eventType);
                }
            }
        } catch (Exception e) {
            throw new CamelExecutionException("Error processing ServiceRequest", exchange, e);
        }
    }
}

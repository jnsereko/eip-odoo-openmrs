/*
 * Copyright © 2021, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.processors;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.PartnerHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.SaleOrderHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.EncounterHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.MedicationRequestHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.PatientHandler;
import com.ozonehis.eip.odoo.openmrs.model.Partner;
import com.ozonehis.eip.odoo.openmrs.model.SaleOrder;
import java.util.ArrayList;
import java.util.List;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MedicationRequest;
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

    @Autowired
    private MedicationRequestHandler medicationRequestHandler;

    @Autowired
    private IGenericClient openmrsFhirClient;

    @Override
    public void process(Exchange exchange) {
        try (ProducerTemplate producerTemplate = exchange.getContext().createProducerTemplate()) {
            Bundle bundle = exchange.getMessage().getBody(Bundle.class);
            List<BundleEntryComponent> entries = bundle.getEntry();

            Patient patient = null;
            Encounter encounter = null;
            ServiceRequest serviceRequest = null;
            MedicationRequest medicationRequest = null;

            for (BundleEntryComponent entry : entries) {
                Resource resource = entry.getResource();
                if (resource instanceof Patient) {
                    patient = (Patient) resource;
                } else if (resource instanceof Encounter) {
                    encounter = (Encounter) resource;
                } else if (resource instanceof ServiceRequest) {
                    serviceRequest = (ServiceRequest) resource;
                } else if (resource instanceof MedicationRequest) {
                    medicationRequest = (MedicationRequest) resource;
                }
            }

            medicationRequestHandler.logMedicationRequestStatus(medicationRequest);

            if (serviceRequest == null) {
                throw new CamelExecutionException("Invalid Bundle. Bundle must contain ServiceRequest", exchange);
            }

            // Get patient and encounter if not in bundle
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
            }

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
            String actualAction = determineActualAction(eventType, serviceRequest, bundle);
            log.warn("ServiceRequestProcessor: Determined action: {}", actualAction);

            if ("CREATE".equals(actualAction) || "UPDATE".equals(actualAction)) {
                boolean isOrderIntent = serviceRequest.getIntent().equals(ServiceRequest.ServiceRequestIntent.ORDER);
                boolean isActiveStatus = serviceRequest.getStatus().equals(ServiceRequest.ServiceRequestStatus.ACTIVE);
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
        } catch (Exception e) {
            throw new CamelExecutionException("Error processing ServiceRequest", exchange, e);
        }
    }

    /**
     * Determines the actual action to take based on event type and service request status.
     * This helps distinguish between actual discontinuation and result addition.
     */
    private String determineActualAction(String eventType, ServiceRequest serviceRequest, Bundle bundle) {
        ServiceRequest.ServiceRequestStatus status = serviceRequest.getStatus();
        ServiceRequest.ServiceRequestIntent intent = serviceRequest.getIntent();
        String serviceRequestId = serviceRequest.getIdElement().getIdPart();

        log.warn("ServiceRequestProcessor - Current status: {}", status);

        if ("c".equals(eventType)) {
            return "CREATE";
        } else if ("u".equals(eventType)) {
            return "UPDATE";
        } else if ("d".equals(eventType)) {
            // Check if there's a Task with T.for = this ServiceRequest

            // If no relevant tasks found, infer based on replaces relationship and results
            if (hasReplacingDiscontinueRequest(bundle, serviceRequestId)) {
                log.warn(
                        "ServiceRequestProcessor: Found replacing ServiceRequest with discontinue reason for {}, treating as DELETE",
                        serviceRequestId);
                return "DELETE";
            }

            // Fallback to original logic for status-based determination
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

    private boolean hasReplacingDiscontinueRequest(Bundle bundle, String serviceRequestId) {
        // Query for ServiceRequests where replaces references this ServiceRequest
        // In OpenMRS FHIR, you need to search for orders that replace this one

        if (serviceRequestId == null || serviceRequestId.isEmpty()) {
            log.warn("ServiceRequest ID is null or empty, cannot search for replacing requests");
            return false;
        }

        try {
            log.warn("Searching for ServiceRequests that replace serviceRequestId: {}", serviceRequestId);

            // Option 1: Using FHIR client search

            List<ServiceRequest> replacingRequests = extractServiceRequestsFromBundle(bundle);

            if (replacingRequests.isEmpty()) {
                log.warn("No replacing ServiceRequests found for serviceRequestId: {}", serviceRequestId);
                return false;
            }

            log.warn(
                    "Found {} replacing ServiceRequests for serviceRequestId: {}",
                    replacingRequests.size(),
                    serviceRequestId);

            // Option 2: If you have a custom FHIR service
            // List<ServiceRequest> replacingRequests = fhirService.findServiceRequestsByReplaces(serviceRequestId);

            for (ServiceRequest replacingRequest : replacingRequests) {
                // In OpenMRS, check if this is a discontinuation order
                if (isDiscontinueRequest(replacingRequest)) {
                    log.info(
                            "Found discontinuation request {} replacing serviceRequestId: {}",
                            replacingRequest.getId(),
                            serviceRequestId);
                    return true;
                }
            }

            log.warn(
                    "No discontinuation requests found among replacing ServiceRequests for serviceRequestId: {}",
                    serviceRequestId);
            return false;

        } catch (Exception e) {
            log.warn(
                    "Error searching for replacing ServiceRequests for serviceRequestId: {} - {}",
                    serviceRequestId,
                    e.getMessage(),
                    e);
            return false;
        }
    }

    private List<ServiceRequest> extractServiceRequestsFromBundle(Bundle bundle) {
        List<ServiceRequest> serviceRequests = new ArrayList<>();

        if (bundle == null) {
            log.warn("Bundle is null when extracting ServiceRequests");
            return serviceRequests;
        }

        if (bundle.getEntry() == null) {
            log.warn("Bundle has no entries");
            return serviceRequests;
        }

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry.getResource() == null) {
                log.warn("Bundle entry has null resource");
                continue;
            }

            if (entry.getResource() instanceof ServiceRequest) {
                serviceRequests.add((ServiceRequest) entry.getResource());
            } else {
                log.warn(
                        "Bundle entry contains unexpected resource type: {}",
                        entry.getResource().getResourceType());
            }
        }

        return serviceRequests;
    }

    private boolean isDiscontinueRequest(ServiceRequest serviceRequest) {
        // In OpenMRS FHIR, check multiple indicators for discontinuation:

        if (serviceRequest == null) {
            log.warn("ServiceRequest is null in isDiscontinueRequest check");
            return false;
        }

        String serviceRequestId = serviceRequest.hasId() ? serviceRequest.getId() : "unknown";
        log.warn("Checking if ServiceRequest {} is a discontinue request", serviceRequestId);

        // 1. Check the intent - discontinued orders often have specific intents
        if (serviceRequest.hasIntent()
                && ServiceRequest.ServiceRequestIntent.REFLEXORDER.equals(serviceRequest.getIntent())) {
            // This might indicate a discontinuation in some OpenMRS configurations
            log.warn("ServiceRequest {} has REFLEXORDER intent, may indicate discontinuation", serviceRequestId);
        }

        // 2. Check reason codes for discontinuation
        if (serviceRequest.hasReasonCode() && isDiscontinueReason(serviceRequest.getReasonCode())) {
            log.warn("ServiceRequest {} identified as discontinuation via reason codes", serviceRequestId);
            return true;
        }

        // 3. Check categories or other extensions that might indicate discontinuation
        if (serviceRequest.hasCategory()) {
            for (CodeableConcept category : serviceRequest.getCategory()) {
                if (isDiscontinueCategory(category)) {
                    log.warn("ServiceRequest {} identified as discontinuation via category", serviceRequestId);
                    return true;
                }
            }
        }

        // 4. Check OpenMRS-specific extensions if they exist
        if (hasDiscontinuationExtension(serviceRequest)) {
            log.warn("ServiceRequest {} identified as discontinuation via extension", serviceRequestId);
            return true;
        }

        log.warn("ServiceRequest {} does not appear to be a discontinuation request", serviceRequestId);
        return false;
    }

    private boolean isDiscontinueReason(List<CodeableConcept> reasonCodes) {
        if (reasonCodes == null || reasonCodes.isEmpty()) {
            log.warn("No reason codes provided for discontinuation check");
            return false;
        }

        log.warn("Checking {} reason codes for discontinuation indicators", reasonCodes.size());

        for (CodeableConcept reason : reasonCodes) {
            if (reason == null) {
                log.warn("Null reason code encountered in discontinuation check");
                continue;
            }

            if (reason.hasCoding()) {
                for (Coding coding : reason.getCoding()) {
                    if (coding == null) {
                        log.warn("Null coding encountered in reason code");
                        continue;
                    }

                    String code = coding.getCode();
                    String display = coding.getDisplay();
                    String system = coding.getSystem();

                    log.warn("Checking coding - code: {}, display: {}, system: {}", code, display, system);

                    // Check common discontinuation codes
                    if (code != null
                            && ("discontinue".equalsIgnoreCase(code)
                                    || "revoke".equalsIgnoreCase(code)
                                    || "cancel".equalsIgnoreCase(code)
                                    || "stop".equalsIgnoreCase(code))) {
                        log.warn("Found discontinuation code: {}", code);
                        return true;
                    }

                    if (display != null
                            && ("discontinuation".equalsIgnoreCase(display)
                                    || "revocation".equalsIgnoreCase(display)
                                    || "cancellation".equalsIgnoreCase(display)
                                    || "stop order".equalsIgnoreCase(display))) {
                        log.warn("Found discontinuation display: {}", display);
                        return true;
                    }

                    // Check OpenMRS-specific code systems if known
                    if (system != null
                            && system.contains("openmrs")
                            && code != null
                            && isOpenMRSDiscontinueCode(code)) {
                        log.warn("Found OpenMRS discontinuation code: {} in system: {}", code, system);
                        return true;
                    }
                }
            } else {
                log.warn("Reason code has no coding elements");
            }
        }

        log.warn("No discontinuation indicators found in reason codes");
        return false;
    }

    private boolean isDiscontinueCategory(CodeableConcept category) {
        if (category.hasCoding()) {
            for (Coding coding : category.getCoding()) {
                String code = coding.getCode();
                if (code != null
                        && ("discontinue".equalsIgnoreCase(code)
                                || "cancel".equalsIgnoreCase(code)
                                || "revoke".equalsIgnoreCase(code))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasDiscontinuationExtension(ServiceRequest serviceRequest) {
        // Check for OpenMRS-specific extensions that might indicate discontinuation
        if (!serviceRequest.hasExtension()) {
            log.warn("ServiceRequest has no extensions to check for discontinuation");
            return false;
        }

        log.warn(
                "Checking {} extensions for discontinuation indicators",
                serviceRequest.getExtension().size());

        for (Extension extension : serviceRequest.getExtension()) {
            if (extension == null) {
                log.warn("Null extension encountered");
                continue;
            }

            String url = extension.getUrl();
            if (url != null && url.contains("discontinu")) {
                log.warn("Found potential discontinuation extension with URL: {}", url);
                return true;
            }

            // Check extension value
            if (extension.hasValue() && extension.getValue() instanceof BooleanType) {
                BooleanType boolValue = (BooleanType) extension.getValue();
                if (boolValue.booleanValue()) {
                    log.warn("Found extension with true boolean value, URL: {}", url);
                    return true;
                }
            }
        }

        log.warn("No discontinuation extensions found");
        return false;
    }

    private boolean isOpenMRSDiscontinueCode(String code) {
        // Add OpenMRS-specific discontinuation codes here
        // This would depend on your OpenMRS configuration
        return "DISCONTINUE".equals(code) || "DC".equals(code) || "CANCEL".equals(code);
    }
}

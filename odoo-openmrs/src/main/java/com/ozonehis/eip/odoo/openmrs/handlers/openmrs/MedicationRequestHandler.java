/*
 * Copyright © 2021, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers.openmrs;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Component
public class MedicationRequestHandler {

    @Autowired
    private IGenericClient openmrsFhirClient;

    String OPENMRS_FHIR_EXT_MEDICATION_REQUEST_FULFILLER_STATUS =
            "http://fhir.openmrs.org/ext/medicationrequest/fullfillerstatus";

    /**
     * Get MedicationRequest by ID
     */
    public MedicationRequest getMedicationRequestByID(String medicationRequestID) {
        MedicationRequest medicationRequest = openmrsFhirClient
                .read()
                .resource(MedicationRequest.class)
                .withId(medicationRequestID)
                .execute();

        log.info("MedicationRequestHandler: MedicationRequest getMedicationRequestByID {}", medicationRequest.getId());
        logMedicationRequestStatus(medicationRequest);
        return medicationRequest;
    }

    /**
     * Get MedicationRequests by Patient ID
     */
    public List<MedicationRequest> getMedicationRequestsByPatientID(String patientID) {
        Bundle bundle = openmrsFhirClient
                .search()
                .forResource(MedicationRequest.class)
                .where(MedicationRequest.SUBJECT.hasId(patientID))
                .sort()
                .descending(MedicationRequest.AUTHOREDON)
                .returnBundle(Bundle.class)
                .execute();

        log.debug(
                "MedicationRequestHandler: MedicationRequests getMedicationRequestsByPatientID bundle {}",
                bundle.getId());

        List<MedicationRequest> medicationRequests = bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(MedicationRequest.class::isInstance)
                .map(MedicationRequest.class::cast)
                .collect(Collectors.toList());

        log.info(
                "MedicationRequestHandler: Found {} MedicationRequests for patient {}",
                medicationRequests.size(),
                patientID);
        medicationRequests.forEach(this::logMedicationRequestStatus);

        return medicationRequests;
    }

    /**
     * Get MedicationRequests by Patient ID and Encounter ID
     */
    public List<MedicationRequest> getMedicationRequestsByPatientAndEncounter(String patientID, String encounterID) {
        Bundle bundle = openmrsFhirClient
                .search()
                .forResource(MedicationRequest.class)
                .where(MedicationRequest.SUBJECT.hasId(patientID))
                .and(MedicationRequest.ENCOUNTER.hasId(encounterID))
                .sort()
                .descending(MedicationRequest.AUTHOREDON)
                .returnBundle(Bundle.class)
                .execute();

        log.debug(
                "MedicationRequestHandler: MedicationRequests getMedicationRequestsByPatientAndEncounter bundle {}",
                bundle.getId());

        List<MedicationRequest> medicationRequests = bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(MedicationRequest.class::isInstance)
                .map(MedicationRequest.class::cast)
                .collect(Collectors.toList());

        log.info(
                "MedicationRequestHandler: Found {} MedicationRequests for patient {} and encounter {}",
                medicationRequests.size(),
                patientID,
                encounterID);
        medicationRequests.forEach(this::logMedicationRequestStatus);

        return medicationRequests;
    }

    /**
     * Get active MedicationRequests by Patient ID
     */
    public List<MedicationRequest> getActiveMedicationRequestsByPatientID(String patientID) {
        Bundle bundle = openmrsFhirClient
                .search()
                .forResource(MedicationRequest.class)
                .where(MedicationRequest.SUBJECT.hasId(patientID))
                .and(MedicationRequest.STATUS.exactly().code("active"))
                .sort()
                .descending(MedicationRequest.AUTHOREDON)
                .returnBundle(Bundle.class)
                .execute();

        log.debug(
                "MedicationRequestHandler: Active MedicationRequests getActiveMedicationRequestsByPatientID bundle {}",
                bundle.getId());

        List<MedicationRequest> medicationRequests = bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(MedicationRequest.class::isInstance)
                .map(MedicationRequest.class::cast)
                .collect(Collectors.toList());

        log.info(
                "MedicationRequestHandler: Found {} active MedicationRequests for patient {}",
                medicationRequests.size(),
                patientID);
        medicationRequests.forEach(this::logMedicationRequestStatus);

        return medicationRequests;
    }

    /**
     * Get MedicationRequests by status
     */
    public List<MedicationRequest> getMedicationRequestsByStatus(String patientID, String status) {
        Bundle bundle = openmrsFhirClient
                .search()
                .forResource(MedicationRequest.class)
                .where(MedicationRequest.SUBJECT.hasId(patientID))
                .and(MedicationRequest.STATUS.exactly().code(status))
                .sort()
                .descending(MedicationRequest.AUTHOREDON)
                .returnBundle(Bundle.class)
                .execute();

        log.debug(
                "MedicationRequestHandler: MedicationRequests by status {} for patient {} bundle {}",
                status,
                patientID,
                bundle.getId());

        List<MedicationRequest> medicationRequests = bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(MedicationRequest.class::isInstance)
                .map(MedicationRequest.class::cast)
                .collect(Collectors.toList());

        log.info(
                "MedicationRequestHandler: Found {} MedicationRequests with status {} for patient {}",
                medicationRequests.size(),
                status,
                patientID);
        medicationRequests.forEach(this::logMedicationRequestStatus);

        return medicationRequests;
    }

    /**
     * Log MedicationRequest status and details
     */
    public void logMedicationRequestStatus(MedicationRequest medicationRequest) {
        if (medicationRequest == null) {
            log.warn("MedicationRequestHandler: Cannot log null MedicationRequest");
            return;
        }

        Extension status = medicationRequest.getExtensionByUrl(OPENMRS_FHIR_EXT_MEDICATION_REQUEST_FULFILLER_STATUS);
        MedicationRequest.MedicationRequestIntent intent = medicationRequest.getIntent();
        MedicationRequest.MedicationRequestPriority priority = medicationRequest.getPriority();

        log.info("=== MedicationRequest Details ===");
        log.info("ID: {}", medicationRequest.getIdPart());
        log.info(
                "Status: {} ({})",
                status,
                status != null ? status.getValue().toString().toUpperCase() : "N/A");
        log.info("Intent: {} ({})", intent, intent != null ? intent.getDisplay() : "N/A");
        log.info("Priority: {} ({})", priority, priority != null ? priority.getDisplay() : "N/A");

        if (medicationRequest.hasAuthoredOn()) {
            log.info("Authored On: {}", medicationRequest.getAuthoredOn());
        }

        if (medicationRequest.hasSubject()) {
            log.info("Patient: {}", medicationRequest.getSubject().getReference());
        }

        if (medicationRequest.hasEncounter()) {
            log.info("Encounter: {}", medicationRequest.getEncounter().getReference());
        }

        if (medicationRequest.hasMedicationCodeableConcept()) {
            log.info(
                    "Medication: {}",
                    medicationRequest.getMedicationCodeableConcept().getText());
        } else if (medicationRequest.hasMedicationReference()) {
            log.info(
                    "Medication Reference: {}",
                    medicationRequest.getMedicationReference().getReference());
        }

        // log.info("Status Code: {}", status != null ? status.toCode() : "N/A");
        log.info("================================");
    }

    /**
     * Check if MedicationRequest status indicates active treatment
     */
    public boolean isActiveTreatment(MedicationRequest medicationRequest) {
        if (medicationRequest == null || !medicationRequest.hasStatus()) {
            return false;
        }

        MedicationRequest.MedicationRequestStatus status = medicationRequest.getStatus();
        return status == MedicationRequest.MedicationRequestStatus.ACTIVE
                || status == MedicationRequest.MedicationRequestStatus.ONHOLD;
    }

    /**
     * Check if MedicationRequest status indicates completed treatment
     */
    public boolean isCompletedTreatment(MedicationRequest medicationRequest) {
        if (medicationRequest == null || !medicationRequest.hasStatus()) {
            return false;
        }

        return medicationRequest.getStatus() == MedicationRequest.MedicationRequestStatus.COMPLETED;
    }

    /**
     * Check if MedicationRequest status indicates cancelled/stopped treatment
     */
    public boolean isCancelledOrStopped(MedicationRequest medicationRequest) {
        if (medicationRequest == null || !medicationRequest.hasStatus()) {
            return false;
        }

        MedicationRequest.MedicationRequestStatus status = medicationRequest.getStatus();
        return status == MedicationRequest.MedicationRequestStatus.CANCELLED
                || status == MedicationRequest.MedicationRequestStatus.STOPPED
                || status == MedicationRequest.MedicationRequestStatus.ENTEREDINERROR;
    }

    /**
     * Determine action based on MedicationRequest status and event type
     */
    public String determineMedicationAction(String eventType, MedicationRequest medicationRequest) {
        MedicationRequest.MedicationRequestStatus status = medicationRequest.getStatus();
        MedicationRequest.MedicationRequestIntent intent = medicationRequest.getIntent();

        log.info(
                "MedicationRequestHandler: Determining action for eventType: {}, status: {}, intent: {}",
                eventType,
                status,
                intent);

        if ("c".equals(eventType)) {
            return "CREATE";
        } else if ("u".equals(eventType)) {
            return "UPDATE";
        } else if ("d".equals(eventType)) {
            // Check if this is actual discontinuation or status change
            if (status == MedicationRequest.MedicationRequestStatus.ACTIVE) {
                log.info("MedicationRequestHandler: MedicationRequest is ACTIVE, treating 'd' event as UPDATE");
                return "UPDATE";
            } else if (status == MedicationRequest.MedicationRequestStatus.COMPLETED) {
                log.info("MedicationRequestHandler: MedicationRequest is COMPLETED, treating 'd' event as UPDATE");
                return "UPDATE";
            } else if (status == MedicationRequest.MedicationRequestStatus.ONHOLD) {
                log.info("MedicationRequestHandler: MedicationRequest is ON_HOLD, treating 'd' event as UPDATE");
                return "UPDATE";
            } else if (isCancelledOrStopped(medicationRequest)) {
                log.info("MedicationRequestHandler: MedicationRequest is cancelled/stopped, treating as DELETE");
                return "DELETE";
            } else {
                log.warn(
                        "MedicationRequestHandler: Unhandled MedicationRequest status: {}, treating 'd' event as UPDATE",
                        status);
                return "UPDATE";
            }
        }

        return "UNKNOWN";
    }
}

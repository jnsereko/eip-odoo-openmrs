/*
 * Copyright © 2026, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers.openmrs;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.MedicationDispense;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Setter
@Component
public class MedicationDispenseHandler {

    @Autowired
    private IGenericClient openmrsFhirClient;

    /**
     * Whether anything was actually dispensed against the given MedicationRequest, i.e. it has a
     * MedicationDispense in status completed or in-progress. Pausing (on-hold) and closing (declined) a
     * prescription in the dispensing app also create MedicationDispense records, so those do not count.
     *
     * <p>The authorizingPrescription link is re-checked here rather than left to the server, because OpenMRS
     * silently ignores search parameters it does not support and would then return every dispense.
     */
    public boolean hasDispensedQuantity(String medicationRequestId) {
        Bundle bundle = openmrsFhirClient
                .search()
                .forResource(MedicationDispense.class)
                .where(MedicationDispense.PRESCRIPTION.hasId("MedicationRequest/" + medicationRequestId))
                .returnBundle(Bundle.class)
                .execute();

        return bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(MedicationDispense.class::isInstance)
                .map(MedicationDispense.class::cast)
                .filter(dispense -> isAuthorizedBy(dispense, medicationRequestId))
                .anyMatch(dispense -> dispense.getStatus() == MedicationDispense.MedicationDispenseStatus.COMPLETED
                        || dispense.getStatus() == MedicationDispense.MedicationDispenseStatus.INPROGRESS);
    }

    private boolean isAuthorizedBy(MedicationDispense dispense, String medicationRequestId) {
        return dispense.getAuthorizingPrescription().stream()
                .map(org.hl7.fhir.r4.model.Reference::getReference)
                .filter(reference -> reference != null && !reference.isEmpty())
                .anyMatch(reference -> medicationRequestId.equals(reference.substring(reference.lastIndexOf('/') + 1)));
    }
}

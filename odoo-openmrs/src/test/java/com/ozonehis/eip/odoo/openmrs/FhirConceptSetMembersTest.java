/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.Test;

/** Reading an OpenMRS concept set's members from its FHIR ValueSet (UVL-EMR#340). */
class FhirConceptSetMembersTest {

    private static final String RX01 = "e3dea2c8-62c6-4487-bdaa-1d009642f7ad";

    private static final String EC01 = "8155e2e0-5b62-42bc-b47c-0702aaafe3df";

    /** Shaped like OpenMRS FHIR2's ValueSetTranslatorImpl output for a concept set. */
    private static ValueSet openmrsValueSet() {
        ValueSet valueSet = new ValueSet();
        ValueSet.ConceptSetComponent byUuid = valueSet.getCompose().addInclude();
        byUuid.addConcept().setCode(RX01).setDisplay("RX01 - Chest X-ray");
        byUuid.addConcept().setCode(EC01).setDisplay("EC01 - Abdominal ultrasound");
        // A member that is itself a set is a reference, not a concept: not followed.
        byUuid.addValueSet("ValueSet/0f6e7d4c-1b2a-4c3d-8e9f-a0b1c2d3e4f5");
        // The members' mappings, one include per source with a system: not uuids, skipped.
        ValueSet.ConceptSetComponent ciel = valueSet.getCompose().addInclude();
        ciel.setSystem("https://cielterminology.org");
        ciel.addConcept().setCode("165065");
        return valueSet;
    }

    @Test
    void membersAreTheUuidsOfTheIncludeWithoutASystem() {
        assertEquals(
                new LinkedHashSet<>(Arrays.asList(RX01, EC01)), FhirConceptSetMembers.membersOf(openmrsValueSet()));
    }

    @Test
    void aValueSetWithoutComposeHasNoMembers() {
        assertTrue(FhirConceptSetMembers.membersOf(new ValueSet()).isEmpty());
        assertTrue(FhirConceptSetMembers.membersOf(null).isEmpty());
    }
}

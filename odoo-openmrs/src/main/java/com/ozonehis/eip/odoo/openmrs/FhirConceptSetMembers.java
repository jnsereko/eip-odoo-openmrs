/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.hl7.fhir.r4.model.ValueSet;

/**
 * Reads the members of an OpenMRS concept set as FHIR {@code ValueSet/{uuid}} (UVL-EMR#340).
 *
 * <p>OpenMRS's FHIR2 module renders a concept set as a ValueSet whose {@code compose.include}
 * without a {@code system} lists each member by its concept uuid; the includes with a system repeat
 * the members' mappings (CIEL, ...) and are skipped. A member that is itself a set appears there as
 * a {@code valueSet} reference and is not followed.
 */
public class FhirConceptSetMembers implements Function<String, Set<String>> {

    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final IGenericClient openmrsFhirClient;

    public FhirConceptSetMembers(IGenericClient openmrsFhirClient) {
        this.openmrsFhirClient = openmrsFhirClient;
    }

    @Override
    public Set<String> apply(String conceptSetUuid) {
        ValueSet valueSet = openmrsFhirClient
                .read()
                .resource(ValueSet.class)
                .withId(conceptSetUuid)
                .execute();
        return membersOf(valueSet);
    }

    static Set<String> membersOf(ValueSet valueSet) {
        Set<String> uuids = new LinkedHashSet<>();
        if (valueSet == null || !valueSet.hasCompose()) {
            return uuids;
        }
        for (ValueSet.ConceptSetComponent include : valueSet.getCompose().getInclude()) {
            if (include.hasSystem()) {
                continue;
            }
            for (ValueSet.ConceptReferenceComponent concept : include.getConcept()) {
                String code = concept.getCode();
                if (code != null && UUID.matcher(code).matches()) {
                    uuids.add(code);
                }
            }
        }
        return uuids;
    }
}

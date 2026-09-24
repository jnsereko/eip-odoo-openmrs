/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.ServiceRequest;

/**
 * What counts as a radiology order.
 *
 * <p>Extracted so that everything in THIS repository decides it the same way. It previously lived
 * inline in {@code RadiologyPaymentTaskProcessor}, and adding a second consumer would have made a
 * second copy.
 *
 * <p>The list is read from the {@value #PROPERTY} property - set it as the environment variable
 * {@code RADIOLOGY_CONCEPT_UUIDS}, a comma-separated list of concept uuids (UVL-EMR#253). The
 * Orthanc bridge reads the same variable, so setting it once for both containers keeps payment
 * gating and worklist creation on the same list. With no value it is {@link #DEFAULT_UUIDS}, the
 * list both bridges hard-coded before, so an unconfigured deployment behaves exactly as it did.
 *
 * <p>A configured value that is blank or contains anything that is not a uuid is rejected as a
 * whole, with a WARN, and the default is used instead. It never becomes an empty or partial list:
 * an empty list would quietly drop every radiology order - no Task, no worklist entry, nothing
 * logged above INFO - which is the kind of silent failure this bridge has already had too many of.
 *
 * <p>This does NOT close issue #304. The Orthanc bridge still picks the modality by text heuristics
 * on the procedure name, and 13 ultrasound, CT and echocardiography concepts are in neither the
 * default list nor any site configuration yet. The durable fix is one shared definition both sides
 * read from the server (a concept set via FHIR ValueSet); this property is the step before that.
 * The imaging-gate frontend ({@code @jnsereko/esm-imaging-gate-app}, {@code config-schema.ts})
 * carries its own copy of the default list and must be kept in step by hand.
 */
@Slf4j
public class RadiologyConcepts {

    /** Property the list is read from. Spring maps the environment variable RADIOLOGY_CONCEPT_UUIDS onto it. */
    public static final String PROPERTY = "radiology.concept.uuids";

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public static final Set<String> DEFAULT_UUIDS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "e3dea2c8-62c6-4487-bdaa-1d009642f7ad", // RX01 - Chest X-ray
            "82e7d36c-078d-40c6-9854-92b376099307", // RX02 - Abdominal X-ray
            "701257a2-885e-4249-8319-d9597d2970af", // RX03 - Bone X-ray
            "b25dcc00-800f-48ac-b31a-f1e9cc53d787", // RX04 - Intravenous urography
            "81e0643c-a871-475e-8bd5-93945da8877d", // RX05 - Salpingo-urethrogram
            "1a5e3d73-f897-47ed-840b-d4537b7cc586", // RX06 - Barium enema
            "0a5ba175-fb7e-4d66-aa6a-ba058f3468c1", // RX07 - CT scan
            "d0b5d4a0-1001-0000-0000-000000000001",
            "d0b5d4a0-1002-0000-0000-000000000001",
            "d0b5d4a0-1003-0000-0000-000000000001",
            "d0b5d4a0-1004-0000-0000-000000000001",
            "d0b5d4a0-1005-0000-0000-000000000001",
            "d0b5d4a0-1006-0000-0000-000000000001",
            "d0b5d4a0-1007-0000-0000-000000000001",
            "d0b5d4a0-1008-0000-0000-000000000001")));

    private final Set<String> uuids;

    /** The default list, as before this was configurable. */
    public RadiologyConcepts() {
        this(null);
    }

    /**
     * @param configured the raw {@value #PROPERTY} value; null or blank means "use the default"
     */
    public RadiologyConcepts(String configured) {
        this.uuids = resolve(configured);
    }

    public Set<String> getUuids() {
        return uuids;
    }

    public boolean isRadiologyOrder(ServiceRequest serviceRequest) {
        if (serviceRequest == null || serviceRequest.getCode() == null) {
            return false;
        }
        return serviceRequest.getCode().getCoding().stream().anyMatch(coding -> uuids.contains(coding.getCode()));
    }

    private static Set<String> resolve(String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            log.info("{} not set - using the default {} radiology concepts", PROPERTY, DEFAULT_UUIDS.size());
            return DEFAULT_UUIDS;
        }
        Set<String> parsed = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();
        for (String entry : configured.split(",")) {
            String uuid = entry.trim();
            if (uuid.isEmpty()) {
                continue;
            }
            if (UUID.matcher(uuid).matches()) {
                parsed.add(uuid);
            } else {
                invalid.add(uuid);
            }
        }
        if (!invalid.isEmpty() || parsed.isEmpty()) {
            log.warn(
                    "{} is invalid ({}) - IGNORING it and using the default {} radiology concepts. Value was '{}'",
                    PROPERTY,
                    invalid.isEmpty() ? "no uuids in it" : "not uuids: " + invalid,
                    DEFAULT_UUIDS.size(),
                    configured);
            return DEFAULT_UUIDS;
        }
        log.info("{} set - using {} configured radiology concepts: {}", PROPERTY, parsed.size(), parsed);
        return Collections.unmodifiableSet(parsed);
    }
}

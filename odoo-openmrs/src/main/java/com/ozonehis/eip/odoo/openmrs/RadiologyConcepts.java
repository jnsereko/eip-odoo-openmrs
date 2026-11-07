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
 * gating and worklist creation on the same list. With no value it is {@link #DEFAULT_UUIDS}: the
 * X-ray and CT concepts both bridges hard-coded before, plus the EC01/EC02 ultrasound concepts
 * (UVL-EMR#304). The Orthanc bridge carries the same default.
 *
 * <p>A configured value that is blank or contains anything that is not a uuid is rejected as a
 * whole, with a WARN, and the default is used instead. It never becomes an empty or partial list:
 * an empty list would quietly drop every radiology order - no Task, no worklist entry, nothing
 * logged above INFO - which is the kind of silent failure this bridge has already had too many of.
 *
 * <p>The Orthanc bridge takes the DICOM modality from the concept, so a concept added here by
 * configuration also needs a modality there, or it falls back to a guess from the procedure name.
 * The remaining ultrasound, CT and echocardiography concepts of #304 are not in the default; the
 * durable fix is still one shared definition both sides read from the server (a concept set via
 * FHIR ValueSet), and this property is the step before that.
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
            "d0b5d4a0-1008-0000-0000-000000000001",
            "8155e2e0-5b62-42bc-b47c-0702aaafe3df", // EC01 - Abdominal ultrasound
            "521361cf-ce7d-49a6-9721-8ebad1b76702", // EC02 - Gastrointestinal ultrasound
            // The 22 body-part X-rays of M3 (UVL-EMR#212, #340), the same list the Orthanc bridge
            // carries: without them a paid ankle X-ray got no payment Task and never reached the
            // worklist (production, 2026-10-27).
            "dac7eb20-d3af-4097-a399-2b1a9f3e0ae4", // Forearm X-ray
            "e6d7bca3-cb2f-4926-bfbc-f89e90d573cd", // Humerus X-ray
            "97589f4b-8bf9-4342-b24f-605d359ce932", // Toe X-ray
            "e309ea51-86cd-4a83-883b-d5f8654e6627", // Shoulder X-ray
            "addae1d8-baa2-459c-b869-e98f0303a688", // Ankle X-ray
            "245e29a4-82de-4662-b176-92c768e49118", // Leg X-ray
            "c82d4492-308d-455d-9bf9-360c3a086743", // Hand X-ray
            "3765eebc-1324-4555-99c8-2b9324d5a185", // Pelvis X-ray
            "c9f0148b-362a-4b11-9bc3-8c3428a9bbb4", // Calcaneus X-ray
            "1bd135e8-d15c-45b4-9bc2-a232f5358912", // Coccyx X-ray
            "1407617f-d79a-43a5-868b-7dedcd2c560f", // Neck X-ray
            "bb1b2daf-584c-4bb6-8de2-aa678c7231fa", // Elbow X-ray
            "ba2a2d62-8220-45a7-9302-9092c48a363d", // Skull X-ray
            "c95e8fa1-b395-4042-9513-d8f33fb81a05", // Finger X-ray
            "41d7e98f-d485-4d27-81fc-70d44e9d6e1d", // Femur X-ray
            "1cb9c9f6-d3ad-42db-b38e-9cefa0b258fe", // Knee X-ray
            "740f0b00-3c24-4c86-b95b-b5c6a7208625", // Foot X-ray
            "bdd5be47-fc95-4ae3-9319-a8283eeead6a", // Wrist X-ray
            "e4b471d9-81d6-48c4-8b97-20fdeeeac280", // C-Spine X-ray
            "30cdefe7-775b-4302-b165-f2540fa207da", // L-Spine X-ray
            "0c6e5f9a-75cf-444c-9415-6c2ac870392c", // T-Spine X-ray
            "bc52d033-8df5-466b-abf9-e0679b42c043"))); // Sacrum X-ray

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

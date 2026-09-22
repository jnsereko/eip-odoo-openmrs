/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.hl7.fhir.r4.model.ServiceRequest;

/**
 * What counts as a radiology order.
 *
 * <p>Extracted so that everything in THIS repository decides it the same way. It previously lived
 * inline in {@code RadiologyPaymentTaskProcessor}, and adding a second consumer would have made a
 * second copy.
 *
 * <p>This does NOT close issue #304. The Orthanc bridge still decides the same question by text
 * heuristics on the concept code ({@code XR}, {@code ct}, {@code ultrasound}, …) while this list is
 * an allow-list of X-ray concepts, so the two repositories still disagree: 13 ultrasound, CT and
 * echocardiography concepts are in neither payment gating nor worklist creation. The real fix is one
 * shared definition both sides read from the server — a concept set, an order type, or a concept
 * attribute — rather than a list in a jar. Until then, anything added here must be added to the
 * Orthanc bridge by hand.
 */
public final class RadiologyConcepts {

    private RadiologyConcepts() {}

    public static final Set<String> UUIDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
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

    public static boolean isRadiologyOrder(ServiceRequest serviceRequest) {
        if (serviceRequest == null || serviceRequest.getCode() == null) {
            return false;
        }
        return serviceRequest.getCode().getCoding().stream()
                .anyMatch(coding -> UUIDS.contains(coding.getCode()));
    }
}

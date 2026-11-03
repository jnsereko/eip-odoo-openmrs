/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The radiology concept list is configurable and never silently empty (UVL-EMR#253), and includes
 * the EC01/EC02 ultrasounds by default (UVL-EMR#304).
 */
class RadiologyConceptsTest {

    private static final String RX01 = "e3dea2c8-62c6-4487-bdaa-1d009642f7ad";

    private static final String RX02 = "82e7d36c-078d-40c6-9854-92b376099307";

    private static final String EC01 = "8155e2e0-5b62-42bc-b47c-0702aaafe3df";

    private static final String EC02 = "521361cf-ce7d-49a6-9721-8ebad1b76702";

    private static final String OTHER = "11111111-2222-3333-4444-555555555555";

    /** The list both bridges hard-coded before #253. */
    private static final Set<String> HARD_CODED_BEFORE_253 = new LinkedHashSet<>(Arrays.asList(
            "e3dea2c8-62c6-4487-bdaa-1d009642f7ad",
            "82e7d36c-078d-40c6-9854-92b376099307",
            "701257a2-885e-4249-8319-d9597d2970af",
            "b25dcc00-800f-48ac-b31a-f1e9cc53d787",
            "81e0643c-a871-475e-8bd5-93945da8877d",
            "1a5e3d73-f897-47ed-840b-d4537b7cc586",
            "0a5ba175-fb7e-4d66-aa6a-ba058f3468c1",
            "d0b5d4a0-1001-0000-0000-000000000001",
            "d0b5d4a0-1002-0000-0000-000000000001",
            "d0b5d4a0-1003-0000-0000-000000000001",
            "d0b5d4a0-1004-0000-0000-000000000001",
            "d0b5d4a0-1005-0000-0000-000000000001",
            "d0b5d4a0-1006-0000-0000-000000000001",
            "d0b5d4a0-1007-0000-0000-000000000001",
            "d0b5d4a0-1008-0000-0000-000000000001"));

    private static ServiceRequest order(String conceptUuid) {
        ServiceRequest sr = new ServiceRequest();
        sr.getCode().addCoding().setCode(conceptUuid);
        return sr;
    }

    private static final String ANKLE = "addae1d8-baa2-459c-b869-e98f0303a688";

    /** The 22 body-part X-rays of M3 (UVL-EMR#212), also in the Orthanc bridge's default. */
    private static final Set<String> M3_XRAYS = new LinkedHashSet<>(Arrays.asList(
            "dac7eb20-d3af-4097-a399-2b1a9f3e0ae4",
            "e6d7bca3-cb2f-4926-bfbc-f89e90d573cd",
            "97589f4b-8bf9-4342-b24f-605d359ce932",
            "e309ea51-86cd-4a83-883b-d5f8654e6627",
            "addae1d8-baa2-459c-b869-e98f0303a688",
            "245e29a4-82de-4662-b176-92c768e49118",
            "c82d4492-308d-455d-9bf9-360c3a086743",
            "3765eebc-1324-4555-99c8-2b9324d5a185",
            "c9f0148b-362a-4b11-9bc3-8c3428a9bbb4",
            "1bd135e8-d15c-45b4-9bc2-a232f5358912",
            "1407617f-d79a-43a5-868b-7dedcd2c560f",
            "bb1b2daf-584c-4bb6-8de2-aa678c7231fa",
            "ba2a2d62-8220-45a7-9302-9092c48a363d",
            "c95e8fa1-b395-4042-9513-d8f33fb81a05",
            "41d7e98f-d485-4d27-81fc-70d44e9d6e1d",
            "1cb9c9f6-d3ad-42db-b38e-9cefa0b258fe",
            "740f0b00-3c24-4c86-b95b-b5c6a7208625",
            "bdd5be47-fc95-4ae3-9319-a8283eeead6a",
            "e4b471d9-81d6-48c4-8b97-20fdeeeac280",
            "30cdefe7-775b-4302-b165-f2540fa207da",
            "0c6e5f9a-75cf-444c-9415-6c2ac870392c",
            "bc52d033-8df5-466b-abf9-e0679b42c043"));

    /** The default: the list before #253, the EC01/EC02 ultrasounds (#304) and the 22 M3 X-rays (#340). */
    private static final Set<String> DEFAULT_SINCE_304 = new LinkedHashSet<>(HARD_CODED_BEFORE_253);

    static {
        DEFAULT_SINCE_304.add(EC01);
        DEFAULT_SINCE_304.add(EC02);
        DEFAULT_SINCE_304.addAll(M3_XRAYS);
    }

    @Test
    void theM3BodyPartXraysAreRadiologyByDefault() {
        RadiologyConcepts concepts = new RadiologyConcepts();
        assertTrue(concepts.getUuids().containsAll(M3_XRAYS));
        // The ankle X-ray production skipped on 2026-10-27.
        assertTrue(concepts.getUuids().contains(ANKLE));
        // Nothing the 5ec0e3b bridge knew is dropped.
        assertTrue(concepts.getUuids().containsAll(HARD_CODED_BEFORE_253));
    }

    @Test
    void defaultIsTheFifteenConceptsHardCodedBeforeTheTwoUltrasoundsAndTheM3Xrays() {
        assertEquals(39, RadiologyConcepts.DEFAULT_UUIDS.size());
        assertEquals(DEFAULT_SINCE_304, RadiologyConcepts.DEFAULT_UUIDS);
        assertEquals(DEFAULT_SINCE_304, new RadiologyConcepts().getUuids());
        assertEquals(DEFAULT_SINCE_304, new RadiologyConcepts(null).getUuids());
    }

    @Test
    void ultrasoundOrdersAreRadiologyByDefault() {
        RadiologyConcepts concepts = new RadiologyConcepts();

        assertTrue(concepts.isRadiologyOrder(order(EC01)));
        assertTrue(concepts.isRadiologyOrder(order(EC02)));
    }

    @Test
    void configuredListReplacesTheDefault() {
        RadiologyConcepts concepts = new RadiologyConcepts(" " + RX02 + " , " + OTHER + ",");

        assertEquals(new LinkedHashSet<>(Arrays.asList(RX02, OTHER)), concepts.getUuids());
        assertTrue(concepts.isRadiologyOrder(order(OTHER)));
        assertTrue(concepts.isRadiologyOrder(order(RX02)));
        // Replaces, not adds to: RX01 is in the default but not in this list.
        assertFalse(concepts.isRadiologyOrder(order(RX01)));
    }

    @Test
    void blankConfigurationFallsBackToTheDefault() {
        for (String blank : new String[] {"", "   ", ",", " , ,"}) {
            RadiologyConcepts concepts = new RadiologyConcepts(blank);
            assertEquals(DEFAULT_SINCE_304, concepts.getUuids(), "for '" + blank + "'");
            assertTrue(concepts.isRadiologyOrder(order(RX01)));
        }
    }

    @Test
    void configurationWithAnythingThatIsNotAUuidFallsBackToTheWholeDefault() {
        // One typo must not leave a partial list: that would drop the mistyped concept in silence.
        RadiologyConcepts concepts = new RadiologyConcepts(RX02 + ",RX01," + OTHER);

        assertEquals(DEFAULT_SINCE_304, concepts.getUuids());
        assertTrue(concepts.isRadiologyOrder(order(RX01)));
        assertFalse(concepts.isRadiologyOrder(order(OTHER)));
    }

    @Test
    void theListIsNeverEmpty() {
        for (String value : new String[] {null, "", ",", "not-a-uuid", RX01}) {
            assertFalse(new RadiologyConcepts(value).getUuids().isEmpty(), "for '" + value + "'");
        }
    }

    @Test
    void nonRadiologyOrdersAreNotMatched() {
        RadiologyConcepts concepts = new RadiologyConcepts();

        assertFalse(concepts.isRadiologyOrder(null));
        assertFalse(concepts.isRadiologyOrder(new ServiceRequest()));
        assertFalse(concepts.isRadiologyOrder(order(OTHER)));
        assertTrue(concepts.isRadiologyOrder(order(RX01)));
    }

    @Test
    void theEnvironmentVariableRadiologyConceptUuidsResolvesTheProperty() {
        // How the value reaches the container: compose sets RADIOLOGY_CONCEPT_UUIDS, and Spring's
        // environment lookup maps it onto radiology.concept.uuids, which EIPAppConfig reads.
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Collections.<String, Object>singletonMap("RADIOLOGY_CONCEPT_UUIDS", RX02)));

        String resolved = environment.resolvePlaceholders("${" + RadiologyConcepts.PROPERTY + ":}");

        assertEquals(RX02, resolved);
        assertEquals(Collections.singleton(RX02), new RadiologyConcepts(resolved).getUuids());
    }
}

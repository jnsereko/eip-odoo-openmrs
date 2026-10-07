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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The radiology concept list is configurable and never silently empty (UVL-EMR#253), includes the
 * EC01/EC02 ultrasounds by default (UVL-EMR#304), and is read from a shared concept set when one is
 * configured (UVL-EMR#340).
 */
class RadiologyConceptsTest {

    private static final String RX01 = "e3dea2c8-62c6-4487-bdaa-1d009642f7ad";

    private static final String RX02 = "82e7d36c-078d-40c6-9854-92b376099307";

    private static final String EC01 = "8155e2e0-5b62-42bc-b47c-0702aaafe3df";

    private static final String EC02 = "521361cf-ce7d-49a6-9721-8ebad1b76702";

    private static final String OTHER = "11111111-2222-3333-4444-555555555555";

    private static final String SET = "6a1c3f0e-5d2b-4c8e-9f47-1b2e3d4c5a60";

    /** RX01-RX07: the orderable X-ray and CT concepts both bridges hard-coded before #253. */
    private static final Set<String> RX01_TO_RX07 = new LinkedHashSet<>(Arrays.asList(
            "e3dea2c8-62c6-4487-bdaa-1d009642f7ad",
            "82e7d36c-078d-40c6-9854-92b376099307",
            "701257a2-885e-4249-8319-d9597d2970af",
            "b25dcc00-800f-48ac-b31a-f1e9cc53d787",
            "81e0643c-a871-475e-8bd5-93945da8877d",
            "1a5e3d73-f897-47ed-840b-d4537b7cc586",
            "0a5ba175-fb7e-4d66-aa6a-ba058f3468c1"));

    private static ServiceRequest order(String conceptUuid) {
        ServiceRequest sr = new ServiceRequest();
        sr.getCode().addCoding().setCode(conceptUuid);
        return sr;
    }

    /** The default since #340: RX01-RX07 and the EC01/EC02 ultrasounds, without the dead d0b5d4a0 X-rays. */
    private static final Set<String> DEFAULT_SINCE_304 = new LinkedHashSet<>(RX01_TO_RX07);

    static {
        DEFAULT_SINCE_304.add(EC01);
        DEFAULT_SINCE_304.add(EC02);
    }

    @Test
    void defaultIsRx01ToRx07PlusTheTwoUltrasounds() {
        assertEquals(9, RadiologyConcepts.DEFAULT_UUIDS.size());
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

    @Test
    void deadD0b5d4a0XraysAreNotInTheDefault() {
        // They are in no orderable concept set and their order type never becomes a ServiceRequest.
        for (String uuid : RadiologyConcepts.DEFAULT_UUIDS) {
            assertFalse(uuid.startsWith("d0b5d4a0-"), uuid);
        }
    }

    // ---- The shared concept set (UVL-EMR#340) ----

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-07T08:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A set loader that returns, or throws, whatever the test queued, and counts its calls. */
    private static final class FakeSet implements Function<String, Set<String>> {
        private final List<Object> answers = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();

        FakeSet then(Object answer) {
            answers.add(answer);
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<String> apply(String setUuid) {
            calls.add(setUuid);
            Object answer = answers.size() > 1 ? answers.remove(0) : answers.get(0);
            if (answer instanceof RuntimeException) {
                throw (RuntimeException) answer;
            }
            return (Set<String>) answer;
        }
    }

    private static Set<String> setOf(String... uuids) {
        return new LinkedHashSet<>(Arrays.asList(uuids));
    }

    private final TestClock clock = new TestClock();

    private RadiologyConcepts withSet(FakeSet set) {
        return new RadiologyConcepts(null, SET, set, Duration.ofMinutes(10), clock);
    }

    @Test
    void theConceptSetMembersReplaceTheList() {
        FakeSet set = new FakeSet().then(setOf(RX02, OTHER));
        RadiologyConcepts concepts = withSet(set);

        assertEquals(setOf(RX02, OTHER), concepts.getUuids());
        // A concept added to the set counts without a rebuild; one left out of it does not.
        assertTrue(concepts.isRadiologyOrder(order(OTHER)));
        assertFalse(concepts.isRadiologyOrder(order(RX01)));
        assertEquals(Collections.singletonList(SET), set.calls);
    }

    @Test
    void theSetIsCachedAndReadAgainWhenTheCacheExpires() {
        FakeSet set = new FakeSet().then(setOf(RX01)).then(setOf(RX01, OTHER));
        RadiologyConcepts concepts = withSet(set);

        assertEquals(setOf(RX01), concepts.getUuids());
        clock.advance(Duration.ofMinutes(9));
        concepts.isRadiologyOrder(order(RX01));
        assertEquals(setOf(RX01), concepts.getUuids());
        assertEquals(1, set.calls.size(), "read once within the cache period");

        clock.advance(Duration.ofMinutes(2));
        assertEquals(setOf(RX01, OTHER), concepts.getUuids());
        assertEquals(2, set.calls.size());
    }

    @Test
    void anUnreadableSetFallsBackToTheListAndIsRetriedAfterAMinute() {
        FakeSet set = new FakeSet().then(new RuntimeException("HTTP 401")).then(setOf(OTHER));
        RadiologyConcepts concepts = withSet(set);

        assertEquals(DEFAULT_SINCE_304, concepts.getUuids());
        concepts.getUuids();
        assertEquals(1, set.calls.size(), "not retried on every order");

        clock.advance(RadiologyConcepts.RETRY_AFTER_FAILURE);
        assertEquals(setOf(OTHER), concepts.getUuids());
    }

    @Test
    void anUnreadableSetFallsBackToTheConfiguredListWhenThereIsOne() {
        FakeSet set = new FakeSet().then(new RuntimeException("connection refused"));
        RadiologyConcepts concepts = new RadiologyConcepts(RX02, SET, set, Duration.ofMinutes(10), clock);

        assertEquals(Collections.singleton(RX02), concepts.getUuids());
    }

    @Test
    void aFailedReReadKeepsTheMembersReadBefore() {
        FakeSet set = new FakeSet().then(setOf(OTHER)).then(new RuntimeException("timeout"));
        RadiologyConcepts concepts = withSet(set);

        assertEquals(setOf(OTHER), concepts.getUuids());
        clock.advance(Duration.ofMinutes(11));
        assertEquals(setOf(OTHER), concepts.getUuids());
        assertEquals(2, set.calls.size());
    }

    @Test
    void anEmptySetNeverEmptiesTheList() {
        RadiologyConcepts concepts = withSet(new FakeSet().then(Collections.<String>emptySet()));

        assertEquals(DEFAULT_SINCE_304, concepts.getUuids());
        assertTrue(concepts.isRadiologyOrder(order(RX01)));
    }

    @Test
    void aSetUuidThatIsNotAUuidIsIgnored() {
        FakeSet set = new FakeSet().then(setOf(OTHER));
        RadiologyConcepts concepts =
                new RadiologyConcepts(null, "Imaging procedures", set, Duration.ofMinutes(10), clock);

        assertEquals(DEFAULT_SINCE_304, concepts.getUuids());
        assertTrue(set.calls.isEmpty());
    }

    @Test
    void theEnvironmentVariableRadiologyConceptSetUuidResolvesTheSetProperty() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Collections.<String, Object>singletonMap("RADIOLOGY_CONCEPT_SET_UUID", SET)));

        assertEquals(SET, environment.resolvePlaceholders("${" + RadiologyConcepts.SET_PROPERTY + ":}"));
    }
}

/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
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
 * RX01-RX07 X-ray and CT concepts and the EC01/EC02 ultrasound concepts (UVL-EMR#304). The Orthanc
 * bridge carries the same default. The eight d0b5d4a0-1xxx X-ray concepts once listed here are gone
 * (UVL-EMR#340): they are in no orderable concept set, and their "Radiology Order" type never
 * becomes a FHIR ServiceRequest, so no order could ever match them.
 *
 * <p>A configured value that is blank or contains anything that is not a uuid is rejected as a
 * whole, with a WARN, and the default is used instead. It never becomes an empty or partial list:
 * an empty list would quietly drop every radiology order - no Task, no worklist entry, nothing
 * logged above INFO - which is the kind of silent failure this bridge has already had too many of.
 *
 * <p><b>The shared definition (UVL-EMR#340).</b> When {@value #SET_PROPERTY} names a concept set -
 * environment variable {@code RADIOLOGY_CONCEPT_SET_UUID}, the "Imaging procedures" set - the list
 * is that set's members, read from OpenMRS as FHIR {@code ValueSet/{uuid}} and cached for
 * {@value #CACHE_SECONDS_PROPERTY} seconds (default {@value #DEFAULT_CACHE_SECONDS}). The Orthanc
 * bridge reads the same set, so a new imaging concept is added to the set in OpenMRS and both
 * bridges pick it up without a jar rebuild. The uuid list above is then only the fallback: used until
 * the set has been read once, and whenever it cannot be read or comes back empty. A failed read
 * keeps the last good members and is retried after {@link #RETRY_AFTER_FAILURE}; it never empties
 * the list. Members that are themselves sets are not followed.
 *
 * <p>The Orthanc bridge takes the DICOM modality from the concept, so a concept added here also
 * needs a modality there, or it falls back to a guess from the procedure name.
 */
@Slf4j
public class RadiologyConcepts {

    /** Property the list is read from. Spring maps the environment variable RADIOLOGY_CONCEPT_UUIDS onto it. */
    public static final String PROPERTY = "radiology.concept.uuids";

    /** Concept set whose members are the radiology concepts. Environment variable RADIOLOGY_CONCEPT_SET_UUID. */
    public static final String SET_PROPERTY = "radiology.concept.set.uuid";

    /** How long the set's members are cached. Environment variable RADIOLOGY_CONCEPT_SET_CACHE_SECONDS. */
    public static final String CACHE_SECONDS_PROPERTY = "radiology.concept.set.cache.seconds";

    public static final long DEFAULT_CACHE_SECONDS = 600;

    /** After a failed read of the set, wait this long before asking OpenMRS again. */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public static final Set<String> DEFAULT_UUIDS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "e3dea2c8-62c6-4487-bdaa-1d009642f7ad", // RX01 - Chest X-ray
            "82e7d36c-078d-40c6-9854-92b376099307", // RX02 - Abdominal X-ray
            "701257a2-885e-4249-8319-d9597d2970af", // RX03 - Bone X-ray
            "b25dcc00-800f-48ac-b31a-f1e9cc53d787", // RX04 - Intravenous urography
            "81e0643c-a871-475e-8bd5-93945da8877d", // RX05 - Salpingo-urethrogram
            "1a5e3d73-f897-47ed-840b-d4537b7cc586", // RX06 - Barium enema
            "0a5ba175-fb7e-4d66-aa6a-ba058f3468c1", // RX07 - CT scan
            "8155e2e0-5b62-42bc-b47c-0702aaafe3df", // EC01 - Abdominal ultrasound
            "521361cf-ce7d-49a6-9721-8ebad1b76702"))); // EC02 - Gastrointestinal ultrasound

    /** The configured list or the default: the whole answer without a set, the fallback with one. */
    private final Set<String> uuids;

    private final String conceptSetUuid;

    private final Function<String, Set<String>> setMembersLoader;

    private final Duration cacheTtl;

    private final Clock clock;

    private volatile Set<String> setMembers;

    private volatile Instant nextSetRead = Instant.MIN;

    /** The default list, as before this was configurable. */
    public RadiologyConcepts() {
        this(null);
    }

    /**
     * @param configured the raw {@value #PROPERTY} value; null or blank means "use the default"
     */
    public RadiologyConcepts(String configured) {
        this(configured, null, null, Duration.ofSeconds(DEFAULT_CACHE_SECONDS), Clock.systemUTC());
    }

    /**
     * @param configured the raw {@value #PROPERTY} value; null or blank means "use the default"
     * @param conceptSetUuid the raw {@value #SET_PROPERTY} value; null or blank means "no set"
     * @param setMembersLoader reads the members of a concept set, e.g. {@link FhirConceptSetMembers}
     * @param cacheTtl how long a successful read of the set is used before it is read again
     */
    public RadiologyConcepts(
            String configured,
            String conceptSetUuid,
            Function<String, Set<String>> setMembersLoader,
            Duration cacheTtl,
            Clock clock) {
        this.uuids = resolve(configured);
        this.conceptSetUuid = resolveSet(conceptSetUuid, setMembersLoader, uuids.size());
        this.setMembersLoader = setMembersLoader;
        this.cacheTtl = cacheTtl;
        this.clock = clock;
    }

    /** The radiology concept uuids: the concept set's members when one is configured and readable. */
    public Set<String> getUuids() {
        if (conceptSetUuid == null) {
            return uuids;
        }
        if (!clock.instant().isBefore(nextSetRead)) {
            readSet();
        }
        Set<String> members = setMembers;
        return members != null ? members : uuids;
    }

    private synchronized void readSet() {
        Instant now = clock.instant();
        if (now.isBefore(nextSetRead)) {
            return; // another thread read it while this one waited
        }
        try {
            Set<String> loaded = setMembersLoader.apply(conceptSetUuid);
            if (loaded == null || loaded.isEmpty()) {
                throw new IllegalStateException("it has no members");
            }
            if (!loaded.equals(setMembers)) {
                log.info(
                        "{} {} - using its {} members as the radiology concepts: {}",
                        SET_PROPERTY,
                        conceptSetUuid,
                        loaded.size(),
                        loaded);
            }
            setMembers = Collections.unmodifiableSet(new LinkedHashSet<>(loaded));
            nextSetRead = now.plus(cacheTtl);
        } catch (RuntimeException e) {
            nextSetRead = now.plus(RETRY_AFTER_FAILURE);
            Set<String> members = setMembers;
            if (members == null) {
                log.warn(
                        "Could not read the radiology concept set {} ({}) - using the {} {} radiology concepts until it can be read",
                        conceptSetUuid,
                        e.getMessage(),
                        uuids.size(),
                        uuids == DEFAULT_UUIDS ? "default" : "configured");
            } else {
                log.warn(
                        "Could not re-read the radiology concept set {} ({}) - keeping its {} members read before",
                        conceptSetUuid,
                        e.getMessage(),
                        members.size());
            }
        }
    }

    public boolean isRadiologyOrder(ServiceRequest serviceRequest) {
        if (serviceRequest == null || serviceRequest.getCode() == null) {
            return false;
        }
        Set<String> radiology = getUuids();
        return serviceRequest.getCode().getCoding().stream().anyMatch(coding -> radiology.contains(coding.getCode()));
    }

    private static String resolveSet(String conceptSetUuid, Function<String, Set<String>> loader, int fallbackSize) {
        if (conceptSetUuid == null || conceptSetUuid.trim().isEmpty()) {
            return null;
        }
        String uuid = conceptSetUuid.trim();
        if (!UUID.matcher(uuid).matches() || loader == null) {
            log.warn(
                    "{} is invalid ('{}') - IGNORING it and using the list of {} radiology concepts",
                    SET_PROPERTY,
                    conceptSetUuid,
                    fallbackSize);
            return null;
        }
        log.info("{} set - the radiology concepts are the members of concept set {}", SET_PROPERTY, uuid);
        return uuid;
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

/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers.openmrs;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.openmrs.eip.Constants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads inpatient stays from OpenMRS and counts the nights to bill for each (UVL-EMR#246).
 *
 * <p>A stay is what the ward app records as encounters on the visit: an Admission (at the admitting ward), a
 * Transfer (at the destination ward) and a Discharge. A night is a midnight, in {@link #timezone}, between the
 * admission and the end of the stay: the Discharge, else the end of the visit, else now. Each midnight is billed
 * to the room class of the ward the patient was in at that midnight; a stay with fewer than {@link #minNights}
 * midnights is billed that many nights at the class of the admitting ward. The class of a ward is the product
 * mapped to the first configured location tag found on the ward or one of its parents, else the default product.
 *
 * <p>The reads are SQL on the OpenMRS database, with every filter in the WHERE clause. A REST or FHIR search was
 * not used because OpenMRS silently ignores search parameters it does not support (the #322 lesson): an ignored
 * encounter-type filter would bill every encounter as a stay.
 */
@Slf4j
@Setter
@Component
public class InpatientStayHandler {

    static final String ADMISSION_TERM = "emr.admissionEncounterType";

    static final String TRANSFER_TERM = "emr.transferWithinHospitalEncounterType";

    static final String DISCHARGE_TERM = "emr.exitFromInpatientEncounterType";

    static final String TERM_SQL = "SELECT metadata_uuid FROM metadatamapping_metadata_term_mapping"
            + " WHERE code = ? AND retired = 0 AND metadata_uuid IS NOT NULL";

    static final String LOCATIONS_SQL = "SELECT l.uuid AS uuid, p.uuid AS parent_uuid, t.uuid AS tag_uuid,"
            + " t.name AS tag_name FROM location l"
            + " LEFT JOIN location p ON p.location_id = l.parent_location"
            + " LEFT JOIN location_tag_map m ON m.location_id = l.location_id"
            + " LEFT JOIN location_tag t ON t.location_tag_id = m.location_tag_id AND t.retired = 0";

    /**
     * The ADT encounters of every visit that has an Admission and is open or ended since the cut-off. %s are the
     * placeholders of the ADT types and of the admission types.
     */
    static final String ADT_SQL = "SELECT v.uuid AS visit_uuid, v.date_started, v.date_stopped,"
            + " pe.uuid AS patient_uuid, e.uuid AS encounter_uuid, e.encounter_id, e.encounter_datetime,"
            + " et.uuid AS type_uuid, l.uuid AS location_uuid"
            + " FROM visit v"
            + " JOIN person pe ON pe.person_id = v.patient_id"
            + " JOIN encounter e ON e.visit_id = v.visit_id AND e.voided = 0"
            + " JOIN encounter_type et ON et.encounter_type_id = e.encounter_type"
            + " LEFT JOIN location l ON l.location_id = e.location_id"
            + " WHERE v.voided = 0 AND et.uuid IN (%s)"
            + " AND (v.date_stopped IS NULL OR v.date_stopped >= ?)"
            + " AND EXISTS (SELECT 1 FROM encounter a"
            + " JOIN encounter_type aet ON aet.encounter_type_id = a.encounter_type"
            + " WHERE a.visit_id = v.visit_id AND a.voided = 0 AND aet.uuid IN (%s))"
            + " ORDER BY v.visit_id, e.encounter_datetime, e.encounter_id";

    /** One Admission, Transfer or Discharge encounter. */
    public record AdtEvent(String encounterUuid, int encounterId, String typeUuid, Instant at, String locationUuid) {}

    /** A visit with an admission, and the nights to bill per product external id (every configured product). */
    public record InpatientStay(
            String visitUuid, String patientUuid, String admissionEncounterUuid, Map<String, Integer> nightsByProduct) {

        public int totalNights() {
            return nightsByProduct.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** The encounter types that make a stay. */
    public record AdtTypes(Set<String> admission, Set<String> transfer, Set<String> discharge) {

        boolean isAdt(String type) {
            return admission.contains(type) || transfer.contains(type) || discharge.contains(type);
        }
    }

    @Value("${eip.inpatient.stay.timezone:UTC}")
    private String timezone;

    @Value("${eip.inpatient.stay.min.nights:1}")
    private int minNights;

    @Value("${eip.inpatient.stay.lookback.days:7}")
    private int lookbackDays;

    @Value("${eip.inpatient.stay.default.product:}")
    private String defaultProduct;

    /** Comma separated {@code tag=product} pairs; the tag by name or uuid; the first match wins. */
    @Value("${eip.inpatient.stay.location.tag.products:}")
    private String locationTagProducts;

    @Value("${eip.inpatient.stay.admission.encounter.types:}")
    private String admissionEncounterTypes;

    @Value("${eip.inpatient.stay.transfer.encounter.types:}")
    private String transferEncounterTypes;

    @Value("${eip.inpatient.stay.discharge.encounter.types:}")
    private String dischargeEncounterTypes;

    private JdbcTemplate jdbcTemplate;

    @Autowired
    public void setOpenmrsDataSource(@Qualifier(Constants.OPENMRS_DATASOURCE_NAME) DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** Whether a default product is set; without one there is nothing to bill a night as. */
    public boolean isConfigured() {
        return defaultProduct != null && !defaultProduct.isBlank();
    }

    /** Every stay product: the default one, then the mapped ones, without repeats. */
    public Set<String> getProducts() {
        Set<String> products = new LinkedHashSet<>();
        if (isConfigured()) {
            products.add(defaultProduct.trim());
        }
        products.addAll(parseTagProducts(locationTagProducts).values());
        return products;
    }

    /** The stays to bill now: every visit with an admission, open or ended within the look-back window. */
    public List<InpatientStay> getStays(Instant now) {
        AdtTypes types = getAdtTypes();
        if (types.admission().isEmpty()) {
            log.warn(
                    "No admission encounter type is configured or mapped in emrapi ({}): no stay is billed",
                    ADMISSION_TERM);
            return List.of();
        }
        Function<String, String> productOfLocation = productOfLocation(readLocations());
        List<String> adt = new ArrayList<>();
        adt.addAll(types.admission());
        adt.addAll(types.transfer());
        adt.addAll(types.discharge());
        String sql = String.format(
                ADT_SQL,
                placeholders(adt.size()),
                placeholders(types.admission().size()));
        List<Object> args = new ArrayList<>(adt);
        args.add(Timestamp.from(now.minus(Duration.ofDays(lookbackDays))));
        args.addAll(types.admission());

        Map<String, List<AdtEvent>> eventsByVisit = new LinkedHashMap<>();
        Map<String, String> patientByVisit = new HashMap<>();
        Map<String, Instant> stopByVisit = new HashMap<>();
        jdbcTemplate.query(
                sql,
                rs -> {
                    String visit = rs.getString("visit_uuid");
                    patientByVisit.put(visit, rs.getString("patient_uuid"));
                    Timestamp stopped = rs.getTimestamp("date_stopped");
                    stopByVisit.put(visit, stopped == null ? null : stopped.toInstant());
                    eventsByVisit
                            .computeIfAbsent(visit, k -> new ArrayList<>())
                            .add(new AdtEvent(
                                    rs.getString("encounter_uuid"),
                                    rs.getInt("encounter_id"),
                                    rs.getString("type_uuid"),
                                    rs.getTimestamp("encounter_datetime").toInstant(),
                                    rs.getString("location_uuid")));
                },
                args.toArray());

        ZoneId zone = ZoneId.of(timezone);
        Set<String> products = getProducts();
        List<InpatientStay> stays = new ArrayList<>();
        for (Map.Entry<String, List<AdtEvent>> entry : eventsByVisit.entrySet()) {
            List<AdtEvent> events = entry.getValue();
            Map<String, Integer> nights = countNights(
                    events, stopByVisit.get(entry.getKey()), now, zone, minNights, types, productOfLocation, products);
            String admission = events.stream()
                    .filter(e -> types.admission().contains(e.typeUuid()))
                    .map(AdtEvent::encounterUuid)
                    .findFirst()
                    .orElse(null);
            stays.add(new InpatientStay(entry.getKey(), patientByVisit.get(entry.getKey()), admission, nights));
        }
        return stays;
    }

    /**
     * The nights of one visit per product. Pure: everything it needs is passed in.
     *
     * @param events the visit's Admission, Transfer and Discharge encounters, in any order
     * @param visitStop when the visit ended, or null if it is open
     * @param productOfLocation the stay product of a ward
     * @param products every stay product, so that a product with no night is present with 0
     */
    public static Map<String, Integer> countNights(
            List<AdtEvent> events,
            Instant visitStop,
            Instant now,
            ZoneId zone,
            int minNights,
            AdtTypes types,
            Function<String, String> productOfLocation,
            Set<String> products) {
        Map<String, Integer> nights = new LinkedHashMap<>();
        for (String product : products) {
            nights.put(product, 0);
        }
        List<AdtEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(AdtEvent::at).thenComparingInt(AdtEvent::encounterId));
        Instant end = visitStop != null && visitStop.isBefore(now) ? visitStop : now;

        String admittedProduct = null;
        boolean admitted = false;
        Instant from = null;
        String location = null;
        for (AdtEvent event : sorted) {
            String type = event.typeUuid();
            if (types.admission().contains(type)
                    || (admitted && types.transfer().contains(type))) {
                if (admitted) {
                    addMidnights(nights, from, event.at(), zone, productOfLocation.apply(location));
                }
                if (admittedProduct == null) {
                    admittedProduct = productOfLocation.apply(event.locationUuid());
                }
                admitted = true;
                from = event.at();
                location = event.locationUuid();
            } else if (admitted && types.discharge().contains(type)) {
                addMidnights(nights, from, event.at(), zone, productOfLocation.apply(location));
                admitted = false;
            }
        }
        if (admitted) {
            addMidnights(nights, from, end, zone, productOfLocation.apply(location));
        }

        int total = nights.values().stream().mapToInt(Integer::intValue).sum();
        if (admittedProduct != null && total < minNights) {
            nights.merge(admittedProduct, minNights - total, Integer::sum);
        }
        return nights;
    }

    /** Adds to the product every midnight m, in the zone, with from < m <= to. */
    static void addMidnights(Map<String, Integer> nights, Instant from, Instant to, ZoneId zone, String product) {
        if (from == null || to == null || !to.isAfter(from) || product == null) {
            return;
        }
        ZonedDateTime midnight = from.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone);
        int count = 0;
        while (!midnight.toInstant().isAfter(to)) {
            count++;
            midnight = midnight.plusDays(1).toLocalDate().atStartOfDay(zone);
        }
        if (count > 0) {
            nights.merge(product, count, Integer::sum);
        }
    }

    /** One location: its parent and its tags, by name and by uuid. */
    record LocationNode(String parentUuid, Set<String> tags) {}

    Map<String, LocationNode> readLocations() {
        Map<String, String> parents = new HashMap<>();
        Map<String, Set<String>> tags = new HashMap<>();
        jdbcTemplate.query(LOCATIONS_SQL, rs -> {
            String uuid = rs.getString("uuid");
            parents.put(uuid, rs.getString("parent_uuid"));
            Set<String> own = tags.computeIfAbsent(uuid, k -> new HashSet<>());
            if (rs.getString("tag_uuid") != null) {
                own.add(rs.getString("tag_uuid"));
                own.add(rs.getString("tag_name"));
            }
        });
        Map<String, LocationNode> locations = new HashMap<>();
        for (Map.Entry<String, String> entry : parents.entrySet()) {
            locations.put(entry.getKey(), new LocationNode(entry.getValue(), tags.get(entry.getKey())));
        }
        return locations;
    }

    /**
     * The stay product of a ward: the product of the first configured tag on the ward or one of its parents, else
     * the default product. Location uuids are compared without case: OpenMRS stores some upper case.
     */
    Function<String, String> productOfLocation(Map<String, LocationNode> locations) {
        Map<String, String> tagProducts = parseTagProducts(locationTagProducts);
        Map<String, LocationNode> byUuid = new HashMap<>();
        locations.forEach((uuid, node) -> byUuid.put(uuid.toLowerCase(), node));
        String fallback = isConfigured() ? defaultProduct.trim() : null;
        return locationUuid -> {
            Set<String> tags = new HashSet<>();
            Set<String> seen = new HashSet<>();
            String current = locationUuid;
            while (current != null && seen.add(current.toLowerCase())) {
                LocationNode node = byUuid.get(current.toLowerCase());
                if (node == null) {
                    break;
                }
                tags.addAll(node.tags());
                current = node.parentUuid();
            }
            for (Map.Entry<String, String> tagProduct : tagProducts.entrySet()) {
                if (tags.contains(tagProduct.getKey())) {
                    return tagProduct.getValue();
                }
            }
            return fallback;
        };
    }

    AdtTypes getAdtTypes() {
        return new AdtTypes(
                typesOrMapping(admissionEncounterTypes, ADMISSION_TERM),
                typesOrMapping(transferEncounterTypes, TRANSFER_TERM),
                typesOrMapping(dischargeEncounterTypes, DISCHARGE_TERM));
    }

    /** The configured encounter type uuids, or, when none are, the one emrapi maps to the term. */
    private Set<String> typesOrMapping(String configured, String term) {
        Set<String> types = parseList(configured);
        if (!types.isEmpty()) {
            return types;
        }
        return new LinkedHashSet<>(jdbcTemplate.queryForList(TERM_SQL, String.class, term));
    }

    static Map<String, String> parseTagProducts(String value) {
        Map<String, String> tagProducts = new LinkedHashMap<>();
        for (String pair : parseList(value)) {
            int eq = pair.lastIndexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                log.warn("Ignoring eip.inpatient.stay.location.tag.products entry '{}': expected tag=product", pair);
                continue;
            }
            tagProducts.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return tagProducts;
    }

    static Set<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptySet();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}

/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.InpatientStayHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.InpatientStayHandler.AdtEvent;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.InpatientStayHandler.AdtTypes;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.InpatientStayHandler.InpatientStay;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Function;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InpatientStayHandlerTest {

    private static final ZoneId BUJUMBURA = ZoneId.of("Africa/Bujumbura");

    private static final String ADMISSION = "e22e39fd-7db2-45e7-80f1-60fa0d5a4378";

    private static final String TRANSFER = "d3b07384-8d1c-4e6b-9b8e-2f3b8e4a1c9f";

    private static final String DISCHARGE = "181820aa-88c9-479b-9077-af92f5364329";

    private static final AdtTypes TYPES = new AdtTypes(Set.of(ADMISSION), Set.of(TRANSFER), Set.of(DISCHARGE));

    private static final String COMMUNE = "commune-product";

    private static final String VIP = "vip-product";

    private static final Set<String> PRODUCTS = new LinkedHashSet<>(List.of(COMMUNE, VIP));

    private static final String WARD = "ward";

    private static final String PRIVATE_ROOM = "private-room";

    private static final Function<String, String> CLASS_OF = location -> PRIVATE_ROOM.equals(location) ? VIP : COMMUNE;

    private int nextId = 1;

    /** Bujumbura is UTC+2: 22:00 UTC is midnight. */
    private static Instant utc(String isoDateTime) {
        return Instant.parse(isoDateTime + "Z");
    }

    private AdtEvent event(String type, String at, String location) {
        return new AdtEvent("enc-" + nextId, nextId++, type, utc(at), location);
    }

    private Map<String, Integer> count(List<AdtEvent> events, String visitStop, String now) {
        return InpatientStayHandler.countNights(
                events, visitStop == null ? null : utc(visitStop), utc(now), BUJUMBURA, 1, TYPES, CLASS_OF, PRODUCTS);
    }

    @Test
    void sameDayStay_isOneNight() {
        Map<String, Integer> nights = count(
                List.of(event(ADMISSION, "2026-11-05T08:00:00", WARD), event(DISCHARGE, "2026-11-05T15:00:00", WARD)),
                null,
                "2026-11-07T10:00:00");

        assertEquals(Map.of(COMMUNE, 1, VIP, 0), nights);
    }

    @Test
    void eachMidnightInBujumbura_isOneNight() {
        // Admitted 5 Nov 10:00 CAT, discharged 8 Nov 09:00 CAT: midnights of the 6th, 7th and 8th
        Map<String, Integer> nights = count(
                List.of(event(ADMISSION, "2026-11-05T08:00:00", WARD), event(DISCHARGE, "2026-11-08T07:00:00", WARD)),
                null,
                "2026-11-09T10:00:00");

        assertEquals(3, nights.get(COMMUNE));
    }

    @Test
    void midnightIsLocal_notUtc() {
        // 21:30 UTC is 23:30 CAT and 22:30 UTC is 00:30 CAT: one midnight in Bujumbura, none in UTC
        Map<String, Integer> nights = count(
                List.of(event(ADMISSION, "2026-11-05T21:30:00", WARD), event(DISCHARGE, "2026-11-05T22:30:00", WARD)),
                null,
                "2026-11-07T10:00:00");

        assertEquals(1, nights.get(COMMUNE));
        Map<String, Integer> utcNights = InpatientStayHandler.countNights(
                List.of(event(ADMISSION, "2026-11-05T08:00:00", WARD), event(DISCHARGE, "2026-11-05T23:30:00", WARD)),
                null,
                utc("2026-11-07T10:00:00"),
                ZoneId.of("UTC"),
                1,
                TYPES,
                CLASS_OF,
                PRODUCTS);
        assertEquals(1, utcNights.get(COMMUNE), "no UTC midnight, so the minimum night");
    }

    @Test
    void openStay_countsMidnightsUntilNow() {
        Map<String, Integer> nights =
                count(List.of(event(ADMISSION, "2026-11-05T08:00:00", WARD)), null, "2026-11-07T22:30:00");

        // midnights of the 6th, 7th and 8th (22:00 UTC on the 7th)
        assertEquals(3, nights.get(COMMUNE));
    }

    @Test
    void eachMidnight_isBilledToTheRoomOccupiedAtThatMidnight() {
        // Commune for the midnights of the 6th and 7th, private room from the 7th at noon: the 8th and 9th
        Map<String, Integer> nights = count(
                List.of(
                        event(ADMISSION, "2026-11-05T08:00:00", WARD),
                        event(TRANSFER, "2026-11-07T10:00:00", PRIVATE_ROOM),
                        event(DISCHARGE, "2026-11-09T08:00:00", PRIVATE_ROOM)),
                null,
                "2026-11-10T10:00:00");

        assertEquals(Map.of(COMMUNE, 2, VIP, 2), nights);
    }

    @Test
    void discharge_freezesTheCount() {
        List<AdtEvent> events =
                List.of(event(ADMISSION, "2026-11-05T08:00:00", WARD), event(DISCHARGE, "2026-11-06T08:00:00", WARD));

        assertEquals(1, count(events, null, "2026-11-06T09:00:00").get(COMMUNE));
        assertEquals(1, count(events, null, "2026-11-20T09:00:00").get(COMMUNE));
    }

    @Test
    void endedVisitWithoutDischarge_endsAtTheVisitEnd() {
        Map<String, Integer> nights = count(
                List.of(event(ADMISSION, "2026-11-05T08:00:00", WARD)), "2026-11-06T21:59:59", "2026-11-20T09:00:00");

        assertEquals(1, nights.get(COMMUNE));
    }

    @Test
    void sameDayStayInAPrivateRoom_isOneVipNight() {
        Map<String, Integer> nights = count(
                List.of(
                        event(ADMISSION, "2026-11-05T08:00:00", PRIVATE_ROOM),
                        event(DISCHARGE, "2026-11-05T12:00:00", PRIVATE_ROOM)),
                null,
                "2026-11-07T10:00:00");

        assertEquals(Map.of(COMMUNE, 0, VIP, 1), nights);
    }

    @Test
    void readmissionInTheSameVisit_addsItsNights() {
        Map<String, Integer> nights = count(
                List.of(
                        event(ADMISSION, "2026-11-05T08:00:00", WARD),
                        event(DISCHARGE, "2026-11-06T08:00:00", WARD),
                        event(ADMISSION, "2026-11-06T18:00:00", PRIVATE_ROOM),
                        event(DISCHARGE, "2026-11-07T08:00:00", PRIVATE_ROOM)),
                null,
                "2026-11-10T10:00:00");

        assertEquals(Map.of(COMMUNE, 1, VIP, 1), nights);
    }

    @Test
    void eventsOutOfOrder_areSortedByTime() {
        List<AdtEvent> events = new ArrayList<>();
        events.add(event(DISCHARGE, "2026-11-08T07:00:00", WARD));
        events.add(event(ADMISSION, "2026-11-05T08:00:00", WARD));

        assertEquals(3, count(events, null, "2026-11-09T10:00:00").get(COMMUNE));
    }

    // ---- SQL, against the OpenMRS columns it reads -------------------------------------------------------------

    private JdbcTemplate jdbc;

    private InpatientStayHandler handler;

    private TimeZone defaultZone;

    @BeforeEach
    void setUpDatabase() {
        // The containers run on UTC, and OpenMRS stores datetimes in its JVM's zone: read them the same way.
        defaultZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:stay;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE person (person_id INT PRIMARY KEY, uuid VARCHAR(38))");
        jdbc.execute("CREATE TABLE visit (visit_id INT PRIMARY KEY, uuid VARCHAR(38), patient_id INT,"
                + " date_started TIMESTAMP, date_stopped TIMESTAMP, voided TINYINT)");
        jdbc.execute("CREATE TABLE encounter_type (encounter_type_id INT PRIMARY KEY, uuid VARCHAR(38))");
        jdbc.execute("CREATE TABLE encounter (encounter_id INT PRIMARY KEY, uuid VARCHAR(38), visit_id INT,"
                + " encounter_type INT, encounter_datetime TIMESTAMP, location_id INT, voided TINYINT)");
        jdbc.execute("CREATE TABLE location (location_id INT PRIMARY KEY, uuid VARCHAR(38), parent_location INT)");
        jdbc.execute("CREATE TABLE location_tag (location_tag_id INT PRIMARY KEY, uuid VARCHAR(38),"
                + " name VARCHAR(255), retired TINYINT)");
        jdbc.execute("CREATE TABLE location_tag_map (location_id INT, location_tag_id INT)");
        jdbc.execute("CREATE TABLE metadatamapping_metadata_term_mapping (id INT PRIMARY KEY, code VARCHAR(255),"
                + " metadata_uuid VARCHAR(38), retired TINYINT)");
        jdbc.update(
                "INSERT INTO metadatamapping_metadata_term_mapping VALUES"
                        + " (1, 'emr.admissionEncounterType', ?, 0), (2, 'emr.transferWithinHospitalEncounterType', ?, 0),"
                        + " (3, 'emr.exitFromInpatientEncounterType', ?, 0)",
                ADMISSION,
                TRANSFER,
                DISCHARGE);
        jdbc.update(
                "INSERT INTO encounter_type VALUES (1, ?), (2, ?), (3, ?), (4, 'consultation')",
                ADMISSION,
                TRANSFER,
                DISCHARGE);
        // IPD parent, a ward under it, and a private room under the ward tagged by name
        jdbc.update("INSERT INTO location VALUES (10, 'D6701EED-IPD', NULL), (11, 'ward-uuid', 10),"
                + " (12, 'private-uuid', 11)");
        jdbc.update("INSERT INTO location_tag VALUES (1, 'tag-private-uuid', 'Chambre privée', 0),"
                + " (2, 'tag-admission', 'Admission Location', 0)");
        jdbc.update("INSERT INTO location_tag_map VALUES (12, 1), (10, 2), (11, 2)");
        jdbc.update("INSERT INTO person VALUES (100, 'patient-a'), (101, 'patient-b'), (102, 'patient-c')");

        handler = new InpatientStayHandler();
        handler.setOpenmrsDataSource(dataSource);
        handler.setTimezone("Africa/Bujumbura");
        handler.setMinNights(1);
        handler.setLookbackDays(7);
        handler.setDefaultProduct(COMMUNE);
        handler.setLocationTagProducts("Chambre privée=" + VIP);
        handler.setAdmissionEncounterTypes("");
        handler.setTransferEncounterTypes("");
        handler.setDischargeEncounterTypes("");
    }

    @AfterEach
    void dropDatabase() {
        jdbc.execute("DROP ALL OBJECTS");
        TimeZone.setDefault(defaultZone);
    }

    @Test
    void getStays_readsAdmittedVisitsOnly_withTheirRoomClass() {
        // A: open, admitted at the IPD parent (no class tag: Commune), transferred to the private room
        jdbc.update("INSERT INTO visit VALUES (1, 'visit-a', 100, '2026-11-05 07:00:00', NULL, 0)");
        jdbc.update("INSERT INTO encounter VALUES (1, 'a-adm', 1, 1, '2026-11-05 08:00:00', 10, 0),"
                + " (2, 'a-tr', 1, 2, '2026-11-06 10:00:00', 12, 0),"
                + " (3, 'a-consult', 1, 4, '2026-11-06 11:00:00', 11, 0)");
        // B: no admission, only a consultation
        jdbc.update("INSERT INTO visit VALUES (2, 'visit-b', 101, '2026-11-05 07:00:00', NULL, 0)");
        jdbc.update("INSERT INTO encounter VALUES (4, 'b-consult', 2, 4, '2026-11-05 08:00:00', 11, 0)");
        // C: admission voided
        jdbc.update("INSERT INTO visit VALUES (3, 'visit-c', 102, '2026-11-05 07:00:00', NULL, 0)");
        jdbc.update("INSERT INTO encounter VALUES (5, 'c-adm', 3, 1, '2026-11-05 08:00:00', 11, 1)");
        // D: admitted, but the visit ended a month ago
        jdbc.update("INSERT INTO visit VALUES (4, 'visit-d', 102, '2026-10-01 07:00:00', '2026-10-03 07:00:00', 0)");
        jdbc.update("INSERT INTO encounter VALUES (6, 'd-adm', 4, 1, '2026-10-01 08:00:00', 11, 0)");

        List<InpatientStay> stays = handler.getStays(utc("2026-11-07T23:00:00"));

        assertEquals(1, stays.size());
        InpatientStay stay = stays.get(0);
        assertEquals("visit-a", stay.visitUuid());
        assertEquals("patient-a", stay.patientUuid());
        assertEquals("a-adm", stay.admissionEncounterUuid());
        // midnight of the 6th in Commune; 7th and 8th in the private room
        assertEquals(Map.of(COMMUNE, 1, VIP, 2), stay.nightsByProduct());
    }

    @Test
    void privateRoomTag_isFoundOnAParent_byNameOrUuid() {
        jdbc.update("INSERT INTO location VALUES (13, 'bed-uuid', 12)");
        handler.setLocationTagProducts("tag-private-uuid=" + VIP);
        jdbc.update("INSERT INTO visit VALUES (1, 'visit-a', 100, '2026-11-05 07:00:00', NULL, 0)");
        jdbc.update("INSERT INTO encounter VALUES (1, 'a-adm', 1, 1, '2026-11-05 08:00:00', 13, 0)");

        List<InpatientStay> stays = handler.getStays(utc("2026-11-05T12:00:00"));

        assertEquals(Map.of(COMMUNE, 0, VIP, 1), stays.get(0).nightsByProduct());
    }

    @Test
    void noMappedAdmissionType_billsNothing() {
        jdbc.update("DELETE FROM metadatamapping_metadata_term_mapping");
        jdbc.update("INSERT INTO visit VALUES (1, 'visit-a', 100, '2026-11-05 07:00:00', NULL, 0)");
        jdbc.update("INSERT INTO encounter VALUES (1, 'a-adm', 1, 1, '2026-11-05 08:00:00', 11, 0)");

        assertTrue(handler.getStays(utc("2026-11-07T23:00:00")).isEmpty());
    }

    @Test
    void configuredEncounterTypes_overrideTheEmrapiMapping() {
        jdbc.update("DELETE FROM metadatamapping_metadata_term_mapping");
        handler.setAdmissionEncounterTypes(ADMISSION);
        handler.setDischargeEncounterTypes(DISCHARGE);
        jdbc.update("INSERT INTO visit VALUES (1, 'visit-a', 100, '2026-11-05 07:00:00', NULL, 0)");
        jdbc.update("INSERT INTO encounter VALUES (1, 'a-adm', 1, 1, '2026-11-05 08:00:00', 11, 0)");

        assertEquals(1, handler.getStays(utc("2026-11-05T12:00:00")).size());
    }
}

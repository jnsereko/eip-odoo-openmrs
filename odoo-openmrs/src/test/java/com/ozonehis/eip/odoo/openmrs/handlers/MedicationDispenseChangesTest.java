/*
 * Copyright © 2026, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.MedicationDispenseHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.MedicationDispenseHandler.DispenseChange;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs the dispense-change query (UVL-EMR#605) against the two OpenMRS tables it reads, in H2. */
class MedicationDispenseChangesTest {

    private static final Instant T0 = Instant.parse("2026-11-11T15:00:00Z");

    private JdbcTemplate jdbc;

    private MedicationDispenseHandler medicationDispenseHandler;

    @BeforeEach
    void setup() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:dispense;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE orders (order_id INT PRIMARY KEY, uuid VARCHAR(38), voided TINYINT)");
        jdbc.execute("CREATE TABLE medication_dispense (medication_dispense_id INT PRIMARY KEY, uuid VARCHAR(38),"
                + " drug_order_id INT, date_created DATETIME, date_changed DATETIME, date_voided DATETIME)");
        jdbc.update("INSERT INTO orders VALUES (1, 'rx-1', 0), (2, 'rx-2', 0), (3, 'rx-voided', 1)");
        medicationDispenseHandler = new MedicationDispenseHandler();
        medicationDispenseHandler.setOpenmrsDataSource(dataSource);
    }

    private void dispense(int id, Integer orderId, Instant created, Instant changed, Instant voided) {
        jdbc.update(
                "INSERT INTO medication_dispense VALUES (?, ?, ?, ?, ?, ?)",
                id,
                "md-" + id,
                orderId,
                Timestamp.from(created),
                changed == null ? null : Timestamp.from(changed),
                voided == null ? null : Timestamp.from(voided));
    }

    @Test
    void shouldReadDispensesCreatedEditedOrDeletedSinceTheGivenTime() {
        dispense(1, 1, T0.minusSeconds(3600), null, null); // old, untouched
        dispense(2, 1, T0.plusSeconds(10), null, null); // partial dispense
        dispense(3, 2, T0.minusSeconds(3600), T0.plusSeconds(20), null); // edited
        dispense(4, 2, T0.minusSeconds(3600), null, T0.plusSeconds(30)); // deleted

        List<DispenseChange> changes = medicationDispenseHandler.getDispensesChangedSince(T0);

        assertEquals(
                List.of(
                        new DispenseChange("md-2", "rx-1", T0.plusSeconds(10)),
                        new DispenseChange("md-3", "rx-2", T0.plusSeconds(20)),
                        new DispenseChange("md-4", "rx-2", T0.plusSeconds(30))),
                changes);
    }

    @Test
    void shouldSkipDispensesWithoutAPrescriptionOrOfAVoidedOne() {
        dispense(1, null, T0.plusSeconds(10), null, null);
        dispense(2, 3, T0.plusSeconds(10), null, null);

        assertEquals(List.of(), medicationDispenseHandler.getDispensesChangedSince(T0));
    }
}

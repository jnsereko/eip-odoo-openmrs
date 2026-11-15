/*
 * Copyright © 2026, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers.openmrs;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.MedicationDispense;
import org.openmrs.eip.Constants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Setter
@Component
public class MedicationDispenseHandler {

    // The dispenses of a prescription created, edited or deleted (voided) since a time, with the prescription. A
    // dispense with no prescription is not billed, nor is one of a voided prescription (its void removed the line).
    static final String CHANGED_DISPENSES_SQL = "SELECT md.uuid AS dispense_uuid, o.uuid AS order_uuid,"
            + " md.date_created, md.date_changed, md.date_voided"
            + " FROM medication_dispense md JOIN orders o ON o.order_id = md.drug_order_id"
            + " WHERE o.voided = 0 AND (md.date_created >= ? OR md.date_changed >= ? OR md.date_voided >= ?)"
            + " ORDER BY md.medication_dispense_id";

    @Autowired
    private IGenericClient openmrsFhirClient;

    private JdbcTemplate jdbcTemplate;

    @Autowired
    public void setOpenmrsDataSource(@Qualifier(Constants.OPENMRS_DATASOURCE_NAME) DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * A change to a dispense of a prescription, as {@link #getDispensesChangedSince} reads it.
     *
     * @param changed when it was created, last edited or deleted, whichever is latest, on the database's clock
     */
    public record DispenseChange(String dispenseUuid, String orderUuid, Instant changed) {}

    /** The database's clock, which the dispenses' dates are on. */
    public Instant getDatabaseNow() {
        return jdbcTemplate.queryForObject("SELECT NOW()", Timestamp.class).toInstant();
    }

    /**
     * The dispenses of a prescription created, edited or deleted at or after the given time (UVL-EMR#605). Read
     * from the database: the dispensing app changes the prescription only when it is dispensed in full, paused or
     * closed, so no prescription event follows a partial dispense, and the watcher does not watch dispenses.
     */
    public List<DispenseChange> getDispensesChangedSince(Instant since) {
        Timestamp from = Timestamp.from(since);
        return jdbcTemplate.query(
                CHANGED_DISPENSES_SQL,
                (rs, rowNum) -> {
                    Instant changed = rs.getTimestamp("date_created").toInstant();
                    for (String column : List.of("date_changed", "date_voided")) {
                        Timestamp date = rs.getTimestamp(column);
                        if (date != null && date.toInstant().isAfter(changed)) {
                            changed = date.toInstant();
                        }
                    }
                    return new DispenseChange(rs.getString("dispense_uuid"), rs.getString("order_uuid"), changed);
                },
                from,
                from,
                from);
    }

    /**
     * Whether anything was actually dispensed against the given MedicationRequest, i.e. it has a
     * MedicationDispense in status completed or in-progress. Pausing (on-hold) and closing (declined) a
     * prescription in the dispensing app also create MedicationDispense records, so those do not count.
     *
     * <p>The authorizingPrescription link is re-checked here rather than left to the server, because OpenMRS
     * silently ignores search parameters it does not support and would then return every dispense.
     */
    public boolean hasDispensedQuantity(String medicationRequestId) {
        return getDispenses(medicationRequestId).findAny().isPresent();
    }

    /**
     * The quantity dispensed against the given MedicationRequest in the given unit (UVL-EMR#544): the sum of its
     * completed and in-progress MedicationDispenses whose quantity is in that unit (a concept uuid). A dispense
     * without a unit counts as in it.
     *
     * @return the quantity, 0 when nothing was dispensed in that unit
     */
    public float getDispensedQuantity(String medicationRequestId, String unit) {
        return (float) getDispenses(medicationRequestId)
                .map(MedicationDispense::getQuantity)
                .filter(quantity ->
                        quantity.hasValue() && (unit == null || !quantity.hasCode() || unit.equals(quantity.getCode())))
                .mapToDouble(quantity -> quantity.getValue().doubleValue())
                .sum();
    }

    // The completed and in-progress dispenses of the MedicationRequest, the ones that actually gave out the drug
    private Stream<MedicationDispense> getDispenses(String medicationRequestId) {
        Bundle bundle = openmrsFhirClient
                .search()
                .forResource(MedicationDispense.class)
                .where(MedicationDispense.PRESCRIPTION.hasId("MedicationRequest/" + medicationRequestId))
                .returnBundle(Bundle.class)
                .execute();

        return bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(MedicationDispense.class::isInstance)
                .map(MedicationDispense.class::cast)
                .filter(dispense -> isAuthorizedBy(dispense, medicationRequestId))
                .filter(dispense -> dispense.getStatus() == MedicationDispense.MedicationDispenseStatus.COMPLETED
                        || dispense.getStatus() == MedicationDispense.MedicationDispenseStatus.INPROGRESS);
    }

    private boolean isAuthorizedBy(MedicationDispense dispense, String medicationRequestId) {
        return dispense.getAuthorizingPrescription().stream()
                .map(org.hl7.fhir.r4.model.Reference::getReference)
                .filter(reference -> reference != null && !reference.isEmpty())
                .anyMatch(reference -> medicationRequestId.equals(reference.substring(reference.lastIndexOf('/') + 1)));
    }
}

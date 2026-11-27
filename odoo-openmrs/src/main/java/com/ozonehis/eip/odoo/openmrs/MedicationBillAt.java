/*
 * Copyright © 2026, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs;

/**
 * When a prescribed medicine is put on the visit's quotation ({@code eip.medication.bill.at}, UVL-EMR#973).
 */
public enum MedicationBillAt {

    /**
     * When it is prescribed, with the prescribed quantity, so that the patient pays before the pharmacy dispenses.
     * A dispense does not change the bill.
     */
    PRESCRIPTION,

    /** Once dispensed, with the quantity dispensed (UVL-EMR#605). */
    DISPENSE;

    /**
     * Reads the property: {@code prescription} or {@code dispense}, in any case; empty or unset is {@code dispense}.
     *
     * @throws IllegalArgumentException for any other value, so that a misspelt setting stops the bridge instead of
     *     billing the other way
     */
    public static MedicationBillAt fromProperty(String value) {
        if (value == null || value.isBlank()) {
            return DISPENSE;
        }
        for (MedicationBillAt billAt : values()) {
            if (billAt.name().equalsIgnoreCase(value.trim())) {
                return billAt;
            }
        }
        throw new IllegalArgumentException(
                "eip.medication.bill.at must be 'prescription' or 'dispense', not '" + value + "'");
    }
}

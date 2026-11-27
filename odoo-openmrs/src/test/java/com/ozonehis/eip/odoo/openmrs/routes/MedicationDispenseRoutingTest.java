/*
 * Copyright © 2026, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MedicationDispenseRoutingTest {

    // Billed at dispense (the default), the schedule that bills partial and edited dispenses runs (UVL-EMR#605)
    @Test
    void shouldStartTheDispenseScheduleWhenMedicinesAreBilledAtDispense() throws Exception {
        assertEquals(1, configuredRoutes(true, "dispense"));
        assertEquals(1, configuredRoutes(true, null));
    }

    // UVL-EMR #973: billed at prescription, a dispense does not change the bill, so the schedule is not started and no
    // prescription can be given up as BILLING_DROPPED (#939)
    @Test
    void shouldNotStartTheDispenseScheduleWhenMedicinesAreBilledAtPrescription() throws Exception {
        assertEquals(0, configuredRoutes(true, "prescription"));
    }

    @Test
    void shouldNotStartTheDispenseScheduleWhenItIsDisabled() throws Exception {
        assertEquals(0, configuredRoutes(false, "dispense"));
    }

    // A misspelt setting stops the bridge instead of billing the other way
    @Test
    void shouldRefuseAnUnknownBillingPoint() {
        assertThrows(IllegalArgumentException.class, () -> configuredRoutes(true, "pharmacy"));
        assertThrows(IllegalArgumentException.class, () -> configuredRoutes(false, "pharmacy"));
    }

    private static int configuredRoutes(boolean enabled, String billAt) throws Exception {
        MedicationDispenseRouting routing = new MedicationDispenseRouting();
        routing.setEnabled(enabled);
        routing.setBillAt(billAt);
        routing.configure();
        return routing.getRouteCollection().getRoutes().size();
    }
}

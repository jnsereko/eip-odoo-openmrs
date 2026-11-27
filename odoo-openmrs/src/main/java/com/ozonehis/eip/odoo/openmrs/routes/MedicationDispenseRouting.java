/*
 * Copyright © 2026, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.routes;

import com.ozonehis.eip.odoo.openmrs.MedicationBillAt;
import com.ozonehis.eip.odoo.openmrs.processors.MedicationDispenseProcessor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bills a partial, top-up, edited or deleted dispense on the visit's quotation (UVL-EMR#605). No event carries
 * those, so the dispenses are read on a schedule.
 *
 * <p>Not started when medicines are billed at prescription ({@code eip.medication.bill.at=prescription},
 * UVL-EMR#973): a dispense then does not change the bill, so there is nothing to read, and no prescription can be
 * given up as BILLING_DROPPED (UVL-EMR#939).
 */
@Slf4j
@Setter
@Component
public class MedicationDispenseRouting extends RouteBuilder {

    private static final String SCHEDULE = "scheduler:medication-dispense"
            + "?initialDelay={{eip.medication.dispense.initial.delay:60000}}"
            + "&delay={{eip.medication.dispense.delay:30000}}";

    @Value("${eip.medication.dispense.enabled:true}")
    private boolean enabled;

    @Value("${eip.medication.bill.at:dispense}")
    private String billAt;

    @Autowired
    private MedicationDispenseProcessor medicationDispenseProcessor;

    @Override
    public void configure() {
        // Read first, so that a misspelt value stops the bridge whether or not the schedule is on
        MedicationBillAt medicationBillAt = MedicationBillAt.fromProperty(billAt);
        if (!enabled) {
            return;
        }
        if (medicationBillAt == MedicationBillAt.PRESCRIPTION) {
            log.info("Medicines are billed at prescription (eip.medication.bill.at): the dispense schedule is not"
                    + " started");
            return;
        }
        from(SCHEDULE)
                .routeId("medication-dispense")
                .log(LoggingLevel.DEBUG, "Billing medication dispenses...")
                .process(medicationDispenseProcessor)
                .end();
    }
}

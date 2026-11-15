/*
 * Copyright © 2026, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.routes;

import com.ozonehis.eip.odoo.openmrs.processors.MedicationDispenseProcessor;
import lombok.Setter;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bills a partial, top-up, edited or deleted dispense on the visit's quotation (UVL-EMR#605). No event carries
 * those, so the dispenses are read on a schedule.
 */
@Setter
@Component
public class MedicationDispenseRouting extends RouteBuilder {

    private static final String SCHEDULE = "scheduler:medication-dispense"
            + "?initialDelay={{eip.medication.dispense.initial.delay:60000}}"
            + "&delay={{eip.medication.dispense.delay:30000}}";

    @Value("${eip.medication.dispense.enabled:true}")
    private boolean enabled;

    @Autowired
    private MedicationDispenseProcessor medicationDispenseProcessor;

    @Override
    public void configure() {
        if (!enabled) {
            return;
        }
        from(SCHEDULE)
                .routeId("medication-dispense")
                .log(LoggingLevel.DEBUG, "Billing medication dispenses...")
                .process(medicationDispenseProcessor)
                .end();
    }
}

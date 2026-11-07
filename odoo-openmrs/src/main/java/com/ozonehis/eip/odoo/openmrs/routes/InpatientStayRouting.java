/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.routes;

import com.ozonehis.eip.odoo.openmrs.processors.InpatientStayProcessor;
import lombok.Setter;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Recounts and bills inpatient stays on a schedule (UVL-EMR#246), hourly by default: a night is billed within
 * the hour after midnight, and a discharge's final count within the hour after it. Off unless
 * {@code eip.inpatient.stay.enabled=true}.
 */
@Setter
@Component
public class InpatientStayRouting extends RouteBuilder {

    private static final String SCHEDULE = "scheduler:inpatient-stay"
            + "?initialDelay={{eip.inpatient.stay.initial.delay:180000}}&delay={{eip.inpatient.stay.delay:3600000}}";

    @Value("${eip.inpatient.stay.enabled:false}")
    private boolean enabled;

    @Autowired
    private InpatientStayProcessor inpatientStayProcessor;

    @Override
    public void configure() {
        if (!enabled) {
            return;
        }
        from(SCHEDULE)
                .routeId("inpatient-stay")
                .log(LoggingLevel.INFO, "Billing inpatient stays...")
                .process(inpatientStayProcessor)
                .end();
    }
}

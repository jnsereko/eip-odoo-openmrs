/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.routes;

import com.ozonehis.eip.odoo.openmrs.processors.UnpaidImagingAuditProcessor;
import lombok.Setter;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Setter
@Component
public class UnpaidImagingAuditRouting extends RouteBuilder {

    /**
     * Hourly, not every 30 seconds like the payment poll. Nothing acts on this: it reports exams that
     * have already happened, so finding one a few minutes sooner changes nothing, and the same order
     * would otherwise be re-reported 120 times an hour for the whole seven-day window.
     */
    private static final String SCHEDULE = "scheduler:unpaid-imaging-audit?initialDelay=120000&delay=3600000";

    @Autowired
    private UnpaidImagingAuditProcessor unpaidImagingAuditProcessor;

    @Override
    public void configure() {
        from(SCHEDULE)
                .routeId("unpaid-imaging-audit")
                .log(LoggingLevel.INFO, "Auditing completed radiology exams for confirmed payment...")
                .process(unpaidImagingAuditProcessor)
                .end();
    }
}

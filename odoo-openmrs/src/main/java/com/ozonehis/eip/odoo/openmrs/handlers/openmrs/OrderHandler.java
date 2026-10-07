/*
 * Copyright © 2026, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers.openmrs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozonehis.eip.odoo.openmrs.client.OpenmrsRestClient;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reads order state that the FHIR ServiceRequest does not carry.
 *
 * <p>FHIR2 reports a ServiceRequest as COMPLETED as soon as the order has a date_stopped, so an order the
 * doctor discontinued before anything was done reads exactly like one whose result was entered. Only the
 * order's fulfiller status tells them apart, and FHIR2 does not expose it on ServiceRequest, so it is read
 * from the REST order resource.
 */
@Slf4j
@Setter
@Component
public class OrderHandler {

    public static final String FULFILLER_STATUS_COMPLETED = "COMPLETED";

    private static final String ORDER_RESOURCE = "order";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private OpenmrsRestClient openmrsRestClient;

    /**
     * Returns the order's fulfiller status (e.g. RECEIVED, IN_PROGRESS, COMPLETED, DECLINED), or null when
     * the order has none or does not exist.
     */
    public String getFulfillerStatus(String orderUuid) throws Exception {
        byte[] order = openmrsRestClient.get(ORDER_RESOURCE, orderUuid);
        if (order == null) {
            log.warn("Order {} not found in OpenMRS", orderUuid);
            return null;
        }
        JsonNode fulfillerStatus = MAPPER.readTree(order).get("fulfillerStatus");
        if (fulfillerStatus == null || fulfillerStatus.isNull()) {
            return null;
        }
        return fulfillerStatus.asText();
    }

    /**
     * Whether the order was carried out: its fulfiller status is COMPLETED (the lab app sets it when the
     * result is entered).
     */
    public boolean isFulfilled(String orderUuid) throws Exception {
        return FULFILLER_STATUS_COMPLETED.equals(getFulfillerStatus(orderUuid));
    }
}

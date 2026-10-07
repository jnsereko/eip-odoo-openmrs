/*
 * Copyright © 2026, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.ozonehis.eip.odoo.openmrs.client.OpenmrsRestClient;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.OrderHandler;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OrderHandlerTest {

    private static final String ORDER_UUID = "7d3a1f0e-6b1c-4c2e-9a3f-1e2d3c4b5a69";

    private OpenmrsRestClient openmrsRestClient;

    private OrderHandler orderHandler;

    @BeforeEach
    void setup() {
        openmrsRestClient = Mockito.mock(OpenmrsRestClient.class);
        orderHandler = new OrderHandler();
        orderHandler.setOpenmrsRestClient(openmrsRestClient);
    }

    @Test
    void shouldTreatACompletedFulfillerStatusAsFulfilled() throws Exception {
        when(openmrsRestClient.get("order", ORDER_UUID)).thenReturn(order("\"COMPLETED\""));

        assertEquals("COMPLETED", orderHandler.getFulfillerStatus(ORDER_UUID));
        assertTrue(orderHandler.isFulfilled(ORDER_UUID));
    }

    @Test
    void shouldNotTreatADiscontinuedOrderWithoutFulfillerStatusAsFulfilled() throws Exception {
        when(openmrsRestClient.get("order", ORDER_UUID)).thenReturn(order("null"));

        assertNull(orderHandler.getFulfillerStatus(ORDER_UUID));
        assertFalse(orderHandler.isFulfilled(ORDER_UUID));
    }

    @Test
    void shouldNotTreatAnInProgressOrderAsFulfilled() throws Exception {
        when(openmrsRestClient.get("order", ORDER_UUID)).thenReturn(order("\"IN_PROGRESS\""));

        assertFalse(orderHandler.isFulfilled(ORDER_UUID));
    }

    @Test
    void shouldReturnNullWhenTheOrderIsNotFound() throws Exception {
        when(openmrsRestClient.get("order", ORDER_UUID)).thenReturn(null);

        assertNull(orderHandler.getFulfillerStatus(ORDER_UUID));
    }

    private byte[] order(String fulfillerStatus) {
        return ("{\"uuid\":\"" + ORDER_UUID + "\",\"action\":\"NEW\",\"dateStopped\":\"2026-10-07T08:18:08.000+0000\","
                        + "\"fulfillerStatus\":" + fulfillerStatus + "}")
                .getBytes(StandardCharsets.UTF_8);
    }
}

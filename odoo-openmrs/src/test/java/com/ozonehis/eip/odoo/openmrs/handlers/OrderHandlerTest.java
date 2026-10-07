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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ozonehis.eip.odoo.openmrs.client.OpenmrsRestClient;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.OrderHandler;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OrderHandlerTest {

    private static final String ORDER_UUID = "7d3a1f0e-6b1c-4c2e-9a3f-1e2d3c4b5a69";

    private static final String PATIENT = "patient-uuid";

    private static final String CONCEPT = "concept-uuid";

    private static final String VISIT = "visit-uuid";

    private static final String STOPPED = "2026-10-07T08:18:08.000+0000";

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

    // UVL-EMR #360: a repeat of an active test is billed; a cancelled one, another visit's or another test's is not
    @Test
    void shouldCountTheBillableOrdersOfTheSameTestInTheSameVisit() throws Exception {
        when(openmrsRestClient.get(eq("order"), eq(ORDER_UUID), any()))
                .thenReturn(bytes(orderInVisit(ORDER_UUID, "NEW", false, null, null, CONCEPT, VISIT)));
        String results = "{\"results\":["
                + String.join(
                        ",",
                        orderInVisit(ORDER_UUID, "NEW", false, null, null, CONCEPT, VISIT),
                        orderInVisit("repeat", "NEW", false, null, "RECEIVED", CONCEPT, VISIT),
                        orderInVisit("done", "NEW", false, STOPPED, "COMPLETED", CONCEPT, VISIT),
                        orderInVisit("cancelled", "NEW", false, STOPPED, null, CONCEPT, VISIT),
                        orderInVisit("discontinue", "DISCONTINUE", false, null, null, CONCEPT, VISIT),
                        orderInVisit("voided", "NEW", true, null, null, CONCEPT, VISIT),
                        orderInVisit("other-visit", "NEW", false, null, null, CONCEPT, "other-visit"),
                        orderInVisit("other-test", "NEW", false, null, null, "other-concept", VISIT))
                + "]}";
        when(openmrsRestClient.get(eq("order"), isNull(), any())).thenReturn(bytes(results));

        assertEquals(3, orderHandler.countBillableOrdersInVisit(ORDER_UUID));
        verify(openmrsRestClient)
                .get(eq("order"), isNull(), startsWith("patient=" + PATIENT + "&concepts=" + CONCEPT + "&"));
    }

    @Test
    void shouldNotCountWhenTheOrderHasNoVisit() throws Exception {
        when(openmrsRestClient.get(eq("order"), eq(ORDER_UUID), any()))
                .thenReturn(bytes("{\"uuid\":\"" + ORDER_UUID + "\",\"patient\":{\"uuid\":\"" + PATIENT
                        + "\"},\"concept\":{\"uuid\":\"" + CONCEPT + "\"},\"encounter\":{\"uuid\":\"e\"}}"));

        assertNull(orderHandler.countBillableOrdersInVisit(ORDER_UUID));
    }

    @Test
    void shouldNotCountWhenTheOrderIsNotFound() throws Exception {
        when(openmrsRestClient.get(eq("order"), eq(ORDER_UUID), any())).thenReturn(null);

        assertNull(orderHandler.countBillableOrdersInVisit(ORDER_UUID));
    }

    private static String orderInVisit(
            String uuid, String action, boolean voided, String dateStopped, String fulfillerStatus, String concept,
            String visit) {
        return "{\"uuid\":\"" + uuid + "\",\"action\":\"" + action + "\",\"voided\":" + voided
                + ",\"dateStopped\":" + (dateStopped == null ? "null" : "\"" + dateStopped + "\"")
                + ",\"fulfillerStatus\":" + (fulfillerStatus == null ? "null" : "\"" + fulfillerStatus + "\"")
                + ",\"patient\":{\"uuid\":\"" + PATIENT + "\"},\"concept\":{\"uuid\":\"" + concept
                + "\"},\"encounter\":{\"uuid\":\"enc\",\"visit\":{\"uuid\":\"" + visit + "\"}}}";
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] order(String fulfillerStatus) {
        return ("{\"uuid\":\"" + ORDER_UUID + "\",\"action\":\"NEW\",\"dateStopped\":\"2026-10-07T08:18:08.000+0000\","
                        + "\"fulfillerStatus\":" + fulfillerStatus + "}")
                .getBytes(StandardCharsets.UTF_8);
    }
}

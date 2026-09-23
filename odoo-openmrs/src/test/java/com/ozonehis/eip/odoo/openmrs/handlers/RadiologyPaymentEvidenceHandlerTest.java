/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.ozonehis.eip.odoo.openmrs.Constants;
import com.ozonehis.eip.odoo.openmrs.client.OdooClient;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.RadiologyPaymentEvidenceHandler;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class RadiologyPaymentEvidenceHandlerTest {

    private static final String PATIENT = "patient-uuid";

    private static final String VISIT = "visit-uuid";

    private static final String PROCEDURE = "RX01 - Radiographie thoracique";

    private static final Instant ORDERED = Instant.parse("2026-09-10T08:00:00Z");

    @Mock
    private OdooClient odooClient;

    @InjectMocks
    private RadiologyPaymentEvidenceHandler handler;

    private AutoCloseable mocksCloser;

    @BeforeEach
    void setup() {
        mocksCloser = openMocks(this);
    }

    @AfterEach
    void close() throws Exception {
        mocksCloser.close();
    }

    private static Map<String, Object> line(int id, String orderName, double qtyInvoiced, String createDate) {
        Map<String, Object> line = new HashMap<>();
        line.put("id", id);
        line.put("name", PROCEDURE);
        line.put("qty_invoiced", qtyInvoiced);
        line.put("order_id", new Object[] {id * 10, orderName});
        line.put("create_date", createDate);
        return line;
    }

    private static Map<String, Object> invoice(String paymentState, double residual) {
        Map<String, Object> invoice = new HashMap<>();
        invoice.put("name", "INV/2026/09/0001");
        invoice.put("state", "posted");
        invoice.put("payment_state", paymentState);
        invoice.put("amount_residual", residual);
        return invoice;
    }

    private void givenLines(Object... lines) {
        when(odooClient.searchAndRead(eq(Constants.SALE_ORDER_LINE_MODEL), anyList(), anyList()))
                .thenReturn(lines);
    }

    private void givenInvoices(String orderName, Object... invoices) {
        when(odooClient.searchAndRead(
                        eq(Constants.ACCOUNT_MOVE_MODEL),
                        eq(List.of(Arrays.asList("invoice_origin", "=", orderName))),
                        anyList()))
                .thenReturn(invoices);
    }

    @Test
    void paidLineOnTheVisitIsEvidence() {
        givenLines(line(6163, "S02986", 1, "2026-09-10 08:00:05"));
        givenInvoices("S02986", invoice("paid", 0));

        assertTrue(handler.hasPaidLineOnOrAfter(PATIENT, VISIT, PROCEDURE, ORDERED));
    }

    @Test
    void noPaidLineIsNotEvidence() {
        givenLines(line(6163, "S02986", 0, "2026-09-10 08:00:05"));
        givenInvoices("S02986");

        assertFalse(handler.hasPaidLineOnOrAfter(PATIENT, VISIT, PROCEDURE, ORDERED));
    }

    @Test
    void invoicedButUnpaidIsNotEvidence() {
        givenLines(line(6163, "S02986", 1, "2026-09-10 08:00:05"));
        givenInvoices("S02986", invoice("not_paid", 1500));

        assertFalse(handler.hasPaidLineOnOrAfter(PATIENT, VISIT, PROCEDURE, ORDERED));
    }

    /**
     * The case the payment processor's newest-line rule gets wrong: a second order for the same
     * procedure later in the visit puts a newer DRAFT line on a new sale order. The paid line is still
     * payment for the first order.
     */
    @Test
    void newerDraftLineInTheSameVisitDoesNotHideThePaidOne() {
        givenLines(
                line(6163, "S02986", 1, "2026-09-10 08:00:05"),
                line(6170, "S03001", 0, "2026-09-10 11:30:00"));
        givenInvoices("S02986", invoice("paid", 0));

        assertTrue(handler.hasPaidLineOnOrAfter(PATIENT, VISIT, PROCEDURE, ORDERED));
        verify(odooClient, never())
                .searchAndRead(
                        eq(Constants.ACCOUNT_MOVE_MODEL),
                        eq(List.of(Arrays.asList("invoice_origin", "=", "S03001"))),
                        anyList());
    }

    @Test
    void paidLineRaisedBeforeTheOrderIsNotEvidence() {
        givenLines(line(6001, "S02900", 1, "2026-09-07 09:00:00"));
        givenInvoices("S02900", invoice("paid", 0));

        assertFalse(handler.hasPaidLineOnOrAfter(PATIENT, VISIT, PROCEDURE, ORDERED));
    }

    @Test
    void paidLineForAnotherProcedureIsNotEvidence() {
        Map<String, Object> other = line(6163, "S02986", 1, "2026-09-10 08:00:05");
        other.put("name", "ECHO1 - Echographie abdominale");
        givenLines(other);
        givenInvoices("S02986", invoice("paid", 0));

        assertFalse(handler.hasPaidLineOnOrAfter(PATIENT, VISIT, PROCEDURE, ORDERED));
    }

    @Test
    void refusesAPatientWideMatch() {
        assertThrows(
                IllegalArgumentException.class, () -> handler.hasPaidLineOnOrAfter(PATIENT, null, PROCEDURE, ORDERED));
    }
}

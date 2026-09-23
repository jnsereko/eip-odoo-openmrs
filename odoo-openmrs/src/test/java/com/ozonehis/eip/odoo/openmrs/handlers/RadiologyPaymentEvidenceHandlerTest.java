/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers;

import static com.ozonehis.eip.odoo.openmrs.FakeOdoo.PATIENT;
import static com.ozonehis.eip.odoo.openmrs.FakeOdoo.VISIT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.ozonehis.eip.odoo.openmrs.FakeOdoo;
import com.ozonehis.eip.odoo.openmrs.client.OdooClient;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.RadiologyPaymentEvidenceHandler;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RadiologyPaymentEvidenceHandlerTest {

    private static final String PROCEDURE = "RX03 - Radiographie du bassin";

    private static final Instant ORDERED = Instant.parse("2026-09-10T08:00:00Z");

    private FakeOdoo odoo;

    private RadiologyPaymentEvidenceHandler handler;

    @BeforeEach
    void setup() {
        OdooClient odooClient = mock(OdooClient.class);
        odoo = new FakeOdoo(odooClient);
        handler = new RadiologyPaymentEvidenceHandler();
        handler.setOdooClient(odooClient);
        odoo.saleOrder(1, "S03007");
    }

    private boolean paid() {
        return handler.hasPaidLineOnOrAfter(PATIENT, VISIT, PROCEDURE, ORDERED);
    }

    @Test
    void finalInvoicePaidIsPaid() {
        odoo.line(61, 1, PROCEDURE, 1, "2026-09-10 08:00:05");
        odoo.paidInvoice(9, "INV/2026/09/0009", 61);

        assertTrue(paid());
    }

    /** UAT attempt 2, S03007: down payment auto-paid at 0 BIF, the RX03 line on an unpaid final invoice. */
    @Test
    void paidDownPaymentWithUnpaidFinalInvoiceIsNotPaid() {
        odoo.line(61, 1, PROCEDURE, 1, "2026-09-10 08:00:05");
        odoo.line(62, 1, "Down payment", 1, "2026-09-10 08:10:00");
        odoo.paidInvoice(8, "INV/2026/09/0008", 62);
        odoo.unpaidInvoice(9, "INV/2026/09/0009", 1.0, 61, 62);

        assertFalse(paid());
    }

    @Test
    void radiologyLineOnlyOnTheUnpaidOfTwoInvoicesIsNotPaid() {
        odoo.line(61, 1, PROCEDURE, 1, "2026-09-10 08:00:05");
        odoo.line(63, 1, "CONS1 - Consultation", 1, "2026-09-10 08:00:01");
        odoo.paidInvoice(7, "INV/2026/09/0007", 63);
        odoo.unpaidInvoice(9, "INV/2026/09/0009", 1.0, 61);

        assertFalse(paid());
    }

    @Test
    void creditNoteReversingThePaidInvoiceIsNotPaid() {
        odoo.line(61, 1, PROCEDURE, 1, "2026-09-10 08:00:05");
        odoo.paidInvoice(9, "INV/2026/09/0009", 61);
        odoo.move(10, "RINV/2026/09/0001", "out_refund", "posted", "paid", 0.0, 9);

        assertFalse(paid());
    }

    @Test
    void draftCreditNoteDoesNotCancelThePayment() {
        odoo.line(61, 1, PROCEDURE, 1, "2026-09-10 08:00:05");
        odoo.paidInvoice(9, "INV/2026/09/0009", 61);
        odoo.move(10, "RINV/2026/09/0001", "out_refund", "draft", "not_paid", 1.0, 9);

        assertTrue(paid());
    }

    @Test
    void inPaymentIsNotYetPaid() {
        odoo.line(61, 1, PROCEDURE, 1, "2026-09-10 08:00:05");
        odoo.move(9, "INV/2026/09/0009", "out_invoice", "posted", "in_payment", 0.0, null, 61);

        assertFalse(paid());
    }

    @Test
    void noInvoiceIsNotPaid() {
        odoo.line(61, 1, PROCEDURE, 0, "2026-09-10 08:00:05");

        assertFalse(paid());
    }

    /**
     * The case the payment processor's newest-line rule gets wrong: a second order for the same
     * procedure later in the visit puts a newer DRAFT line on a new sale order. The paid line is still
     * payment for the first order.
     */
    @Test
    void newerDraftLineInTheSameVisitDoesNotHideThePaidOne() {
        odoo.saleOrder(2, "S03010");
        odoo.line(61, 1, PROCEDURE, 1, "2026-09-10 08:00:05");
        odoo.line(70, 2, PROCEDURE, 0, "2026-09-10 11:30:00");
        odoo.paidInvoice(9, "INV/2026/09/0009", 61);

        assertTrue(paid());
    }

    @Test
    void anotherPatientsPaidInvoiceIsNotPaid() {
        odoo.saleOrder(3, "S03020", "other-patient", "other-visit", "sale");
        odoo.line(80, 3, PROCEDURE, 1, "2026-09-10 08:00:05");
        odoo.paidInvoice(20, "INV/2026/09/0020", 80);

        assertFalse(paid());
    }

    @Test
    void paidLineRaisedBeforeTheOrderIsNotPaid() {
        odoo.line(61, 1, PROCEDURE, 1, "2026-09-07 09:00:00");
        odoo.paidInvoice(9, "INV/2026/09/0009", 61);

        assertFalse(paid());
    }

    @Test
    void paidLineForAnotherProcedureIsNotPaid() {
        odoo.line(61, 1, "ECHO1 - Echographie abdominale", 1, "2026-09-10 08:00:05");
        odoo.paidInvoice(9, "INV/2026/09/0009", 61);

        assertFalse(paid());
    }

    @Test
    void refusesAPatientWideMatch() {
        assertThrows(
                IllegalArgumentException.class, () -> handler.hasPaidLineOnOrAfter(PATIENT, null, PROCEDURE, ORDERED));
    }
}

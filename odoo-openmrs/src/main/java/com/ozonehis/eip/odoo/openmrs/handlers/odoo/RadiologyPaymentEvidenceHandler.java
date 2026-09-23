/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers.odoo;

import com.ozonehis.eip.odoo.openmrs.Constants;
import com.ozonehis.eip.odoo.openmrs.client.OdooClient;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Answers "is there evidence in Odoo that this imaging order was paid for?" — read-only. Used by the
 * payment processor to decide whether to accept a Task, and by the unpaid-imaging audit (#322) to
 * re-check accepted ones. Both go through {@link #isLinePaid}, so they cannot disagree about what a
 * paid line is.
 *
 * <p>What "paid" means for a sale order line: at least one invoice that CARRIES THAT LINE (reached
 * through the line's own {@code invoice_lines}, not through the sale order) is a posted customer
 * invoice, {@code payment_state=paid}, nothing outstanding, and not reversed by a posted credit note.
 * Looking up invoices by {@code invoice_origin} instead - any paid invoice on the same sale order -
 * was the attempt-2 UAT failure: a 0 BIF down payment on S03007 was auto-paid, and it authorised the
 * RX03 line sitting unpaid on the final invoice. A down payment invoice is linked to the down payment
 * line only, so it can never count for an imaging line; the final invoice has to be paid.
 *
 * <p>This is deliberately NOT the payment processor's decision rule. RadiologyPaymentTaskProcessor
 * decides on the NEWEST matching sale order line, which is right for deciding whether to accept a
 * Task now, and wrong for re-checking a Task that was already accepted: a second order for the same
 * procedure later in the same visit puts a new draft line on a new sale order, the newest line is
 * then that draft, and a correctly paid, correctly accepted order would be reported as unpaid.
 *
 * <p>The rule here is: ANY sale order line that
 * <ul>
 *   <li>belongs to the order's own patient AND its own visit (client_order_ref = visit uuid),</li>
 *   <li>names the same procedure, matched the way the payment processor matches it,</li>
 *   <li>was raised on or after the order was placed (60 s clock-skew tolerance), and</li>
 *   <li>has been invoiced, on an invoice that is paid with nothing outstanding.</li>
 * </ul>
 * The visit is required: a patient-wide match is what let one paid invoice answer for other orders.
 */
@Slf4j
@Setter
@Component
public class RadiologyPaymentEvidenceHandler {

    /** Same tolerance as the payment processor: the line is written seconds after the order. */
    private static final long CLOCK_SKEW_SECONDS = 60;

    private static final String ACCOUNT_MOVE_LINE_MODEL = "account.move.line";

    /** The sale order line fields {@link #isLinePaid} needs; callers must read at least these. */
    public static final List<String> LINE_FIELDS =
            List.of("id", "name", "qty_invoiced", "order_id", "create_date", "invoice_lines");

    @Autowired
    private OdooClient odooClient;

    /**
     * Whether Odoo holds a paid invoice line for this order.
     *
     * @param patientUuid the order's patient (res.partner.ref)
     * @param visitUuid the order's visit (sale.order.client_order_ref); must not be null
     * @param procedureDesc the order's procedure text, as the sale order line names it
     * @param orderedAt when the order was placed; null means the line date cannot be bounded
     * @throws RuntimeException when Odoo cannot be read — the caller must treat that as "unknown",
     *     not as "unpaid"
     */
    public boolean hasPaidLineOnOrAfter(String patientUuid, String visitUuid, String procedureDesc, Instant orderedAt) {
        if (visitUuid == null) {
            throw new IllegalArgumentException("visitUuid is required - a patient-wide match is not evidence");
        }

        Object[] lines = odooClient.searchAndRead(
                Constants.SALE_ORDER_LINE_MODEL,
                Arrays.asList(
                        Arrays.asList("order_id.partner_id.ref", "=", patientUuid),
                        Arrays.asList("order_id.client_order_ref", "=", visitUuid)),
                LINE_FIELDS);
        if (lines == null || lines.length == 0) {
            return false;
        }

        String matchKey = procedureDesc != null && procedureDesc.length() > 6
                ? procedureDesc.substring(0, 6).toLowerCase()
                : (procedureDesc != null ? procedureDesc.toLowerCase() : "");
        if (matchKey.isEmpty()) {
            return false;
        }
        Instant cutoff = orderedAt == null ? null : orderedAt.minusSeconds(CLOCK_SKEW_SECONDS);

        for (Object lineObj : lines) {
            Map<?, ?> line = (Map<?, ?>) lineObj;
            if (!String.valueOf(line.get("name")).toLowerCase().contains(matchKey)) {
                continue;
            }
            if (cutoff != null) {
                Instant created = parseOdooDate(line.get("create_date"));
                if (created == null || created.isBefore(cutoff)) {
                    continue;
                }
            }
            if (isLinePaid(line)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this sale order line is paid: some invoice carrying it is a posted {@code out_invoice},
     * {@code payment_state=paid}, {@code amount_residual=0}, and no posted credit note reverses it.
     *
     * <p>{@code in_payment} is deliberately NOT paid: in Odoo 17 it means a payment is registered but
     * not yet reconciled with the bank, and it can still bounce. UAT settles straight to {@code paid}
     * (INV/2026/09/0006 did), so this costs nothing there.
     *
     * <p>Any posted credit note against the invoice cancels it, even a partial one - for a single
     * imaging line a partial refund still means the exam is disputed, and the audit exists to be
     * conservative.
     *
     * @param line a sale.order.line record read with at least {@link #LINE_FIELDS}
     * @throws RuntimeException when Odoo cannot be read — "unknown", never "unpaid"
     */
    public boolean isLinePaid(Map<?, ?> line) {
        Object qtyInvoiced = line.get("qty_invoiced");
        if (!(qtyInvoiced instanceof Number) || ((Number) qtyInvoiced).doubleValue() <= 0) {
            return false;
        }
        List<Integer> invoiceLineIds = ids(line.get("invoice_lines"));
        if (invoiceLineIds.isEmpty()) {
            return false;
        }

        Object[] invoiceLines = odooClient.searchAndRead(
                ACCOUNT_MOVE_LINE_MODEL,
                List.of(Arrays.asList("id", "in", invoiceLineIds)),
                Arrays.asList("id", "move_id"));
        Set<Integer> moveIds = new LinkedHashSet<>();
        if (invoiceLines != null) {
            for (Object invoiceLine : invoiceLines) {
                Integer moveId = many2oneId(((Map<?, ?>) invoiceLine).get("move_id"));
                if (moveId != null) {
                    moveIds.add(moveId);
                }
            }
        }
        if (moveIds.isEmpty()) {
            return false;
        }

        Object[] moves = odooClient.searchAndRead(
                Constants.ACCOUNT_MOVE_MODEL,
                List.of(Arrays.asList("id", "in", new ArrayList<>(moveIds))),
                Arrays.asList("id", "name", "move_type", "state", "payment_state", "amount_residual"));
        Set<Integer> paidInvoiceIds = new LinkedHashSet<>();
        if (moves != null) {
            for (Object moveObj : moves) {
                Map<?, ?> move = (Map<?, ?>) moveObj;
                Object residual = move.get("amount_residual");
                if ("out_invoice".equals(String.valueOf(move.get("move_type")))
                        && "posted".equals(String.valueOf(move.get("state")))
                        && "paid".equals(String.valueOf(move.get("payment_state")))
                        && residual instanceof Number
                        && ((Number) residual).doubleValue() == 0.0
                        && move.get("id") instanceof Number) {
                    paidInvoiceIds.add(((Number) move.get("id")).intValue());
                }
            }
        }
        if (paidInvoiceIds.isEmpty()) {
            return false;
        }

        // Searched by reversed_entry_id rather than taken from invoice_lines: a credit note made from
        // the invoice is not guaranteed to be linked back to the sale order line.
        Object[] creditNotes = odooClient.searchAndRead(
                Constants.ACCOUNT_MOVE_MODEL,
                Arrays.asList(
                        Arrays.asList("reversed_entry_id", "in", new ArrayList<>(paidInvoiceIds)),
                        Arrays.asList("move_type", "=", "out_refund"),
                        Arrays.asList("state", "=", "posted")),
                Arrays.asList("id", "name", "reversed_entry_id"));
        if (creditNotes != null) {
            for (Object creditNote : creditNotes) {
                paidInvoiceIds.remove(many2oneId(((Map<?, ?>) creditNote).get("reversed_entry_id")));
            }
        }
        return !paidInvoiceIds.isEmpty();
    }

    /** Odoo x2many fields read as an array of ids. */
    private static List<Integer> ids(Object x2many) {
        List<Integer> ids = new ArrayList<>();
        if (x2many instanceof Object[]) {
            for (Object id : (Object[]) x2many) {
                if (id instanceof Number) {
                    ids.add(((Number) id).intValue());
                }
            }
        }
        return ids;
    }

    /** Odoo many2one fields read as [id, display_name], or false when empty. */
    private static Integer many2oneId(Object many2one) {
        if (many2one instanceof Object[] && ((Object[]) many2one).length > 0 && ((Object[]) many2one)[0] instanceof Number) {
            return ((Number) ((Object[]) many2one)[0]).intValue();
        }
        return null;
    }

    /** Odoo datetimes are "yyyy-MM-dd HH:mm:ss[.ffffff]" in UTC with no zone marker. */
    private Instant parseOdooDate(Object value) {
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty() || "false".equals(raw)) {
            return null;
        }
        int dot = raw.indexOf('.');
        if (dot > 0) {
            raw = raw.substring(0, dot);
        }
        try {
            return LocalDateTime.parse(raw.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            log.warn("Unparseable Odoo create_date '{}'", value);
            return null;
        }
    }
}

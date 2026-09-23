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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Answers "is there evidence in Odoo that this imaging order was paid for?" — read-only, for the
 * unpaid-imaging audit (#322).
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
                Arrays.asList("id", "name", "qty_invoiced", "order_id", "create_date"));
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

        // One invoice lookup per sale order, however many of its lines match.
        Map<String, Boolean> paidByOrderName = new HashMap<>();
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
            Object qtyInvoiced = line.get("qty_invoiced");
            if (!(qtyInvoiced instanceof Number) || ((Number) qtyInvoiced).doubleValue() <= 0) {
                continue;
            }
            String orderName = orderName(line.get("order_id"));
            if (orderName == null) {
                continue;
            }
            if (paidByOrderName.computeIfAbsent(orderName, this::hasPaidInvoice)) {
                return true;
            }
        }
        return false;
    }

    /** Paid means what the payment processor accepts: payment_state=paid and nothing outstanding. */
    private boolean hasPaidInvoice(String orderName) {
        Object[] invoices = odooClient.searchAndRead(
                Constants.ACCOUNT_MOVE_MODEL,
                List.of(Arrays.asList("invoice_origin", "=", orderName)),
                Arrays.asList("name", "state", "payment_state", "amount_residual"));
        if (invoices == null) {
            return false;
        }
        for (Object invoiceObj : invoices) {
            Map<?, ?> invoice = (Map<?, ?>) invoiceObj;
            Object residual = invoice.get("amount_residual");
            if ("paid".equals(String.valueOf(invoice.get("payment_state")))
                    && residual instanceof Number
                    && ((Number) residual).doubleValue() == 0.0) {
                return true;
            }
        }
        return false;
    }

    /** Odoo many2one fields read as [id, display_name]. */
    private String orderName(Object orderIdField) {
        if (orderIdField instanceof Object[] && ((Object[]) orderIdField).length > 1) {
            String name = String.valueOf(((Object[]) orderIdField)[1]);
            return name.isEmpty() ? null : name;
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

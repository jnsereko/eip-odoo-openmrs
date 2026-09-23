/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.ozonehis.eip.odoo.openmrs.client.OdooClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An in-memory Odoo behind a mocked {@link OdooClient#searchAndRead}: records per model, filtered by
 * the domain the code actually sends ("=" and "in" clauses). Many2one values are stored as
 * {@code [id, name]}, as XML-RPC returns them, and compared on the id.
 *
 * <p>Sale order lines carry their patient and visit under the dotted domain keys
 * ({@code order_id.partner_id.ref}, {@code order_id.client_order_ref}) so the patient/visit scoping
 * is exercised, not assumed.
 */
public class FakeOdoo {

    public static final String PATIENT = "patient-uuid";

    public static final String VISIT = "visit-uuid";

    private final Map<String, List<Map<String, Object>>> records = new HashMap<>();

    private int nextInvoiceLineId = 1000;

    public FakeOdoo(OdooClient odooClient) {
        when(odooClient.searchAndRead(anyString(), anyList(), anyList()))
                .thenAnswer(invocation -> search(invocation.getArgument(0), invocation.getArgument(1)));
    }

    /** A sale order in this patient's visit, state sale. */
    public void saleOrder(int id, String name) {
        saleOrder(id, name, PATIENT, VISIT, "sale");
    }

    public void saleOrder(int id, String name, String patient, String visit, String state) {
        Map<String, Object> order = new HashMap<>();
        order.put("id", id);
        order.put("name", name);
        order.put("state", state);
        order.put("partner_ref", patient);
        order.put("visit", visit);
        add(Constants.SALE_ORDER_MODEL, order);
    }

    /** A sale order line; qtyInvoiced is what Odoo computes from the posted invoices carrying it. */
    public Map<String, Object> line(int id, int orderId, String name, double qtyInvoiced, String createDate) {
        Map<String, Object> order = find(Constants.SALE_ORDER_MODEL, orderId);
        Map<String, Object> line = new HashMap<>();
        line.put("id", id);
        line.put("name", name);
        line.put("qty_invoiced", qtyInvoiced);
        line.put("order_id", new Object[] {orderId, order.get("name")});
        line.put("create_date", createDate);
        line.put("invoice_lines", new Object[0]);
        line.put("order_id.partner_id.ref", order.get("partner_ref"));
        line.put("order_id.client_order_ref", order.get("visit"));
        add(Constants.SALE_ORDER_LINE_MODEL, line);
        return line;
    }

    /** A customer invoice (or credit note) and the sale order lines it carries. */
    public void move(int id, String name, String moveType, String state, String paymentState, double residual,
            Integer reversedEntryId, int... saleLineIds) {
        Map<String, Object> move = new HashMap<>();
        move.put("id", id);
        move.put("name", name);
        move.put("move_type", moveType);
        move.put("state", state);
        move.put("payment_state", paymentState);
        move.put("amount_residual", residual);
        move.put("reversed_entry_id", reversedEntryId == null ? Boolean.FALSE : new Object[] {reversedEntryId, "INV"});
        add(Constants.ACCOUNT_MOVE_MODEL, move);
        for (int saleLineId : saleLineIds) {
            int amlId = nextInvoiceLineId++;
            Map<String, Object> aml = new HashMap<>();
            aml.put("id", amlId);
            aml.put("move_id", new Object[] {id, name});
            add("account.move.line", aml);
            Map<String, Object> saleLine = find(Constants.SALE_ORDER_LINE_MODEL, saleLineId);
            Object[] old = (Object[]) saleLine.get("invoice_lines");
            Object[] grown = java.util.Arrays.copyOf(old, old.length + 1);
            grown[old.length] = amlId;
            saleLine.put("invoice_lines", grown);
        }
    }

    public void paidInvoice(int id, String name, int... saleLineIds) {
        move(id, name, "out_invoice", "posted", "paid", 0.0, null, saleLineIds);
    }

    public void unpaidInvoice(int id, String name, double residual, int... saleLineIds) {
        move(id, name, "out_invoice", "posted", "not_paid", residual, null, saleLineIds);
    }

    private void add(String model, Map<String, Object> record) {
        records.computeIfAbsent(model, k -> new ArrayList<>()).add(record);
    }

    private Map<String, Object> find(String model, int id) {
        return records.getOrDefault(model, List.of()).stream()
                .filter(r -> Objects.equals(r.get("id"), id))
                .findFirst()
                .orElseThrow();
    }

    private Object[] search(String model, List<Object> domain) {
        return records.getOrDefault(model, List.of()).stream()
                .filter(record -> domain.stream().allMatch(clause -> matches(record, (List<?>) clause)))
                .toArray();
    }

    private static boolean matches(Map<String, Object> record, List<?> clause) {
        Object value = idOf(record.get(String.valueOf(clause.get(0))));
        String op = String.valueOf(clause.get(1));
        Object expected = clause.get(2);
        if ("=".equals(op)) {
            return Objects.equals(value, expected);
        }
        if ("in".equals(op)) {
            return ((List<?>) expected).contains(value);
        }
        throw new IllegalArgumentException("FakeOdoo does not support operator " + op);
    }

    private static Object idOf(Object value) {
        return value instanceof Object[] && ((Object[]) value).length > 0 ? ((Object[]) value)[0] : value;
    }
}

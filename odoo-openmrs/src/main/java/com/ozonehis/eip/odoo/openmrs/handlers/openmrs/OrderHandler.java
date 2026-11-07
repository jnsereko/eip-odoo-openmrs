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
import java.util.ArrayList;
import java.util.List;
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

    private static final String ACTION_DISCONTINUE = "DISCONTINUE";

    // What countBillableOrdersInVisit reads of an order
    private static final String ORDER_IN_VISIT_REP = "custom:(uuid,action,voided,dateStopped,fulfillerStatus,"
            + "patient:(uuid),concept:(uuid),encounter:(uuid,visit:(uuid)))";

    // What getDrugOrdersInVisit reads of an order. drug and quantity exist on drug orders only, and OpenMRS rejects
    // the whole search when one result lacks them, so the search is narrowed to the drug's concept.
    private static final String DRUG_ORDER_IN_VISIT_REP =
            "custom:(uuid,action,voided,dateStopped,fulfillerStatus,quantity,quantityUnits:(uuid),drug:(uuid),"
                    + "patient:(uuid),concept:(uuid),encounter:(uuid,visit:(uuid)))";

    // Orders of one test for one patient; far more than a patient has
    private static final int SEARCH_LIMIT = 100;

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

    /**
     * How many orders of the same test as the given order, in the same visit, are to be billed (UVL-EMR#360).
     *
     * <p>An order counts when it is not voided, is not itself a DISCONTINUE order, and is either still active
     * or was carried out (fulfiller status COMPLETED). So a repeat of an active test counts twice, a revision
     * counts once (the revised order is stopped unfulfilled), and an order discontinued before it was done no
     * longer counts.
     *
     * <p>Every criterion is re-checked on the response rather than left to the search, because OpenMRS
     * silently ignores search parameters it does not support.
     *
     * @return the count, or null when the order, its test or its visit cannot be read
     */
    public Integer countBillableOrdersInVisit(String orderUuid) throws Exception {
        byte[] order = openmrsRestClient.get(ORDER_RESOURCE, orderUuid, "v=" + ORDER_IN_VISIT_REP);
        if (order == null) {
            log.warn("Order {} not found in OpenMRS", orderUuid);
            return null;
        }
        JsonNode node = MAPPER.readTree(order);
        String patientUuid = node.at("/patient/uuid").asText(null);
        String conceptUuid = node.at("/concept/uuid").asText(null);
        String visitUuid = node.at("/encounter/visit/uuid").asText(null);
        if (patientUuid == null || conceptUuid == null || visitUuid == null) {
            log.warn("Order {} has no patient, concept or visit, so its repeats cannot be counted", orderUuid);
            return null;
        }

        byte[] orders = openmrsRestClient.get(
                ORDER_RESOURCE,
                null,
                "patient=" + patientUuid + "&concepts=" + conceptUuid + "&limit=" + SEARCH_LIMIT + "&v="
                        + ORDER_IN_VISIT_REP);
        if (orders == null) {
            return null;
        }
        int count = 0;
        for (JsonNode candidate : MAPPER.readTree(orders).path("results")) {
            if (isBillableInVisit(candidate, conceptUuid, visitUuid)) {
                count++;
            }
        }
        log.debug("Order {}: {} billable order(s) of concept {} in visit {}", orderUuid, count, conceptUuid, visitUuid);
        return count;
    }

    /**
     * An order of a drug in a visit, as {@link #getDrugOrdersInVisit} reads it.
     *
     * @param active the order is not stopped (discontinued or revised); an order past its auto-expire date still is
     * @param quantity the quantity to dispense, or null when the prescription has none
     * @param quantityUnits the concept uuid of the quantity's unit, or null
     */
    public record DrugOrderInVisit(
            String uuid, boolean active, Float quantity, String quantityUnits, String fulfillerStatus) {}

    /**
     * The orders of the same drug (the same Odoo product) as the given order, in the same visit (UVL-EMR#544): not
     * voided and not DISCONTINUE orders, whether still active or stopped. What each of them bills is for the caller
     * to decide, as a stopped order may have been dispensed before it was stopped.
     *
     * <p>Every criterion is re-checked on the response rather than left to the search, because OpenMRS silently
     * ignores search parameters it does not support.
     *
     * @return the orders, or null when the order, its drug or its visit cannot be read
     */
    public List<DrugOrderInVisit> getDrugOrdersInVisit(String orderUuid) throws Exception {
        byte[] order = openmrsRestClient.get(ORDER_RESOURCE, orderUuid, "v=" + DRUG_ORDER_IN_VISIT_REP);
        if (order == null) {
            log.warn("Order {} not found in OpenMRS", orderUuid);
            return null;
        }
        JsonNode node = MAPPER.readTree(order);
        String patientUuid = node.at("/patient/uuid").asText(null);
        String conceptUuid = node.at("/concept/uuid").asText(null);
        String drugUuid = node.at("/drug/uuid").asText(null);
        String visitUuid = node.at("/encounter/visit/uuid").asText(null);
        if (patientUuid == null || conceptUuid == null || drugUuid == null || visitUuid == null) {
            log.warn("Order {} has no patient, drug or visit, so the orders of its drug cannot be read", orderUuid);
            return null;
        }

        byte[] orders = openmrsRestClient.get(
                ORDER_RESOURCE,
                null,
                "patient=" + patientUuid + "&concepts=" + conceptUuid + "&limit=" + SEARCH_LIMIT + "&v="
                        + DRUG_ORDER_IN_VISIT_REP);
        if (orders == null) {
            return null;
        }
        List<DrugOrderInVisit> drugOrders = new ArrayList<>();
        for (JsonNode candidate : MAPPER.readTree(orders).path("results")) {
            if (candidate.path("voided").asBoolean(false)
                    || ACTION_DISCONTINUE.equals(candidate.path("action").asText(null))
                    || !drugUuid.equals(candidate.at("/drug/uuid").asText(null))
                    || !visitUuid.equals(candidate.at("/encounter/visit/uuid").asText(null))) {
                continue;
            }
            JsonNode quantity = candidate.path("quantity");
            drugOrders.add(new DrugOrderInVisit(
                    candidate.path("uuid").asText(null),
                    candidate.path("dateStopped").isMissingNode()
                            || candidate.path("dateStopped").isNull(),
                    quantity.isNumber() ? quantity.floatValue() : null,
                    candidate.at("/quantityUnits/uuid").asText(null),
                    candidate.path("fulfillerStatus").asText(null)));
        }
        log.debug("Order {}: {} order(s) of drug {} in visit {}", orderUuid, drugOrders.size(), drugUuid, visitUuid);
        return drugOrders;
    }

    private static boolean isBillableInVisit(JsonNode order, String conceptUuid, String visitUuid) {
        if (order.path("voided").asBoolean(false)
                || ACTION_DISCONTINUE.equals(order.path("action").asText(null))
                || !conceptUuid.equals(order.at("/concept/uuid").asText(null))
                || !visitUuid.equals(order.at("/encounter/visit/uuid").asText(null))) {
            return false;
        }
        boolean active = order.path("dateStopped").isMissingNode()
                || order.path("dateStopped").isNull();
        return active || FULFILLER_STATUS_COMPLETED.equals(order.path("fulfillerStatus").asText(null));
    }
}

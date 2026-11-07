/*
 * Copyright © 2021, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers.odoo;

import static java.util.Arrays.asList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozonehis.eip.odoo.openmrs.Constants;
import com.ozonehis.eip.odoo.openmrs.client.OdooClient;
import com.ozonehis.eip.odoo.openmrs.client.OpenmrsRestClient;
import com.ozonehis.eip.odoo.openmrs.model.SaleOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.openmrs.eip.EIPException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Names the insurance plans that pay for a visit on the visit's quotation.
 *
 * <p>The payer is the coded visit attribute set when the visit is started; a second, optional attribute names
 * a supplementary payer. Each answer is looked up among the Odoo plans (partners of the
 * {@code insurance_coverage} addon) by {@code insurance_payer_code}, so which answer bills which plan is
 * configured in Odoo, not here. A plan of type complementary is named as the complementary plan, anything else
 * as the base plan.
 *
 * <p>Off unless {@code odoo.insurance.payer.visit.attribute.type} is set. When on:
 * <ul>
 *   <li>a visit with no payer is left to the patient's plans in Odoo;
 *   <li>a payer listed in {@code odoo.insurance.self.pay.payers} names no plan, so the patient pays what no
 *       supplementary plan covers, even if they hold a plan;
 *   <li>when {@code odoo.insurance.supplementary.visit.attribute.type} is set, a visit with a payer but no
 *       supplementary payer names no complementary plan, rather than taking the patient's;
 *   <li>a payer with no plan in Odoo is an error: the quotation is not created, rather than billed to the
 *       wrong party.
 * </ul>
 *
 * <p>The plans are set once, on the new quotation. A payer changed afterwards is not applied to it, but it is
 * not silent either: the next order of the visit leaves a note on the quotation (see
 * {@link #noteVisitPayerChange}).
 */
@Slf4j
@Setter
@Component
public class InsurancePayerHandler {

    static final String BASE_PLAN_FIELD = "insurance_base_id";

    static final String COMPLEMENTARY_PLAN_FIELD = "insurance_complementary_id";

    static final String MESSAGE_MODEL = "mail.message";

    private static final String VISIT_RESOURCE = "visit";

    private static final String VISIT_ATTRIBUTES_REPRESENTATION =
            "?v=custom:(uuid,attributes:(uuid,voided,attributeType:(uuid),value))";

    private static final String NO_PLAN = "-";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${odoo.insurance.payer.visit.attribute.type:}")
    private String payerAttributeTypeUuid;

    @Value("${odoo.insurance.supplementary.visit.attribute.type:}")
    private String supplementaryAttributeTypeUuid;

    @Value("${odoo.insurance.self.pay.payers:}")
    private String selfPayPayers;

    @Value("${odoo.insurance.payer.changed.note:" + DEFAULT_PAYER_CHANGED_NOTE + "}")
    private String payerChangedNote;

    static final String DEFAULT_PAYER_CHANGED_NOTE = "The payer of this visit was changed after this quotation was"
            + " created. The quotation is billed to: %s. The visit now says: %s. If the visit is right, correct the"
            + " insurance plans of this quotation; invoices already made do not change.";

    @Autowired
    private OdooClient odooClient;

    @Autowired
    private OpenmrsRestClient openmrsRestClient;

    public boolean isEnabled() {
        return payerAttributeTypeUuid != null && !payerAttributeTypeUuid.isBlank();
    }

    /**
     * Sets the plans of a new quotation from the payers of its visit. Does nothing when the feature is off.
     *
     * @throws EIPException when the visit cannot be read, or one of its payers has no plan in Odoo
     */
    public void applyVisitPayer(SaleOrder saleOrder, String visitUuid) {
        if (!isEnabled()) {
            return;
        }
        VisitPlans plans = getVisitPlans(visitUuid);
        if (plans == null) {
            log.info("Visit {} has no payer: its quotation takes the patient's plans in Odoo", visitUuid);
            return;
        }
        saleOrder.setInsuranceBaseId(plans.baseId);
        saleOrder.setInsuranceComplementaryId(plans.complementaryId);
        log.info(
                "Visit {} is paid by {} (base plan {}, complementary plan {})",
                visitUuid,
                plans,
                plans.baseId,
                plans.complementaryId);
    }

    /**
     * Leaves a note on an existing quotation when its visit's payers no longer match the plans it names: the
     * payer was changed after the visit's first order. The plans are not changed, as lines already on the
     * quotation were ordered under them, but the cashier sees the change. One note per change. Never throws:
     * a payer with no plan is noted too, and the order is still billed.
     */
    public void noteVisitPayerChange(SaleOrder quotation, String visitUuid) {
        if (!isEnabled() || quotation == null || quotation.getOrderId() == null) {
            return;
        }
        try {
            String visitSide;
            try {
                VisitPlans plans = getVisitPlans(visitUuid);
                if (plans == null) {
                    return;
                }
                Map<String, Object> current = readQuotationPlans(quotation.getOrderId());
                if (plans.matches(current)) {
                    return;
                }
                visitSide = plans.toString();
            } catch (EIPException e) {
                visitSide = e.getMessage();
            }
            String quotationSide = describeQuotationPlans(readQuotationPlans(quotation.getOrderId()));
            if (isAlreadyNoted(quotation.getOrderId(), visitSide)) {
                return;
            }
            // An empty setting (a compose variable passed through unset) keeps the default text.
            String template = payerChangedNote == null || payerChangedNote.isBlank()
                    ? DEFAULT_PAYER_CHANGED_NOTE
                    : payerChangedNote;
            String note = String.format(template, quotationSide, visitSide);
            Map<String, Object> message = new HashMap<>();
            message.put("model", Constants.SALE_ORDER_MODEL);
            message.put("res_id", quotation.getOrderId());
            message.put("message_type", "comment");
            message.put("body", note);
            odooClient.create(MESSAGE_MODEL, List.of(message));
            log.warn(
                    "Visit {}: payer changed after quotation {} was created. Quotation billed to {}, visit now says"
                            + " {}. Noted on the quotation; its plans are unchanged.",
                    visitUuid,
                    quotation.getOrderId(),
                    quotationSide,
                    visitSide);
        } catch (RuntimeException e) {
            // Billing the order matters more than the note.
            log.error(
                    "Could not compare the payer of visit {} with quotation {}", visitUuid, quotation.getOrderId(), e);
        }
    }

    /**
     * Returns the payer recorded on the visit: the uuid of the answer for a coded attribute, the value
     * otherwise. Null when the visit has none.
     */
    public String getVisitPayer(String visitUuid) {
        return getAttributeValue(readVisitAttributes(visitUuid), payerAttributeTypeUuid);
    }

    /**
     * The plans the visit's payers name, or null when the visit names no payer at all. A field left null is left
     * to Odoo; Boolean.FALSE names no plan.
     */
    VisitPlans getVisitPlans(String visitUuid) {
        JsonNode attributes = readVisitAttributes(visitUuid);
        String payer = getAttributeValue(attributes, payerAttributeTypeUuid);
        boolean supplementaryOn = supplementaryAttributeTypeUuid != null && !supplementaryAttributeTypeUuid.isBlank();
        String supplementary = supplementaryOn ? getAttributeValue(attributes, supplementaryAttributeTypeUuid) : null;
        if (payer == null && supplementary == null) {
            return null;
        }

        VisitPlans plans = new VisitPlans();
        if (payer != null) {
            plans.place(visitUuid, payer, getSelfPayPayers().contains(payer) ? null : getPlan(visitUuid, payer));
        }
        if (supplementary != null) {
            plans.place(
                    visitUuid,
                    supplementary,
                    getSelfPayPayers().contains(supplementary) ? null : getPlan(visitUuid, supplementary));
        }
        // A self-paid payer names no base plan. With the supplementary field on the visit form, an empty one
        // means no complementary plan, not the patient's.
        boolean selfPaid = payer != null && getSelfPayPayers().contains(payer);
        if (selfPaid && plans.baseId == null) {
            plans.baseId = Boolean.FALSE;
        }
        if (payer != null && plans.complementaryId == null && (supplementaryOn || selfPaid)) {
            plans.complementaryId = Boolean.FALSE;
        }
        return plans;
    }

    private JsonNode readVisitAttributes(String visitUuid) {
        byte[] visit;
        try {
            visit = openmrsRestClient.get(VISIT_RESOURCE, visitUuid + VISIT_ATTRIBUTES_REPRESENTATION);
        } catch (Exception e) {
            throw new EIPException(String.format("Could not read the payer of visit %s", visitUuid), e);
        }
        if (visit == null) {
            throw new EIPException(String.format("Visit %s not found in OpenMRS", visitUuid));
        }
        try {
            return MAPPER.readTree(visit).path("attributes");
        } catch (Exception e) {
            throw new EIPException(String.format("Could not read the attributes of visit %s", visitUuid), e);
        }
    }

    private static String getAttributeValue(JsonNode attributes, String attributeTypeUuid) {
        for (JsonNode attribute : attributes) {
            if (attribute.path("voided").asBoolean(false)
                    || !attributeTypeUuid.equals(
                            attribute.path("attributeType").path("uuid").asText())) {
                continue;
            }
            JsonNode value = attribute.path("value");
            String answer = value.isObject() ? value.path("uuid").asText(null) : value.asText(null);
            if (answer != null && !answer.isBlank()) {
                return answer;
            }
        }
        return null;
    }

    private Map<String, Object> getPlan(String visitUuid, String payer) {
        Map<String, Object> plan = getPlanByPayerCode(payer);
        if (plan == null) {
            throw new EIPException(String.format(
                    "Visit %s has payer %s, but no Odoo insurance plan has that payer code. Give the plan this"
                            + " insurance_payer_code, or list the payer in odoo.insurance.self.pay.payers if the"
                            + " patient pays.",
                    visitUuid, payer));
        }
        return plan;
    }

    private Map<String, Object> getPlanByPayerCode(String payer) {
        Object[] records = odooClient.searchAndRead(
                Constants.PARTNER_MODEL,
                List.of(asList("is_insurance", "=", true), asList("insurance_payer_code", "=", payer)),
                asList("id", "name", "insurance_type"));
        if (records == null) {
            throw new EIPException(
                    String.format("Got null response while fetching the Odoo plan with payer code %s", payer));
        }
        if (records.length == 0) {
            return null;
        }
        if (records.length > 1) {
            throw new EIPException(String.format("Several Odoo insurance plans have payer code %s", payer));
        }
        return (Map<String, Object>) records[0];
    }

    private Map<String, Object> readQuotationPlans(Integer orderId) {
        Object[] records = odooClient.searchAndRead(
                Constants.SALE_ORDER_MODEL,
                List.of(asList("id", "=", orderId)),
                asList("id", BASE_PLAN_FIELD, COMPLEMENTARY_PLAN_FIELD));
        if (records == null || records.length != 1) {
            throw new EIPException(String.format("Could not read the insurance plans of quotation %s", orderId));
        }
        return (Map<String, Object>) records[0];
    }

    private boolean isAlreadyNoted(Integer orderId, String visitSide) {
        Object[] notes = odooClient.searchAndRead(
                MESSAGE_MODEL,
                List.of(
                        asList("model", "=", Constants.SALE_ORDER_MODEL),
                        asList("res_id", "=", orderId),
                        asList("body", "ilike", visitSide)),
                asList("id"));
        return notes != null && notes.length > 0;
    }

    private static String describeQuotationPlans(Map<String, Object> quotation) {
        String base = many2oneName(quotation.get(BASE_PLAN_FIELD));
        String complementary = many2oneName(quotation.get(COMPLEMENTARY_PLAN_FIELD));
        return describe(base, complementary);
    }

    private static String describe(String base, String complementary) {
        if (base == null && complementary == null) {
            return NO_PLAN;
        }
        if (complementary == null) {
            return base;
        }
        return (base == null ? NO_PLAN : base) + " + " + complementary;
    }

    /** The id of an Odoo many2one as read ({@code [id, name]} or false), or null for none. */
    static Integer many2oneId(Object value) {
        if (value instanceof Object[] pair && pair.length > 0) {
            return (Integer) pair[0];
        }
        if (value instanceof List<?> pair && !pair.isEmpty()) {
            return (Integer) pair.get(0);
        }
        return value instanceof Integer id ? id : null;
    }

    private static String many2oneName(Object value) {
        if (value instanceof Object[] pair && pair.length > 1) {
            return String.valueOf(pair[1]);
        }
        if (value instanceof List<?> pair && pair.size() > 1) {
            return String.valueOf(pair.get(1));
        }
        return null;
    }

    private Set<String> getSelfPayPayers() {
        if (selfPayPayers == null || selfPayPayers.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(selfPayPayers.split(","))
                .map(String::trim)
                .filter(payer -> !payer.isEmpty())
                .collect(Collectors.toSet());
    }

    /** The plans a visit names. Integer plan id, Boolean.FALSE for an explicit "no plan", null to leave out. */
    static class VisitPlans {

        Object baseId;

        String baseName;

        Object complementaryId;

        String complementaryName;

        /** Names {@code plan} in the field its type calls for; a null plan is a self-paid payer. */
        void place(String visitUuid, String payer, Map<String, Object> plan) {
            if (plan == null) {
                return;
            }
            Integer planId = (Integer) plan.get("id");
            String planName = String.valueOf(plan.get("name"));
            if ("complementary".equals(plan.get("insurance_type"))) {
                if (complementaryId != null) {
                    throw new EIPException(String.format(
                            "Visit %s names two complementary plans (%s and %s, payer %s). Keep one.",
                            visitUuid, complementaryName, planName, payer));
                }
                complementaryId = planId;
                complementaryName = planName;
            } else {
                if (baseId != null) {
                    throw new EIPException(String.format(
                            "Visit %s names two base plans (%s and %s, payer %s). Keep one.",
                            visitUuid, baseName, planName, payer));
                }
                baseId = planId;
                baseName = planName;
            }
        }

        /** Whether the quotation as read names the same plans, for the fields this visit decides. */
        boolean matches(Map<String, Object> quotation) {
            return matches(baseId, quotation.get(BASE_PLAN_FIELD))
                    && matches(complementaryId, quotation.get(COMPLEMENTARY_PLAN_FIELD));
        }

        private static boolean matches(Object wanted, Object current) {
            if (wanted == null) {
                return true;
            }
            Integer wantedId = wanted instanceof Integer id ? id : null;
            return Objects.equals(wantedId, many2oneId(current));
        }

        @Override
        public String toString() {
            return describe(baseName, complementaryName);
        }
    }
}

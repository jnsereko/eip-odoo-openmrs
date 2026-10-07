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
import java.util.List;
import java.util.Map;
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
 * <p>The payer is the coded visit attribute set when the visit is started. Its answer is looked up among the
 * Odoo plans (partners of the {@code insurance_coverage} addon) by {@code insurance_payer_code}, so which
 * answer bills which plan is configured in Odoo, not here. A plan of type complementary is named as the
 * complementary plan, anything else as the base plan; a plan left unnamed defaults in Odoo from the patient's
 * own plans.
 *
 * <p>Off unless {@code odoo.insurance.payer.visit.attribute.type} is set. When on:
 * <ul>
 *   <li>a visit with no payer is left to the patient's plans in Odoo;
 *   <li>a payer listed in {@code odoo.insurance.self.pay.payers} names no plan at all, so the patient pays
 *       the whole quotation even if they hold a plan;
 *   <li>a payer with no plan in Odoo is an error: the quotation is not created, rather than billed to the
 *       wrong party.
 * </ul>
 */
@Slf4j
@Setter
@Component
public class InsurancePayerHandler {

    static final String BASE_PLAN_FIELD = "insurance_base_id";

    static final String COMPLEMENTARY_PLAN_FIELD = "insurance_complementary_id";

    private static final String VISIT_RESOURCE = "visit";

    private static final String VISIT_ATTRIBUTES_REPRESENTATION =
            "?v=custom:(uuid,attributes:(uuid,voided,attributeType:(uuid),value))";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${odoo.insurance.payer.visit.attribute.type:}")
    private String payerAttributeTypeUuid;

    @Value("${odoo.insurance.self.pay.payers:}")
    private String selfPayPayers;

    @Autowired
    private OdooClient odooClient;

    @Autowired
    private OpenmrsRestClient openmrsRestClient;

    public boolean isEnabled() {
        return payerAttributeTypeUuid != null && !payerAttributeTypeUuid.isBlank();
    }

    /**
     * Sets the plans of a new quotation from the payer of its visit. Does nothing when the feature is off.
     *
     * @throws EIPException when the visit cannot be read, or its payer has no plan in Odoo
     */
    public void applyVisitPayer(SaleOrder saleOrder, String visitUuid) {
        if (!isEnabled()) {
            return;
        }
        String payer = getVisitPayer(visitUuid);
        if (payer == null) {
            log.info("Visit {} has no payer: its quotation takes the patient's plans in Odoo", visitUuid);
            return;
        }
        if (getSelfPayPayers().contains(payer)) {
            saleOrder.setInsuranceBaseId(Boolean.FALSE);
            saleOrder.setInsuranceComplementaryId(Boolean.FALSE);
            log.info("Visit {} is self-paid (payer {}): its quotation names no plan", visitUuid, payer);
            return;
        }

        Map<String, Object> plan = getPlanByPayerCode(payer);
        if (plan == null) {
            throw new EIPException(String.format(
                    "Visit %s has payer %s, but no Odoo insurance plan has that payer code. Give the plan this"
                            + " insurance_payer_code, or list the payer in odoo.insurance.self.pay.payers if the"
                            + " patient pays.",
                    visitUuid, payer));
        }
        Integer planId = (Integer) plan.get("id");
        if ("complementary".equals(plan.get("insurance_type"))) {
            saleOrder.setInsuranceComplementaryId(planId);
        } else {
            saleOrder.setInsuranceBaseId(planId);
        }
        log.info("Visit {} is paid by plan {} ({}) through payer {}", visitUuid, planId, plan.get("name"), payer);
    }

    /**
     * Returns the payer recorded on the visit: the uuid of the answer for a coded attribute, the value
     * otherwise. Null when the visit has none.
     */
    public String getVisitPayer(String visitUuid) {
        byte[] visit;
        try {
            visit = openmrsRestClient.get(VISIT_RESOURCE, visitUuid + VISIT_ATTRIBUTES_REPRESENTATION);
        } catch (Exception e) {
            throw new EIPException(String.format("Could not read the payer of visit %s", visitUuid), e);
        }
        if (visit == null) {
            throw new EIPException(String.format("Visit %s not found in OpenMRS", visitUuid));
        }

        JsonNode attributes;
        try {
            attributes = MAPPER.readTree(visit).path("attributes");
        } catch (Exception e) {
            throw new EIPException(String.format("Could not read the attributes of visit %s", visitUuid), e);
        }
        for (JsonNode attribute : attributes) {
            if (attribute.path("voided").asBoolean(false)
                    || !payerAttributeTypeUuid.equals(
                            attribute.path("attributeType").path("uuid").asText())) {
                continue;
            }
            JsonNode value = attribute.path("value");
            String payer = value.isObject() ? value.path("uuid").asText(null) : value.asText(null);
            if (payer != null && !payer.isBlank()) {
                return payer;
            }
        }
        return null;
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

    private Set<String> getSelfPayPayers() {
        if (selfPayPayers == null || selfPayPayers.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(selfPayPayers.split(","))
                .map(String::trim)
                .filter(payer -> !payer.isEmpty())
                .collect(Collectors.toSet());
    }
}

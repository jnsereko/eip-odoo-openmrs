/*
 * Copyright © 2021, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers.odoo;

import static java.util.Arrays.asList;

import com.ozonehis.eip.odoo.openmrs.Constants;
import com.ozonehis.eip.odoo.openmrs.client.OdooClient;
import com.ozonehis.eip.odoo.openmrs.client.OdooUtils;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.ObservationHandler;
import com.ozonehis.eip.odoo.openmrs.mapper.odoo.SaleOrderMapper;
import com.ozonehis.eip.odoo.openmrs.model.Partner;
import com.ozonehis.eip.odoo.openmrs.model.Product;
import com.ozonehis.eip.odoo.openmrs.model.SaleOrder;
import com.ozonehis.eip.odoo.openmrs.model.SaleOrderLine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.ProducerTemplate;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Resource;
import org.openmrs.eip.EIPException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Setter
@Component
public class SaleOrderHandler {

    @Value("${eip.weight.concept}")
    private String weightConcept;

    @Value("${odoo.customer.weight.field}")
    private String odooCustomerWeightField;

    @Value("${odoo.customer.dob.field}")
    private String odooCustomerDobField;

    @Value("${odoo.customer.id.field}")
    private String odooCustomerIdField;

    @Autowired
    private OdooClient odooClient;

    @Autowired
    private SaleOrderLineHandler saleOrderLineHandler;

    @Autowired
    private SaleOrderMapper saleOrderMapper;

    @Autowired
    private ProductHandler productHandler;

    @Autowired
    private ObservationHandler observationHandler;

    @Autowired
    private OdooUtils odooUtils;

    @Autowired
    private InsurancePayerHandler insurancePayerHandler;

    public List<String> orderDefaultAttributes;

    // Odoo sale order states that are still a quotation, so their lines may be changed
    private static final List<String> QUOTATION_STATES = List.of("draft", "sent");

    public SaleOrder getDraftSaleOrderIfExistsByVisitId(String visitId, Integer companyId) {
        orderDefaultAttributes = asList(
                "id",
                "client_order_ref",
                "partner_id",
                "state",
                "order_line",
                "company_id",
                odooCustomerWeightField,
                odooCustomerDobField,
                odooCustomerIdField);
        List<Object> criteria = new ArrayList<>();
        criteria.add(asList("client_order_ref", "=", visitId));
        criteria.add(asList("state", "=", "draft"));
        if (companyId != null) {
            criteria.add(asList("company_id", "=", companyId));
        }
        Object[] records = odooClient.searchAndRead(Constants.SALE_ORDER_MODEL, criteria, orderDefaultAttributes);
        if (records == null) {
            throw new EIPException(
                    String.format("Got null response while fetching for Sale order with client_order_ref %s", visitId));
        } else if (records.length == 1) {
            SaleOrder saleOrder = odooUtils.convertToObject((Map<String, Object>) records[0], SaleOrder.class);
            log.debug("Sale order exists with client_order_ref {} sale order {}", visitId, saleOrder);
            return saleOrder;
        } else if (records.length == 0) {
            log.warn("No Sale order found with client_order_ref {}", visitId);
            return null;
        } else {
            log.warn("Multiple Sale order exists with client_order_ref {}", visitId);
            throw new EIPException(String.format("Multiple Sale order found with client_order_ref %s", visitId));
        }
    }

    public void sendSaleOrder(ProducerTemplate producerTemplate, String endpointUri, SaleOrder saleOrder) {
        Map<String, Object> saleOrderHeaders = new HashMap<>();
        if (endpointUri.contains("update")) {
            saleOrderHeaders.put(Constants.HEADER_ODOO_ID_ATTRIBUTE_VALUE, List.of(saleOrder.getOrderId()));
            // Odoo returns many2one fields as [id, name] on read but requires a bare id on write.
            saleOrder.setCompanyId(toMany2oneId(saleOrder.getCompanyId()));
            saleOrder.setOrderPartnerId(toMany2oneId(saleOrder.getOrderPartnerId()));
        }
        producerTemplate.sendBodyAndHeaders(endpointUri, saleOrder, saleOrderHeaders);
    }

    public void updateSaleOrderIfExistsWithSaleOrderLine(
            Resource resource,
            SaleOrder saleOrder,
            String encounterVisitUuid,
            int partnerId,
            String patientID,
            Integer companyId,
            ProducerTemplate producerTemplate) {
        // If sale order exists create sale order line and link it to sale order
        SaleOrderLine saleOrderLine = saleOrderLineHandler.buildSaleOrderLineIfProductExists(resource, saleOrder);
        if (saleOrderLine == null) {
            log.info(
                    "{}: Skipping create sale order line for encounter Visit {}",
                    resource.getClass().getName(),
                    encounterVisitUuid);
            return;
        }
        saleOrderLine.setSaleOrderLineCompanyId(companyId);

        // Update sale order with Patient Weight if not already present
        if (saleOrder.getPartnerWeight() == null
                || saleOrder.getPartnerWeight().isEmpty()
                || saleOrder.getPartnerWeight().equals("false")) {
            updateSaleOrderWithPatientWeight(partnerId, patientID, saleOrder, producerTemplate);
        }
        producerTemplate.sendBody("direct:odoo-create-sale-order-line-route", saleOrderLine);
        log.debug(
                "{}: Created sale order line {} and linked to sale order {}",
                resource.getClass().getName(),
                saleOrderLine,
                saleOrder);
    }

    public void createSaleOrderWithSaleOrderLine(
            Resource resource,
            Encounter encounter,
            Partner partner,
            String encounterVisitUuid,
            String patientID,
            Integer companyId,
            ProducerTemplate producerTemplate) {
        // If the sale order does not exist, create it, then create sale order line and link it to sale order
        SaleOrder newSaleOrder = saleOrderMapper.toOdoo(encounter);
        newSaleOrder.setOrderPartnerId(partner.getPartnerId());
        newSaleOrder.setOrderState("draft");
        newSaleOrder.setCompanyId(companyId);
        // Add Patient DOB to Odoo Quotation
        newSaleOrder.setPartnerBirthDate(partner.getPartnerBirthDate());
        // Add Patient Id to Odoo Quotation
        newSaleOrder.setOdooCustomerId(partner.getPartnerExternalId().replaceAll("(?i)</?p>", ""));
        String patientWeight = getPartnerWeight(patientID);
        if (patientWeight != null) {
            newSaleOrder.setPartnerWeight(getPartnerWeight(patientID));
        }
        // The plans that pay for the visit, from its payer. Set once, on the new quotation: a payer changed
        // after the visit's first order is not applied to it.
        insurancePayerHandler.applyVisitPayer(newSaleOrder, encounterVisitUuid);

        sendSaleOrder(producerTemplate, "direct:odoo-create-sale-order-route", newSaleOrder);
        log.debug(
                "{}: Created sale order with partner_id {}", resource.getClass().getName(), partner.getPartnerId());

        SaleOrder fetchedSaleOrder = getDraftSaleOrderIfExistsByVisitId(encounterVisitUuid, companyId);
        if (fetchedSaleOrder != null) {
            SaleOrderLine saleOrderLine =
                    saleOrderLineHandler.buildSaleOrderLineIfProductExists(resource, fetchedSaleOrder);
            if (saleOrderLine == null) {
                log.info(
                        "{}: Skipping create sale order line and sale order for partner_id {}",
                        resource.getClass().getName(),
                        partner.getPartnerId());
                return;
            }
            saleOrderLine.setSaleOrderLineCompanyId(companyId);

            producerTemplate.sendBody("direct:odoo-create-sale-order-line-route", saleOrderLine);
            log.debug(
                    "{}: Created sale order {} and sale order line {} and linked to sale order",
                    resource.getClass().getName(),
                    fetchedSaleOrder.getOrderId(),
                    saleOrderLine);
        }
    }

    public void deleteSaleOrderLine(
            Resource resource, String encounterVisitUuid, Integer companyId, ProducerTemplate producerTemplate) {
        SaleOrder saleOrder = getDraftSaleOrderIfExistsByVisitId(encounterVisitUuid, companyId);
        if (saleOrder != null) {
            Product product = productHandler.getProduct(resource);
            if (product != null) {
                SaleOrderLine saleOrderLine = saleOrderLineHandler.getSaleOrderLineIfExists(
                        saleOrder.getOrderId(), product.getProductResId());
                if (saleOrderLine != null) {
                    saleOrderLineHandler.sendSaleOrderLine(
                            producerTemplate, "direct:odoo-delete-sale-order-line-route", saleOrderLine);
                }
            }
        }
    }

    /**
     * Takes the line of an order that was cancelled before it was carried out (discontinued, or closed by
     * the pharmacy without dispensing) off the visit's quotation, so it is not invoiced.
     *
     * <p>Only a quotation (state draft or sent) is changed. The line is unlinked; if Odoo refuses, its
     * quantity is set to 0 instead. A confirmed, locked or invoiced sale order is never touched: the line is
     * reported at WARN so that billing can correct it by hand.
     */
    public void removeSaleOrderLineFromQuotation(
            Resource resource, String encounterVisitUuid, Integer companyId, ProducerTemplate producerTemplate) {
        Product product = productHandler.getProduct(resource);
        if (product == null) {
            log.info(
                    "{} {}: no Odoo product, so no sale order line to remove for visit {}",
                    resource.fhirType(),
                    resource.getIdPart(),
                    encounterVisitUuid);
            return;
        }

        List<Object> criteria = new ArrayList<>();
        criteria.add(asList("client_order_ref", "=", encounterVisitUuid));
        criteria.add(asList("state", "!=", "cancel"));
        if (companyId != null) {
            criteria.add(asList("company_id", "=", companyId));
        }
        Object[] records =
                odooClient.searchAndRead(Constants.SALE_ORDER_MODEL, criteria, asList("id", "name", "state"));
        if (records == null) {
            throw new EIPException(String.format(
                    "Got null response while fetching for Sale order with client_order_ref %s", encounterVisitUuid));
        }

        boolean found = false;
        for (Object record : records) {
            Map<String, Object> saleOrder = (Map<String, Object>) record;
            int saleOrderId = (Integer) saleOrder.get("id");
            String state = String.valueOf(saleOrder.get("state"));
            SaleOrderLine saleOrderLine =
                    saleOrderLineHandler.getSaleOrderLineIfExists(saleOrderId, product.getProductResId());
            if (saleOrderLine == null) {
                continue;
            }
            found = true;
            if (QUOTATION_STATES.contains(state)) {
                removeQuotationLine(saleOrderLine, producerTemplate);
                log.info(
                        "{} {}: removed sale order line {} from quotation {} ({}) of visit {}",
                        resource.fhirType(),
                        resource.getIdPart(),
                        saleOrderLine.getSaleOrderLineId(),
                        saleOrder.get("name"),
                        state,
                        encounterVisitUuid);
            } else {
                log.warn(
                        "{} {} was cancelled without being carried out, but its line {} is on sale order {} in"
                                + " state {} for visit {}. Confirmed sale orders are not changed: correct the"
                                + " invoice in Odoo.",
                        resource.fhirType(),
                        resource.getIdPart(),
                        saleOrderLine.getSaleOrderLineId(),
                        saleOrder.get("name"),
                        state,
                        encounterVisitUuid);
            }
        }
        if (!found) {
            log.info(
                    "{} {}: no sale order line to remove for visit {}",
                    resource.fhirType(),
                    resource.getIdPart(),
                    encounterVisitUuid);
        }
    }

    private void removeQuotationLine(SaleOrderLine saleOrderLine, ProducerTemplate producerTemplate) {
        try {
            saleOrderLineHandler.sendSaleOrderLine(
                    producerTemplate, "direct:odoo-delete-sale-order-line-route", saleOrderLine);
        } catch (Exception e) {
            log.warn(
                    "Could not delete sale order line {}, setting its quantity to 0 instead: {}",
                    saleOrderLine.getSaleOrderLineId(),
                    e.getMessage());
            SaleOrderLine zeroQuantity = new SaleOrderLine();
            zeroQuantity.setSaleOrderLineId(saleOrderLine.getSaleOrderLineId());
            zeroQuantity.setSaleOrderLineProductUomQty(0f);
            saleOrderLineHandler.sendSaleOrderLine(
                    producerTemplate, "direct:odoo-update-sale-order-line-route", zeroQuantity);
        }
    }

    // Check if sale order has no sale order line, then cancel the sale order
    public void cancelSaleOrderWhenNoSaleOrderLine(
            int partnerId, String encounterVisitUuid, Integer companyId, ProducerTemplate producerTemplate) {
        SaleOrder saleOrder = getDraftSaleOrderIfExistsByVisitId(encounterVisitUuid, companyId);
        if (saleOrder != null
                && (saleOrder.getOrderLine() == null || saleOrder.getOrderLine().isEmpty())) {
            log.debug("SaleOrderHandler: Count of sale order line {}", saleOrder.getOrderLine());
            saleOrder.setOrderState("cancel");
            saleOrder.setOrderPartnerId((Integer) partnerId);
            sendSaleOrder(producerTemplate, "direct:odoo-update-sale-order-route", saleOrder);
        }
    }

    public void updateSaleOrderWithPatientWeight(
            int partnerId, String patientID, SaleOrder saleOrder, ProducerTemplate producerTemplate) {
        String patientWeight = getPartnerWeight(patientID);
        if (saleOrder != null && patientWeight != null) {
            log.debug("SaleOrderHandler: Update sale order with Patient weight {}", saleOrder.getOrderId());
            saleOrder.setOrderPartnerId(partnerId);
            saleOrder.setPartnerWeight(patientWeight);
            sendSaleOrder(producerTemplate, "direct:odoo-update-sale-order-route", saleOrder);
        }
    }

    public String getPartnerWeight(String patientID) {
        Observation observation = observationHandler.getObservationBySubjectIDAndConceptID(patientID, weightConcept);
        if (observation == null) {
            return null;
        }

        return observation.getValueQuantity().getValue() + " "
                + observation.getValueQuantity().getUnit();
    }

    private Object toMany2oneId(Object value) {
        if (value instanceof List<?> pair && !pair.isEmpty()) {
            return pair.get(0);
        }
        return value;
    }
}

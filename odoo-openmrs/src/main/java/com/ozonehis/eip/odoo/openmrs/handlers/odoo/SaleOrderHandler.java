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
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.OrderHandler;
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
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
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
    private OrderHandler orderHandler;

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
        Float serviceRequestQuantity = null;
        if (resource instanceof ServiceRequest serviceRequest) {
            Product product = productHandler.getProduct(resource);
            if (product != null) {
                SaleOrderLine existingLine = saleOrderLineHandler.getSaleOrderLineIfExists(
                        saleOrder.getOrderId(), product.getProductResId());
                serviceRequestQuantity = getServiceRequestQuantity(
                        serviceRequest, product, saleOrder.getOrderId(), encounterVisitUuid, companyId);
                if (existingLine != null) {
                    // A repeat of a test already on the quotation (UVL-EMR#360): one line per test, counted.
                    updateServiceRequestLineQuantity(
                            serviceRequest, existingLine, serviceRequestQuantity, saleOrder, producerTemplate);
                    return;
                }
                if (serviceRequestQuantity != null && serviceRequestQuantity <= 0) {
                    log.info(
                            "ServiceRequest {}: every order of this test in visit {} is already on a confirmed sale"
                                    + " order. No line added to quotation {}.",
                            serviceRequest.getIdPart(),
                            encounterVisitUuid,
                            saleOrder.getOrderId());
                    return;
                }
            }
        }

        if (resource instanceof MedicationRequest medicationRequest
                && isMedicationRequestBilledElsewhere(
                        medicationRequest, saleOrder.getOrderId(), encounterVisitUuid, companyId)) {
            return;
        }

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
        if (serviceRequestQuantity != null) {
            saleOrderLine.setSaleOrderLineProductUomQty(serviceRequestQuantity);
        }

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
        Float serviceRequestQuantity = null;
        if (resource instanceof ServiceRequest serviceRequest) {
            Product product = productHandler.getProduct(resource);
            if (product != null) {
                serviceRequestQuantity =
                        getServiceRequestQuantity(serviceRequest, product, null, encounterVisitUuid, companyId);
                if (serviceRequestQuantity != null && serviceRequestQuantity <= 0) {
                    log.info(
                            "ServiceRequest {}: every order of this test in visit {} is already on a confirmed sale"
                                    + " order. No quotation created.",
                            serviceRequest.getIdPart(),
                            encounterVisitUuid);
                    return;
                }
            }
        }

        if (resource instanceof MedicationRequest medicationRequest
                && isMedicationRequestBilledElsewhere(medicationRequest, null, encounterVisitUuid, companyId)) {
            return;
        }

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
            if (serviceRequestQuantity != null) {
                saleOrderLine.setSaleOrderLineProductUomQty(serviceRequestQuantity);
            }

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
                        "{} {} was stopped (discontinued, paused or closed) before it was carried out, but its"
                                + " line {} is on sale order {} in"
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

    /**
     * Lowers the quantity of a lab or procedure line on the visit's draft quotation when one of several orders
     * of that test is discontinued before it is done (UVL-EMR#360).
     *
     * @return true when the line was kept with the remaining orders' quantity; false when the caller should
     *     remove the line as before: no other order of the test is left, the line is not on the draft
     *     quotation, or the orders could not be counted
     */
    public boolean reduceServiceRequestLineQuantity(
            ServiceRequest serviceRequest,
            String encounterVisitUuid,
            Integer companyId,
            ProducerTemplate producerTemplate) {
        SaleOrder saleOrder = getDraftSaleOrderIfExistsByVisitId(encounterVisitUuid, companyId);
        if (saleOrder == null) {
            return false;
        }
        Product product = productHandler.getProduct(serviceRequest);
        if (product == null) {
            return false;
        }
        SaleOrderLine line =
                saleOrderLineHandler.getSaleOrderLineIfExists(saleOrder.getOrderId(), product.getProductResId());
        if (line == null) {
            return false;
        }
        Float quantity = getServiceRequestQuantity(
                serviceRequest, product, saleOrder.getOrderId(), encounterVisitUuid, companyId);
        if (quantity == null || quantity <= 0) {
            return false;
        }
        updateServiceRequestLineQuantity(serviceRequest, line, quantity, saleOrder, producerTemplate);
        return true;
    }

    /**
     * Whether the visit's other, non-cancelled sale orders already bill this prescription.
     *
     * <p>Every dispense, pause or other update of a MedicationRequest is processed again. Once the visit's
     * quotation is confirmed (for example the cashier takes payment for an X-ray before the pharmacy
     * dispenses), there is no draft quotation left, so without this check the update created a new quotation
     * holding the drug a second time. A drug line carries no link to its order, so lines are counted: when the
     * other sale orders hold at least as many lines for this drug's product as the visit has billable orders of
     * the drug, the prescription is billed already. A new prescription of the same drug later in the visit
     * still gets a line.
     *
     * @param quotationId the quotation being changed, which is not counted; null when it is yet to be created
     */
    boolean isMedicationRequestBilledElsewhere(
            MedicationRequest medicationRequest, Integer quotationId, String encounterVisitUuid, Integer companyId) {
        Product product = productHandler.getProduct(medicationRequest);
        if (product == null) {
            return false;
        }

        List<Object> criteria = new ArrayList<>();
        criteria.add(asList("order_id.client_order_ref", "=", encounterVisitUuid));
        criteria.add(asList("order_id.state", "!=", "cancel"));
        criteria.add(asList("product_id", "=", product.getProductResId()));
        if (companyId != null) {
            criteria.add(asList("order_id.company_id", "=", companyId));
        }
        Object[] lines = odooClient.searchAndRead(Constants.SALE_ORDER_LINE_MODEL, criteria, asList("id", "order_id"));
        if (lines == null) {
            throw new EIPException(String.format(
                    "Got null response while fetching sale order lines of product %s for visit %s",
                    product.getProductResId(), encounterVisitUuid));
        }
        int linesElsewhere = 0;
        for (Object record : lines) {
            Object orderId = ((Map<String, Object>) record).get("order_id");
            if (orderId instanceof Object[] pair && pair.length > 0) {
                // XML-RPC returns a many2one as [id, name]
                orderId = pair[0];
            }
            if (quotationId == null || !quotationId.equals(orderId)) {
                linesElsewhere++;
            }
        }
        if (linesElsewhere == 0) {
            return false;
        }

        Integer billableOrders;
        try {
            billableOrders = orderHandler.countBillableOrdersInVisit(medicationRequest.getIdPart());
        } catch (Exception e) {
            log.warn(
                    "MedicationRequest {}: could not count the orders of this drug in visit {}: {}",
                    medicationRequest.getIdPart(),
                    encounterVisitUuid,
                    e.getMessage());
            billableOrders = null;
        }
        if (billableOrders != null && billableOrders > linesElsewhere) {
            return false;
        }
        // Billing a drug twice is worse than missing a repeat, so a count that cannot be read counts as billed.
        log.info(
                "MedicationRequest {}: other sale orders of visit {} already hold {} line(s) for this drug ({}"
                        + " billable order(s)). No line added.",
                medicationRequest.getIdPart(),
                encounterVisitUuid,
                linesElsewhere,
                billableOrders == null ? "unknown" : billableOrders);
        return true;
    }

    /**
     * The quantity a lab or procedure line on the visit's quotation should carry (UVL-EMR#360): the billable
     * orders of that test in the visit (see {@link OrderHandler#countBillableOrdersInVisit}), less what the
     * visit's other, non-cancelled sale orders already bill for it. Without the subtraction, a repeat ordered
     * after the first quotation was confirmed would bill the first order a second time.
     *
     * @param quotationId the quotation being changed, which is not subtracted; null when it is yet to be created
     * @return the quantity, possibly 0 or less when confirmed sale orders already cover every order; or null
     *     when the orders cannot be counted, in which case the caller keeps the one-line-per-test behaviour
     */
    Float getServiceRequestQuantity(
            ServiceRequest serviceRequest,
            Product product,
            Integer quotationId,
            String encounterVisitUuid,
            Integer companyId) {
        Integer billableOrders;
        try {
            billableOrders = orderHandler.countBillableOrdersInVisit(serviceRequest.getIdPart());
        } catch (Exception e) {
            log.warn(
                    "ServiceRequest {}: could not count the orders of this test in visit {}, so its quantity is not"
                            + " changed: {}",
                    serviceRequest.getIdPart(),
                    encounterVisitUuid,
                    e.getMessage());
            return null;
        }
        if (billableOrders == null) {
            return null;
        }

        List<Object> criteria = new ArrayList<>();
        criteria.add(asList("order_id.client_order_ref", "=", encounterVisitUuid));
        criteria.add(asList("order_id.state", "!=", "cancel"));
        criteria.add(asList("product_id", "=", product.getProductResId()));
        if (companyId != null) {
            criteria.add(asList("order_id.company_id", "=", companyId));
        }
        Object[] lines = odooClient.searchAndRead(
                Constants.SALE_ORDER_LINE_MODEL, criteria, asList("id", "order_id", "product_uom_qty"));
        if (lines == null) {
            throw new EIPException(String.format(
                    "Got null response while fetching sale order lines of product %s for visit %s",
                    product.getProductResId(), encounterVisitUuid));
        }
        float billedElsewhere = 0;
        for (Object record : lines) {
            Map<String, Object> line = (Map<String, Object>) record;
            Object orderId = line.get("order_id");
            if (orderId instanceof Object[] pair && pair.length > 0) {
                // XML-RPC returns a many2one as [id, name]
                orderId = pair[0];
            }
            if (quotationId != null && quotationId.equals(orderId)) {
                continue;
            }
            Object quantity = line.get("product_uom_qty");
            if (quantity instanceof Number number) {
                billedElsewhere += number.floatValue();
            }
        }
        return billableOrders - billedElsewhere;
    }

    private void updateServiceRequestLineQuantity(
            ServiceRequest serviceRequest,
            SaleOrderLine line,
            Float quantity,
            SaleOrder saleOrder,
            ProducerTemplate producerTemplate) {
        if (quantity == null) {
            log.info(
                    "ServiceRequest {}: test already on quotation {} (line {}). Its quantity is not changed.",
                    serviceRequest.getIdPart(),
                    saleOrder.getOrderId(),
                    line.getSaleOrderLineId());
            return;
        }
        if (quantity <= 0) {
            log.warn(
                    "ServiceRequest {}: quotation {} has line {} for a test that confirmed sale orders of the same"
                            + " visit already bill in full. The line is left as it is: check it in Odoo.",
                    serviceRequest.getIdPart(),
                    saleOrder.getOrderId(),
                    line.getSaleOrderLineId());
            return;
        }
        if (quantity.equals(line.getSaleOrderLineProductUomQty())) {
            log.debug(
                    "ServiceRequest {}: line {} already has quantity {}",
                    serviceRequest.getIdPart(),
                    line.getSaleOrderLineId(),
                    quantity);
            return;
        }
        SaleOrderLine update = new SaleOrderLine();
        update.setSaleOrderLineId(line.getSaleOrderLineId());
        update.setSaleOrderLineProductUomQty(quantity);
        saleOrderLineHandler.sendSaleOrderLine(producerTemplate, "direct:odoo-update-sale-order-line-route", update);
        log.info(
                "ServiceRequest {}: quantity of line {} on quotation {} set from {} to {}, the orders of this test"
                        + " in the visit",
                serviceRequest.getIdPart(),
                line.getSaleOrderLineId(),
                saleOrder.getOrderId(),
                line.getSaleOrderLineProductUomQty(),
                quantity);
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

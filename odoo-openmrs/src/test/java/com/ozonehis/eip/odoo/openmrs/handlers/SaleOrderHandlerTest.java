/*
 * Copyright © 2021, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.ozonehis.eip.odoo.openmrs.Constants;
import com.ozonehis.eip.odoo.openmrs.client.OdooClient;
import com.ozonehis.eip.odoo.openmrs.client.OdooUtils;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.ProductHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.SaleOrderHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.SaleOrderLineHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.ObservationHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.OrderHandler;
import com.ozonehis.eip.odoo.openmrs.mapper.odoo.SaleOrderMapper;
import com.ozonehis.eip.odoo.openmrs.model.Partner;
import com.ozonehis.eip.odoo.openmrs.model.Product;
import com.ozonehis.eip.odoo.openmrs.model.SaleOrder;
import com.ozonehis.eip.odoo.openmrs.model.SaleOrderLine;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.ProducerTemplate;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.openmrs.eip.EIPException;
import org.springframework.core.env.Environment;

class SaleOrderHandlerTest {

    @Mock
    private OdooClient odooClient;

    @Mock
    private SaleOrderLineHandler saleOrderLineHandler;

    @Mock
    private SaleOrderMapper saleOrderMapper;

    @Mock
    private ProductHandler productHandler;

    @Mock
    private ObservationHandler observationHandler;

    @Mock
    private OrderHandler orderHandler;

    private OdooUtils odooUtils;

    @InjectMocks
    private SaleOrderHandler saleOrderHandler;

    private static AutoCloseable mocksCloser;

    private static final String VISIT_ID_1 = "e5ca6578-fb37-4900-a054-c68db82a551c";

    private static final String OBSERVATION_ID = "h4ca6578-db37-2910-c055-c90db82a919c";

    private static final String PATIENT_ID = "patient-id-987";

    private static final int PARTNER_ID = 12;

    private static final String PATIENT_IDENTIFIER = "10000Y";

    @AfterAll
    public static void close() throws Exception {
        mocksCloser.close();
    }

    @BeforeEach
    public void setup() {
        mocksCloser = openMocks(this);
        Environment mockEnvironment = Mockito.mock(Environment.class);
        when(mockEnvironment.getProperty("odoo.customer.weight.field")).thenReturn("x_customer_weight");
        when(mockEnvironment.getProperty("odoo.customer.id.field")).thenReturn("x_external_identifier");
        odooUtils = new OdooUtils();
        odooUtils.setEnvironment(mockEnvironment);
        saleOrderHandler.setOdooUtils(odooUtils);
    }

    @Test
    public void shouldReturnSaleOrderWhenOnlyOneSaleOrderExistsWithVisitId() {
        // Setup
        Map<String, Object> saleOrderMap = getSaleOrderMap(1, VISIT_ID_1, "draft", 12);
        Object[] saleOrders = {saleOrderMap};

        // Mock behavior
        when(odooClient.searchAndRead(
                        eq(Constants.SALE_ORDER_MODEL),
                        eq(List.of(asList("client_order_ref", "=", VISIT_ID_1), asList("state", "=", "draft"))),
                        any()))
                .thenReturn(saleOrders);

        // Act
        SaleOrder result = saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(VISIT_ID_1, null);

        // Verify
        assertNotNull(result);
        assertEquals(VISIT_ID_1, result.getOrderClientOrderRef());
        assertEquals(1, result.getOrderId());
        assertEquals("draft", result.getOrderState());
        assertEquals(12, result.getOrderPartnerId());
    }

    @Test
    public void shouldThrowErrorWhenMultipleSaleOrderExistsWithSameVisitId() {
        // Setup
        Map<String, Object> saleOrderMap1 = getSaleOrderMap(1, VISIT_ID_1, "draft", 12);
        Map<String, Object> saleOrderMap2 = getSaleOrderMap(2, VISIT_ID_1, "draft", 15);

        Object[] saleOrders = {saleOrderMap1, saleOrderMap2};

        // Mock behavior
        when(odooClient.searchAndRead(
                        eq(Constants.SALE_ORDER_MODEL),
                        eq(List.of(asList("client_order_ref", "=", VISIT_ID_1), asList("state", "=", "draft"))),
                        any()))
                .thenReturn(saleOrders);

        // Verify
        assertThrows(EIPException.class, () -> saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(VISIT_ID_1, null));
    }

    @Test
    public void shouldReturnNullWhenNoSaleOrderExistsWithVisitId() {
        // Setup
        Object[] saleOrders = {};

        // Mock behavior
        when(odooClient.searchAndRead(
                        eq(Constants.SALE_ORDER_MODEL),
                        eq(List.of(asList("client_order_ref", "=", VISIT_ID_1), asList("state", "=", "draft"))),
                        any()))
                .thenReturn(saleOrders);

        // Act
        SaleOrder result = saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(VISIT_ID_1, null);

        // Verify
        assertNull(result);
    }

    @Test
    public void shouldThrowErrorWhenNullResponseFromClient() {
        // Mock behavior
        when(odooClient.searchAndRead(
                        eq(Constants.SALE_ORDER_MODEL),
                        eq(List.of(asList("client_order_ref", "=", VISIT_ID_1), asList("state", "=", "draft"))),
                        any()))
                .thenReturn(null);

        // Verify
        assertThrows(EIPException.class, () -> saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(VISIT_ID_1, null));
    }

    @Test
    public void shouldUpdateSaleOrderWithSaleOrderLine() {
        // Setup
        SaleOrderLine saleOrderLine = new SaleOrderLine();
        SaleOrder saleOrder = new SaleOrder();
        Resource resource = new MedicationRequest();

        // Mock behaviour
        when(saleOrderLineHandler.buildSaleOrderLineIfProductExists(resource, saleOrder))
                .thenReturn(saleOrderLine);
        when(observationHandler.getObservationBySubjectIDAndConceptID(eq(PATIENT_ID), any()))
                .thenReturn(null);
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        // Act
        saleOrderHandler.updateSaleOrderIfExistsWithSaleOrderLine(
                resource, saleOrder, VISIT_ID_1, PARTNER_ID, PATIENT_ID, null, producerTemplate);

        // Verify
        verify(producerTemplate, times(1)).sendBody("direct:odoo-create-sale-order-line-route", saleOrderLine);
    }

    @Test
    public void shouldCreateSaleOrderWithSaleOrderLine() {
        // Setup
        int partnerId = 12;
        SaleOrderLine saleOrderLine = new SaleOrderLine();
        Encounter encounter = new Encounter();
        SaleOrder saleOrder = getSaleOrder();
        Resource resource = new MedicationRequest();
        Map<String, Object> saleOrderMap = getSaleOrderMap(1, VISIT_ID_1, "draft", 12);

        // Mock behaviour
        when(saleOrderMapper.toOdoo(encounter)).thenReturn(saleOrder);
        when(odooClient.searchAndRead(
                        eq(Constants.SALE_ORDER_MODEL),
                        eq(List.of(asList("client_order_ref", "=", VISIT_ID_1), asList("state", "=", "draft"))),
                        any()))
                .thenReturn(new Object[] {saleOrderMap});
        when(saleOrderLineHandler.buildSaleOrderLineIfProductExists(any(), any()))
                .thenReturn(saleOrderLine);
        when(observationHandler.getObservationBySubjectIDAndConceptID(eq(PATIENT_ID), any()))
                .thenReturn(null);
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        Partner partner = new Partner();
        partner.setPartnerId(PARTNER_ID);
        partner.setPartnerComment(PATIENT_IDENTIFIER);
        partner.setPartnerExternalId(PATIENT_IDENTIFIER);

        // Act
        saleOrderHandler.createSaleOrderWithSaleOrderLine(
                resource, encounter, partner, VISIT_ID_1, PATIENT_ID, null, producerTemplate);

        // Verify
        verify(producerTemplate, times(1))
                .sendBodyAndHeaders("direct:odoo-create-sale-order-route", saleOrder, new HashMap<>());
        verify(producerTemplate, times(1)).sendBody("direct:odoo-create-sale-order-line-route", saleOrderLine);
    }

    @Test
    public void shouldDeleteSaleOrderLine() {
        // Setup
        SaleOrderLine saleOrderLine = new SaleOrderLine();
        Product product = new Product();
        product.setProductResId(123);
        SaleOrder saleOrder = getSaleOrder();
        Resource resource = new MedicationRequest();
        Map<String, Object> saleOrderMap = getSaleOrderMap(1, VISIT_ID_1, "draft", 12);

        // Mock behaviour
        when(odooClient.searchAndRead(
                        eq(Constants.SALE_ORDER_MODEL),
                        eq(List.of(asList("client_order_ref", "=", VISIT_ID_1), asList("state", "=", "draft"))),
                        any()))
                .thenReturn(new Object[] {saleOrderMap});
        when(productHandler.getProduct(resource)).thenReturn(product);
        when(saleOrderLineHandler.getSaleOrderLineIfExists(saleOrder.getOrderId(), product.getProductResId()))
                .thenReturn(saleOrderLine);
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        // Act
        saleOrderHandler.deleteSaleOrderLine(resource, VISIT_ID_1, null, producerTemplate);

        // Verify
        verify(saleOrderLineHandler, times(1))
                .sendSaleOrderLine(producerTemplate, "direct:odoo-delete-sale-order-line-route", saleOrderLine);
    }

    @Test
    public void shouldCancelSaleOrderWhenNoSaleOrderLine() {
        // Setup
        int partnerId = 12;
        Product product = new Product();
        product.setProductResId(123);
        SaleOrder saleOrder = getSaleOrder();
        saleOrder.setOrderState("cancel");
        Map<String, Object> saleOrderMap = getSaleOrderMap(1, VISIT_ID_1, "draft", 12);
        Map<String, Object> saleOrderHeaders = new HashMap<>();
        saleOrderHeaders.put(Constants.HEADER_ODOO_ID_ATTRIBUTE_VALUE, List.of(saleOrder.getOrderId()));

        // Mock behaviour
        when(odooClient.searchAndRead(
                        eq(Constants.SALE_ORDER_MODEL),
                        eq(List.of(asList("client_order_ref", "=", VISIT_ID_1), asList("state", "=", "draft"))),
                        any()))
                .thenReturn(new Object[] {saleOrderMap});
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        // Act
        saleOrderHandler.cancelSaleOrderWhenNoSaleOrderLine(partnerId, VISIT_ID_1, null, producerTemplate);

        // Verify
        verify(producerTemplate, times(1))
                .sendBodyAndHeaders("direct:odoo-update-sale-order-route", saleOrder, saleOrderHeaders);
    }

    public Map<String, Object> getSaleOrderMap(int id, String clientOrderRef, String state, int partnerId) {
        Map<String, Object> saleOrderMap = new HashMap<>();
        saleOrderMap.put("id", id);
        saleOrderMap.put("client_order_ref", clientOrderRef);
        saleOrderMap.put("state", state);
        saleOrderMap.put("partner_id", partnerId);
        return saleOrderMap;
    }

    private SaleOrder getSaleOrder() {
        SaleOrder saleOrder = new SaleOrder();
        saleOrder.setOrderId(1);
        saleOrder.setOrderClientOrderRef(VISIT_ID_1);
        saleOrder.setOrderState("draft");
        saleOrder.setOrderPartnerId(12);
        return saleOrder;
    }

    @Test
    void shouldReturnPatientWeightGivenPatientID() {
        // Setup
        Observation observation = new Observation();
        observation.setId(OBSERVATION_ID);
        observation.setValue(new Quantity().setValue(67).setUnit("Kg"));

        // Mock
        when(observationHandler.getObservationBySubjectIDAndConceptID(eq(PATIENT_ID), any()))
                .thenReturn(observation);

        // Act
        String result = saleOrderHandler.getPartnerWeight(PATIENT_ID);

        // Assert
        assertEquals("67 Kg", result);
    }

    @Test
    void shouldReturnNullWhenObservationIsNull() {
        // Mock
        when(observationHandler.getObservationBySubjectIDAndConceptID(eq(PATIENT_ID), any()))
                .thenReturn(null);

        // Act
        String result = saleOrderHandler.getPartnerWeight(PATIENT_ID);

        // Assert
        assertNull(result);
    }

    // UVL-EMR #386: a discontinued order's line comes off a quotation that is still a draft
    @Test
    public void shouldRemoveSaleOrderLineFromDraftQuotation() {
        // Setup
        Resource resource = new ServiceRequest();
        Product product = new Product();
        product.setProductResId(123);
        SaleOrderLine saleOrderLine = new SaleOrderLine();
        saleOrderLine.setSaleOrderLineId(6300);
        mockVisitSaleOrders(resource, product, Map.of("id", 1, "name", "S00001", "state", "draft"));
        when(saleOrderLineHandler.getSaleOrderLineIfExists(1, 123)).thenReturn(saleOrderLine);
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        // Act
        saleOrderHandler.removeSaleOrderLineFromQuotation(resource, VISIT_ID_1, null, producerTemplate);

        // Verify
        verify(saleOrderLineHandler, times(1))
                .sendSaleOrderLine(producerTemplate, "direct:odoo-delete-sale-order-line-route", saleOrderLine);
    }

    @Test
    public void shouldRemoveSaleOrderLineFromSentQuotation() {
        // Setup
        Resource resource = new ServiceRequest();
        Product product = new Product();
        product.setProductResId(123);
        SaleOrderLine saleOrderLine = new SaleOrderLine();
        saleOrderLine.setSaleOrderLineId(6300);
        mockVisitSaleOrders(resource, product, Map.of("id", 1, "name", "S00001", "state", "sent"));
        when(saleOrderLineHandler.getSaleOrderLineIfExists(1, 123)).thenReturn(saleOrderLine);
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        // Act
        saleOrderHandler.removeSaleOrderLineFromQuotation(resource, VISIT_ID_1, null, producerTemplate);

        // Verify
        verify(saleOrderLineHandler, times(1))
                .sendSaleOrderLine(producerTemplate, "direct:odoo-delete-sale-order-line-route", saleOrderLine);
    }

    @Test
    public void shouldNeverChangeAConfirmedSaleOrder() {
        // Setup
        Resource resource = new ServiceRequest();
        Product product = new Product();
        product.setProductResId(123);
        SaleOrderLine saleOrderLine = new SaleOrderLine();
        saleOrderLine.setSaleOrderLineId(6305);
        mockVisitSaleOrders(resource, product, Map.of("id", 2, "name", "S00002", "state", "sale"));
        when(saleOrderLineHandler.getSaleOrderLineIfExists(2, 123)).thenReturn(saleOrderLine);
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        // Act
        saleOrderHandler.removeSaleOrderLineFromQuotation(resource, VISIT_ID_1, null, producerTemplate);

        // Verify
        verify(saleOrderLineHandler, never()).sendSaleOrderLine(any(), any(), any());
        Mockito.verifyNoInteractions(producerTemplate);
    }

    @Test
    public void shouldSetQuantityToZeroWhenTheQuotationLineCannotBeDeleted() {
        // Setup
        Resource resource = new ServiceRequest();
        Product product = new Product();
        product.setProductResId(123);
        SaleOrderLine saleOrderLine = new SaleOrderLine();
        saleOrderLine.setSaleOrderLineId(6300);
        mockVisitSaleOrders(resource, product, Map.of("id", 1, "name", "S00001", "state", "draft"));
        when(saleOrderLineHandler.getSaleOrderLineIfExists(1, 123)).thenReturn(saleOrderLine);
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);
        doThrow(new RuntimeException("unlink refused"))
                .when(saleOrderLineHandler)
                .sendSaleOrderLine(producerTemplate, "direct:odoo-delete-sale-order-line-route", saleOrderLine);

        // Act
        saleOrderHandler.removeSaleOrderLineFromQuotation(resource, VISIT_ID_1, null, producerTemplate);

        // Verify
        SaleOrderLine zeroQuantity = new SaleOrderLine();
        zeroQuantity.setSaleOrderLineId(6300);
        zeroQuantity.setSaleOrderLineProductUomQty(0f);
        verify(saleOrderLineHandler, times(1))
                .sendSaleOrderLine(producerTemplate, "direct:odoo-update-sale-order-line-route", zeroQuantity);
    }

    @Test
    public void shouldOnlyTouchTheQuotationWhenTheVisitAlsoHasAConfirmedSaleOrder() {
        // Setup
        Resource resource = new ServiceRequest();
        Product product = new Product();
        product.setProductResId(123);
        SaleOrderLine quotationLine = new SaleOrderLine();
        quotationLine.setSaleOrderLineId(7001);
        mockVisitSaleOrders(
                resource,
                product,
                Map.of("id", 1, "name", "S00001", "state", "sale"),
                Map.of("id", 2, "name", "S00002", "state", "draft"));
        when(saleOrderLineHandler.getSaleOrderLineIfExists(1, 123)).thenReturn(null);
        when(saleOrderLineHandler.getSaleOrderLineIfExists(2, 123)).thenReturn(quotationLine);
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        // Act
        saleOrderHandler.removeSaleOrderLineFromQuotation(resource, VISIT_ID_1, null, producerTemplate);

        // Verify
        verify(saleOrderLineHandler, times(1))
                .sendSaleOrderLine(producerTemplate, "direct:odoo-delete-sale-order-line-route", quotationLine);
    }

    // UVL-EMR #360: a second order of the same test on the same visit raises the line's quantity
    @Test
    public void shouldCountARepeatOfATestAlreadyOnTheQuotation() throws Exception {
        // Setup
        ServiceRequest repeat = serviceRequest("repeat-order");
        SaleOrder quotation = quotation(1);
        SaleOrderLine line = quotationLine(6300, 1f);
        mockTestLine(repeat, 1, line);
        when(orderHandler.countBillableOrdersInVisit("repeat-order")).thenReturn(2);
        mockVisitLinesOfTest(visitLine(6300, 1, 1.0));
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        // Act
        saleOrderHandler.updateSaleOrderIfExistsWithSaleOrderLine(
                repeat, quotation, VISIT_ID_1, PARTNER_ID, PATIENT_ID, null, producerTemplate);

        // Verify
        SaleOrderLine update = new SaleOrderLine();
        update.setSaleOrderLineId(6300);
        update.setSaleOrderLineProductUomQty(2f);
        verify(saleOrderLineHandler, times(1))
                .sendSaleOrderLine(producerTemplate, "direct:odoo-update-sale-order-line-route", update);
        verify(producerTemplate, never()).sendBody(eq("direct:odoo-create-sale-order-line-route"), any());
    }

    // The same order's create and update events, or a replay, must not bill it twice
    @Test
    public void shouldNotChangeTheQuantityWhenTheSameOrderIsProcessedAgain() throws Exception {
        // Setup
        ServiceRequest order = serviceRequest("first-order");
        mockTestLine(order, 1, quotationLine(6300, 2f));
        when(orderHandler.countBillableOrdersInVisit("first-order")).thenReturn(2);
        mockVisitLinesOfTest(visitLine(6300, 1, 2.0));
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        // Act
        saleOrderHandler.updateSaleOrderIfExistsWithSaleOrderLine(
                order, quotation(1), VISIT_ID_1, PARTNER_ID, PATIENT_ID, null, producerTemplate);

        // Verify
        verify(saleOrderLineHandler, never()).sendSaleOrderLine(any(), any(), any());
        Mockito.verifyNoInteractions(producerTemplate);
    }

    // The first order is on a confirmed sale order: the new quotation bills only the repeat
    @Test
    public void shouldNotBillAgainWhatAConfirmedSaleOrderOfTheVisitAlreadyBills() throws Exception {
        // Setup
        ServiceRequest repeat = serviceRequest("repeat-order");
        SaleOrder quotation = quotation(2);
        mockTestLine(repeat, 2, null);
        when(orderHandler.countBillableOrdersInVisit("repeat-order")).thenReturn(2);
        mockVisitLinesOfTest(visitLine(6300, 1, 1.0));
        SaleOrderLine newLine = new SaleOrderLine();
        newLine.setSaleOrderLineProductUomQty(1f);
        when(saleOrderLineHandler.buildSaleOrderLineIfProductExists(repeat, quotation))
                .thenReturn(newLine);
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        // Act
        saleOrderHandler.updateSaleOrderIfExistsWithSaleOrderLine(
                repeat, quotation, VISIT_ID_1, PARTNER_ID, PATIENT_ID, null, producerTemplate);

        // Verify
        verify(producerTemplate, times(1)).sendBody("direct:odoo-create-sale-order-line-route", newLine);
        assertEquals(1f, newLine.getSaleOrderLineProductUomQty());
    }

    @Test
    public void shouldAddNoLineWhenConfirmedSaleOrdersAlreadyBillEveryOrderOfTheTest() throws Exception {
        // Setup
        ServiceRequest order = serviceRequest("first-order");
        SaleOrder quotation = quotation(2);
        mockTestLine(order, 2, null);
        when(orderHandler.countBillableOrdersInVisit("first-order")).thenReturn(1);
        mockVisitLinesOfTest(visitLine(6300, 1, 1.0));
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        // Act
        saleOrderHandler.updateSaleOrderIfExistsWithSaleOrderLine(
                order, quotation, VISIT_ID_1, PARTNER_ID, PATIENT_ID, null, producerTemplate);

        // Verify
        verify(saleOrderLineHandler, never()).buildSaleOrderLineIfProductExists(any(), any());
        Mockito.verifyNoInteractions(producerTemplate);
    }

    // If OpenMRS cannot be asked, the line is left as it was before #360: one per test, unchanged
    @Test
    public void shouldLeaveTheQuantityAloneWhenTheOrdersCannotBeCounted() throws Exception {
        // Setup
        ServiceRequest repeat = serviceRequest("repeat-order");
        mockTestLine(repeat, 1, quotationLine(6300, 1f));
        when(orderHandler.countBillableOrdersInVisit("repeat-order")).thenThrow(new Exception("OpenMRS down"));
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        // Act
        saleOrderHandler.updateSaleOrderIfExistsWithSaleOrderLine(
                repeat, quotation(1), VISIT_ID_1, PARTNER_ID, PATIENT_ID, null, producerTemplate);

        // Verify
        verify(saleOrderLineHandler, never()).sendSaleOrderLine(any(), any(), any());
        Mockito.verifyNoInteractions(producerTemplate);
    }

    // UVL-EMR #360: one of two orders of the same test is discontinued before it is done
    @Test
    public void shouldLowerTheQuantityWhenOneOfTwoOrdersIsDiscontinued() throws Exception {
        // Setup
        ServiceRequest cancelled = serviceRequest("cancelled-order");
        mockDraftQuotation(1);
        mockTestLine(cancelled, 1, quotationLine(6300, 2f));
        when(orderHandler.countBillableOrdersInVisit("cancelled-order")).thenReturn(1);
        mockVisitLinesOfTest(visitLine(6300, 1, 2.0));
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        // Act
        boolean kept =
                saleOrderHandler.reduceServiceRequestLineQuantity(cancelled, VISIT_ID_1, null, producerTemplate);

        // Verify
        assertTrue(kept);
        SaleOrderLine update = new SaleOrderLine();
        update.setSaleOrderLineId(6300);
        update.setSaleOrderLineProductUomQty(1f);
        verify(saleOrderLineHandler, times(1))
                .sendSaleOrderLine(producerTemplate, "direct:odoo-update-sale-order-line-route", update);
    }

    @Test
    public void shouldLeaveTheRemovalToTheCallerWhenNoOtherOrderOfTheTestRemains() throws Exception {
        // Setup
        ServiceRequest cancelled = serviceRequest("cancelled-order");
        mockDraftQuotation(1);
        mockTestLine(cancelled, 1, quotationLine(6300, 1f));
        when(orderHandler.countBillableOrdersInVisit("cancelled-order")).thenReturn(0);
        mockVisitLinesOfTest(visitLine(6300, 1, 1.0));
        ProducerTemplate producerTemplate = Mockito.mock(ProducerTemplate.class);

        // Act
        boolean kept =
                saleOrderHandler.reduceServiceRequestLineQuantity(cancelled, VISIT_ID_1, null, producerTemplate);

        // Verify
        assertFalse(kept);
        verify(saleOrderLineHandler, never()).sendSaleOrderLine(any(), any(), any());
    }

    private static ServiceRequest serviceRequest(String id) {
        ServiceRequest serviceRequest = new ServiceRequest();
        serviceRequest.setId(id);
        return serviceRequest;
    }

    private static SaleOrder quotation(int id) {
        SaleOrder saleOrder = new SaleOrder();
        saleOrder.setOrderId(id);
        saleOrder.setPartnerWeight("70 kg");
        return saleOrder;
    }

    private static SaleOrderLine quotationLine(int id, float quantity) {
        SaleOrderLine line = new SaleOrderLine();
        line.setSaleOrderLineId(id);
        line.setSaleOrderLineProductUomQty(quantity);
        return line;
    }

    private static Map<String, Object> visitLine(int id, int saleOrderId, double quantity) {
        return Map.of("id", id, "order_id", new Object[] {saleOrderId, "S0000" + saleOrderId}, "product_uom_qty", quantity);
    }

    private void mockTestLine(ServiceRequest serviceRequest, int quotationId, SaleOrderLine line) {
        Product product = new Product();
        product.setProductResId(123);
        when(productHandler.getProduct(serviceRequest)).thenReturn(product);
        when(saleOrderLineHandler.getSaleOrderLineIfExists(quotationId, 123)).thenReturn(line);
    }

    private void mockDraftQuotation(int id) {
        when(odooClient.searchAndRead(
                        eq(Constants.SALE_ORDER_MODEL),
                        eq(List.of(asList("client_order_ref", "=", VISIT_ID_1), asList("state", "=", "draft"))),
                        any()))
                .thenReturn(new Object[] {getSaleOrderMap(id, VISIT_ID_1, "draft", PARTNER_ID)});
    }

    @SafeVarargs
    private void mockVisitLinesOfTest(Map<String, Object>... lines) {
        when(odooClient.searchAndRead(
                        eq(Constants.SALE_ORDER_LINE_MODEL),
                        eq(List.of(
                                asList("order_id.client_order_ref", "=", VISIT_ID_1),
                                asList("order_id.state", "!=", "cancel"),
                                asList("product_id", "=", 123))),
                        any()))
                .thenReturn(lines);
    }

    @SafeVarargs
    private void mockVisitSaleOrders(Resource resource, Product product, Map<String, Object>... saleOrders) {
        when(productHandler.getProduct(resource)).thenReturn(product);
        when(odooClient.searchAndRead(
                        eq(Constants.SALE_ORDER_MODEL),
                        eq(List.of(asList("client_order_ref", "=", VISIT_ID_1), asList("state", "!=", "cancel"))),
                        any()))
                .thenReturn(saleOrders);
    }
}

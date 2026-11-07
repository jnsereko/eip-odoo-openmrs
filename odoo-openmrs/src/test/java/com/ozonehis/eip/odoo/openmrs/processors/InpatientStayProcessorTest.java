/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.processors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.ozonehis.eip.odoo.openmrs.handlers.odoo.CompanyHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.PartnerHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.ProductHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.SaleOrderHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.EncounterHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.InpatientStayHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.InpatientStayHandler.InpatientStay;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.PatientHandler;
import com.ozonehis.eip.odoo.openmrs.model.Partner;
import com.ozonehis.eip.odoo.openmrs.model.Product;
import com.ozonehis.eip.odoo.openmrs.model.SaleOrder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class InpatientStayProcessorTest {

    private static final String VISIT = "visit-uuid";

    private static final String COMMUNE = "commune-product";

    private static final String VIP = "vip-product";

    @Mock
    private InpatientStayHandler inpatientStayHandler;

    @Mock
    private SaleOrderHandler saleOrderHandler;

    @Mock
    private ProductHandler productHandler;

    @Mock
    private PartnerHandler partnerHandler;

    @Mock
    private PatientHandler patientHandler;

    @Mock
    private EncounterHandler encounterHandler;

    @Mock
    private CompanyHandler companyHandler;

    @InjectMocks
    private InpatientStayProcessor processor;

    private AutoCloseable mocks;

    private ProducerTemplate producerTemplate;

    private Product commune;

    private Product vip;

    @BeforeEach
    void setUp() {
        mocks = openMocks(this);
        producerTemplate = mock(ProducerTemplate.class);
        commune = product(558);
        vip = product(559);
        when(productHandler.getProductByExternalId(COMMUNE)).thenReturn(commune);
        when(productHandler.getProductByExternalId(VIP)).thenReturn(vip);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    private static Product product(int resId) {
        Product product = new Product();
        product.setProductResId(resId);
        return product;
    }

    private static InpatientStay stay(int communeNights, int vipNights) {
        Map<String, Integer> nights = new LinkedHashMap<>();
        nights.put(COMMUNE, communeNights);
        nights.put(VIP, vipNights);
        return new InpatientStay(VISIT, "patient-uuid", "admission-uuid", nights);
    }

    @Test
    void existingQuotation_getsOneLinePerProduct_includingZeroToRemove() {
        SaleOrder quotation = new SaleOrder();
        quotation.setOrderId(7);
        when(saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(VISIT, null)).thenReturn(quotation);
        when(saleOrderHandler.getStayNightsToBill(commune, 2, 7, VISIT, null)).thenReturn(2f);
        when(saleOrderHandler.getStayNightsToBill(vip, 0, 7, VISIT, null)).thenReturn(0f);

        processor.bill(stay(2, 0), new HashMap<>(), producerTemplate);

        verify(saleOrderHandler).upsertStayLine(quotation, commune, 2f, null, producerTemplate);
        verify(saleOrderHandler).upsertStayLine(quotation, vip, 0f, null, producerTemplate);
        verify(saleOrderHandler, never()).createStayQuotation(any(), any(), any(), any(), any(), any());
    }

    @Test
    void noQuotation_createsOne_asAnOrderWould() {
        SaleOrder created = new SaleOrder();
        created.setOrderId(9);
        when(saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(VISIT, null)).thenReturn(null);
        when(saleOrderHandler.getStayNightsToBill(eq(commune), eq(1), isNull(), eq(VISIT), isNull()))
                .thenReturn(1f);
        when(saleOrderHandler.getStayNightsToBill(eq(vip), eq(0), any(), eq(VISIT), isNull()))
                .thenReturn(0f);
        Encounter admission = new Encounter();
        admission.setPartOf(new Reference("Encounter/" + VISIT));
        when(encounterHandler.getEncounterByEncounterID("admission-uuid")).thenReturn(admission);
        Patient patient = new Patient();
        patient.setId("patient-uuid");
        when(patientHandler.getPatientByPatientID("patient-uuid")).thenReturn(patient);
        Partner partner = new Partner();
        when(partnerHandler.createOrUpdatePartner(producerTemplate, patient, null))
                .thenReturn(partner);
        when(saleOrderHandler.createStayQuotation(admission, partner, VISIT, "patient-uuid", null, producerTemplate))
                .thenReturn(created);

        processor.bill(stay(1, 0), new HashMap<>(), producerTemplate);

        verify(saleOrderHandler).upsertStayLine(created, commune, 1f, null, producerTemplate);
        verify(saleOrderHandler).upsertStayLine(created, vip, 0f, null, producerTemplate);
    }

    @Test
    void noQuotation_andEverythingBilledOnConfirmedOrders_createsNothing() {
        when(saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(VISIT, null)).thenReturn(null);
        when(saleOrderHandler.getStayNightsToBill(any(), anyInt(), any(), eq(VISIT), isNull()))
                .thenReturn(0f);

        processor.bill(stay(3, 0), new HashMap<>(), producerTemplate);

        verify(saleOrderHandler, never()).createStayQuotation(any(), any(), any(), any(), any(), any());
        verify(saleOrderHandler, never()).upsertStayLine(any(), any(), anyFloat(), any(), any());
    }

    @Test
    void notConfigured_billsNothing() {
        when(inpatientStayHandler.isConfigured()).thenReturn(false);
        Exchange exchange = mock(Exchange.class);

        processor.process(exchange);

        verify(inpatientStayHandler, never()).getStays(any());
    }

    @Test
    void oneFailingStay_doesNotStopTheOthers() throws Exception {
        when(inpatientStayHandler.isConfigured()).thenReturn(true);
        InpatientStay failing = new InpatientStay("visit-1", "p1", "a1", Map.of(COMMUNE, 1));
        InpatientStay fine = new InpatientStay("visit-2", "p2", "a2", Map.of(COMMUNE, 1));
        when(inpatientStayHandler.getStays(any())).thenReturn(List.of(failing, fine));
        when(saleOrderHandler.getDraftSaleOrderIfExistsByVisitId("visit-1", null))
                .thenThrow(new IllegalStateException("Odoo down"));
        SaleOrder quotation = new SaleOrder();
        quotation.setOrderId(5);
        when(saleOrderHandler.getDraftSaleOrderIfExistsByVisitId("visit-2", null))
                .thenReturn(quotation);
        when(saleOrderHandler.getStayNightsToBill(commune, 1, 5, "visit-2", null))
                .thenReturn(1f);
        Exchange exchange = mock(Exchange.class);
        CamelContext context = mock(CamelContext.class);
        when(exchange.getContext()).thenReturn(context);
        when(context.createProducerTemplate()).thenReturn(producerTemplate);

        processor.process(exchange);

        verify(saleOrderHandler).upsertStayLine(quotation, commune, 1f, null, producerTemplate);
    }
}

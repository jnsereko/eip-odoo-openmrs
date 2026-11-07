/*
 * Copyright © 2021, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.ozonehis.eip.odoo.openmrs.Constants;
import com.ozonehis.eip.odoo.openmrs.client.OdooClient;
import com.ozonehis.eip.odoo.openmrs.client.OpenmrsRestClient;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.InsurancePayerHandler;
import com.ozonehis.eip.odoo.openmrs.model.SaleOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.openmrs.eip.EIPException;

class InsurancePayerHandlerTest {

    private static final String VISIT_UUID = "0f1d2c3b-4a59-4687-9a1b-2c3d4e5f6a7b";

    private static final String PAYER_TYPE = "payer-attribute-type-uuid";

    private static final String OTHER_TYPE = "other-attribute-type-uuid";

    private static final String MFP = "mfp-answer-uuid";

    private static final String SUPPLEMENTARY = "supplementary-answer-uuid";

    private static final String CASH = "cash-answer-uuid";

    private static final String MOBILE_MONEY = "mobile-money-answer-uuid";

    private static final String SUPPLEMENTARY_TYPE = "supplementary-attribute-type-uuid";

    private static final String PNB = "pnb-answer-uuid";

    private static final int QUOTATION_ID = 7;

    @Mock
    private OdooClient odooClient;

    @Mock
    private OpenmrsRestClient openmrsRestClient;

    @InjectMocks
    private InsurancePayerHandler handler;

    private AutoCloseable mocksCloser;

    @BeforeEach
    public void setup() {
        mocksCloser = openMocks(this);
        handler.setPayerAttributeTypeUuid(PAYER_TYPE);
        handler.setSelfPayPayers(CASH + ", " + MOBILE_MONEY);
    }

    @AfterEach
    public void close() throws Exception {
        mocksCloser.close();
    }

    private void givenVisit(String attributesJson) throws Exception {
        String visit = "{\"uuid\":\"" + VISIT_UUID + "\",\"attributes\":[" + attributesJson + "]}";
        when(openmrsRestClient.get(eq("visit"), anyString())).thenReturn(visit.getBytes(StandardCharsets.UTF_8));
    }

    private static String codedAttribute(String type, String answer, boolean voided) {
        return "{\"uuid\":\"a-" + answer + "\",\"voided\":" + voided + ",\"attributeType\":{\"uuid\":\"" + type
                + "\"},\"value\":{\"uuid\":\"" + answer + "\",\"display\":\"x\"}}";
    }

    private void givenPlan(String payerCode, int id, String type) {
        when(odooClient.searchAndRead(
                        eq(Constants.PARTNER_MODEL),
                        eq(List.of(asList("is_insurance", "=", true), asList("insurance_payer_code", "=", payerCode))),
                        any()))
                .thenReturn(new Object[] {Map.of("id", id, "name", "Plan " + id, "insurance_type", type)});
    }

    @Test
    public void shouldDoNothingWhenTheFeatureIsOff() {
        handler.setPayerAttributeTypeUuid("");
        SaleOrder saleOrder = new SaleOrder();

        handler.applyVisitPayer(saleOrder, VISIT_UUID);

        verifyNoInteractions(openmrsRestClient, odooClient);
        assertNull(saleOrder.getInsuranceBaseId());
        assertNull(saleOrder.getInsuranceComplementaryId());
    }

    @Test
    public void shouldNameTheBasePlanOfTheVisitsPayer() throws Exception {
        givenVisit(codedAttribute(PAYER_TYPE, MFP, false));
        givenPlan(MFP, 41, "base");
        SaleOrder saleOrder = new SaleOrder();

        handler.applyVisitPayer(saleOrder, VISIT_UUID);

        assertEquals(41, saleOrder.getInsuranceBaseId());
        // Left out, so that Odoo takes the patient's complementary plan.
        assertNull(saleOrder.getInsuranceComplementaryId());
    }

    @Test
    public void shouldNameAComplementaryPlanAsComplementary() throws Exception {
        givenVisit(codedAttribute(PAYER_TYPE, SUPPLEMENTARY, false));
        givenPlan(SUPPLEMENTARY, 42, "complementary");
        SaleOrder saleOrder = new SaleOrder();

        handler.applyVisitPayer(saleOrder, VISIT_UUID);

        assertNull(saleOrder.getInsuranceBaseId());
        assertEquals(42, saleOrder.getInsuranceComplementaryId());
    }

    @Test
    public void shouldNameNoPlanForASelfPaidVisit() throws Exception {
        givenVisit(codedAttribute(PAYER_TYPE, MOBILE_MONEY, false));
        SaleOrder saleOrder = new SaleOrder();

        handler.applyVisitPayer(saleOrder, VISIT_UUID);

        assertEquals(Boolean.FALSE, saleOrder.getInsuranceBaseId());
        assertEquals(Boolean.FALSE, saleOrder.getInsuranceComplementaryId());
        verify(odooClient, never()).searchAndRead(any(), any(), any());
    }

    @Test
    public void shouldLeaveAVisitWithoutPayerToThePatientsPlans() throws Exception {
        givenVisit(codedAttribute(OTHER_TYPE, MFP, false));
        SaleOrder saleOrder = new SaleOrder();

        handler.applyVisitPayer(saleOrder, VISIT_UUID);

        assertNull(saleOrder.getInsuranceBaseId());
        assertNull(saleOrder.getInsuranceComplementaryId());
        verify(odooClient, never()).searchAndRead(any(), any(), any());
    }

    @Test
    public void shouldIgnoreAVoidedPayer() throws Exception {
        givenVisit(codedAttribute(PAYER_TYPE, MFP, true) + "," + codedAttribute(PAYER_TYPE, CASH, false));
        SaleOrder saleOrder = new SaleOrder();

        handler.applyVisitPayer(saleOrder, VISIT_UUID);

        assertEquals(Boolean.FALSE, saleOrder.getInsuranceBaseId());
    }

    @Test
    public void shouldFailWhenThePayerHasNoPlan() throws Exception {
        givenVisit(codedAttribute(PAYER_TYPE, MFP, false));
        when(odooClient.searchAndRead(eq(Constants.PARTNER_MODEL), any(), any()))
                .thenReturn(new Object[] {});
        SaleOrder saleOrder = new SaleOrder();

        EIPException e = assertThrows(EIPException.class, () -> handler.applyVisitPayer(saleOrder, VISIT_UUID));

        assertTrue(e.getMessage().contains(MFP));
        assertNull(saleOrder.getInsuranceBaseId());
    }

    @Test
    public void shouldFailWhenSeveralPlansHaveThePayerCode() throws Exception {
        givenVisit(codedAttribute(PAYER_TYPE, MFP, false));
        when(odooClient.searchAndRead(eq(Constants.PARTNER_MODEL), any(), any()))
                .thenReturn(new Object[] {Map.of("id", 1), Map.of("id", 2)});

        assertThrows(EIPException.class, () -> handler.applyVisitPayer(new SaleOrder(), VISIT_UUID));
    }

    @Test
    public void shouldFailWhenTheVisitIsNotFound() throws Exception {
        when(openmrsRestClient.get(eq("visit"), anyString())).thenReturn(null);

        assertThrows(EIPException.class, () -> handler.applyVisitPayer(new SaleOrder(), VISIT_UUID));
    }

    @Test
    public void shouldReadAPayerStoredAsText() throws Exception {
        givenVisit("{\"voided\":false,\"attributeType\":{\"uuid\":\"" + PAYER_TYPE + "\"},\"value\":\"" + CASH + "\"}");

        assertEquals(CASH, handler.getVisitPayer(VISIT_UUID));
    }

    @Test
    public void shouldAskOpenmrsForTheVisitsAttributesOnly() throws Exception {
        givenVisit("");

        handler.applyVisitPayer(new SaleOrder(), VISIT_UUID);

        verify(openmrsRestClient)
                .get("visit", VISIT_UUID + "?v=custom:(uuid,attributes:(uuid,voided,attributeType:(uuid),value))");
    }

    private void givenQuotationPlans(Object base, Object complementary) {
        when(odooClient.searchAndRead(
                        eq(Constants.SALE_ORDER_MODEL), eq(List.of(asList("id", "=", QUOTATION_ID))), any()))
                .thenReturn(new Object[] {
                    Map.of("id", QUOTATION_ID, "insurance_base_id", base, "insurance_complementary_id", complementary)
                });
    }

    private void givenNotes(Object... notes) {
        when(odooClient.searchAndRead(eq("mail.message"), any(), any())).thenReturn(notes);
    }

    private static SaleOrder quotation() {
        SaleOrder quotation = new SaleOrder();
        quotation.setOrderId(QUOTATION_ID);
        return quotation;
    }

    @Test
    public void shouldNameThePayerAndTheSupplementaryPayersPlans() throws Exception {
        handler.setSupplementaryAttributeTypeUuid(SUPPLEMENTARY_TYPE);
        givenVisit(codedAttribute(PAYER_TYPE, MFP, false) + "," + codedAttribute(SUPPLEMENTARY_TYPE, PNB, false));
        givenPlan(MFP, 41, "base");
        givenPlan(PNB, 42, "complementary");
        SaleOrder saleOrder = new SaleOrder();

        handler.applyVisitPayer(saleOrder, VISIT_UUID);

        assertEquals(41, saleOrder.getInsuranceBaseId());
        assertEquals(42, saleOrder.getInsuranceComplementaryId());
    }

    @Test
    public void shouldNameNoComplementaryPlanWhenTheVisitHasNoSupplementaryPayer() throws Exception {
        handler.setSupplementaryAttributeTypeUuid(SUPPLEMENTARY_TYPE);
        givenVisit(codedAttribute(PAYER_TYPE, MFP, false));
        givenPlan(MFP, 41, "base");
        SaleOrder saleOrder = new SaleOrder();

        handler.applyVisitPayer(saleOrder, VISIT_UUID);

        assertEquals(41, saleOrder.getInsuranceBaseId());
        // The visit form has the field and it was left empty: not the patient's complementary plan.
        assertEquals(Boolean.FALSE, saleOrder.getInsuranceComplementaryId());
    }

    @Test
    public void shouldKeepTheSupplementaryPlanOfASelfPaidVisit() throws Exception {
        handler.setSupplementaryAttributeTypeUuid(SUPPLEMENTARY_TYPE);
        givenVisit(codedAttribute(PAYER_TYPE, CASH, false) + "," + codedAttribute(SUPPLEMENTARY_TYPE, PNB, false));
        givenPlan(PNB, 42, "complementary");
        SaleOrder saleOrder = new SaleOrder();

        handler.applyVisitPayer(saleOrder, VISIT_UUID);

        assertEquals(Boolean.FALSE, saleOrder.getInsuranceBaseId());
        assertEquals(42, saleOrder.getInsuranceComplementaryId());
    }

    @Test
    public void shouldFailWhenTheSupplementaryPayerHasNoPlan() throws Exception {
        handler.setSupplementaryAttributeTypeUuid(SUPPLEMENTARY_TYPE);
        givenVisit(codedAttribute(PAYER_TYPE, MFP, false) + "," + codedAttribute(SUPPLEMENTARY_TYPE, PNB, false));
        givenPlan(MFP, 41, "base");
        when(odooClient.searchAndRead(
                        eq(Constants.PARTNER_MODEL),
                        eq(List.of(asList("is_insurance", "=", true), asList("insurance_payer_code", "=", PNB))),
                        any()))
                .thenReturn(new Object[] {});
        SaleOrder saleOrder = new SaleOrder();

        EIPException e = assertThrows(EIPException.class, () -> handler.applyVisitPayer(saleOrder, VISIT_UUID));

        assertTrue(e.getMessage().contains(PNB));
        assertNull(saleOrder.getInsuranceBaseId());
    }

    @Test
    public void shouldFailWhenTheVisitNamesTwoComplementaryPlans() throws Exception {
        handler.setSupplementaryAttributeTypeUuid(SUPPLEMENTARY_TYPE);
        givenVisit(codedAttribute(PAYER_TYPE, SUPPLEMENTARY, false) + ","
                + codedAttribute(SUPPLEMENTARY_TYPE, PNB, false));
        givenPlan(SUPPLEMENTARY, 43, "complementary");
        givenPlan(PNB, 42, "complementary");

        assertThrows(EIPException.class, () -> handler.applyVisitPayer(new SaleOrder(), VISIT_UUID));
    }

    @Test
    public void shouldNotNoteAQuotationThatMatchesTheVisit() throws Exception {
        givenVisit(codedAttribute(PAYER_TYPE, MFP, false));
        givenPlan(MFP, 41, "base");
        givenQuotationPlans(new Object[] {41, "MFP"}, false);

        handler.noteVisitPayerChange(quotation(), VISIT_UUID);

        verify(odooClient, never()).create(any(), any());
    }

    @Test
    public void shouldNoteAPayerChangedAfterTheQuotationWasCreated() throws Exception {
        givenVisit(codedAttribute(PAYER_TYPE, MFP, false));
        givenPlan(MFP, 41, "base");
        givenQuotationPlans(false, false);
        givenNotes();
        handler.setPayerChangedNote("billed to %s, visit says %s");

        handler.noteVisitPayerChange(quotation(), VISIT_UUID);

        ArgumentCaptor<List<Map<String, Object>>> message = ArgumentCaptor.forClass(List.class);
        verify(odooClient).create(eq("mail.message"), message.capture());
        Map<String, Object> note = message.getValue().get(0);
        assertEquals(Constants.SALE_ORDER_MODEL, note.get("model"));
        assertEquals(QUOTATION_ID, note.get("res_id"));
        assertEquals("billed to -, visit says Plan 41", note.get("body"));
        // The quotation's plans are not written.
        verify(odooClient, never()).write(any(), any());
    }

    @Test
    public void shouldNoteAChangeOnlyOnce() throws Exception {
        givenVisit(codedAttribute(PAYER_TYPE, CASH, false));
        givenQuotationPlans(new Object[] {41, "MFP"}, false);
        givenNotes(Map.of("id", 99));

        handler.noteVisitPayerChange(quotation(), VISIT_UUID);

        verify(odooClient, never()).create(any(), any());
    }

    @Test
    public void shouldNoteAChangedPayerWithNoPlanWithoutFailingTheOrder() throws Exception {
        givenVisit(codedAttribute(PAYER_TYPE, MFP, false));
        when(odooClient.searchAndRead(eq(Constants.PARTNER_MODEL), any(), any()))
                .thenReturn(new Object[] {});
        givenQuotationPlans(false, false);
        givenNotes();

        assertDoesNotThrow(() -> handler.noteVisitPayerChange(quotation(), VISIT_UUID));

        verify(odooClient).create(eq("mail.message"), any());
    }

    @Test
    public void shouldNeverFailTheOrderWhenTheNoteCannotBeWritten() throws Exception {
        givenVisit(codedAttribute(PAYER_TYPE, MFP, false));
        givenPlan(MFP, 41, "base");
        givenQuotationPlans(false, false);
        givenNotes();
        doThrow(new RuntimeException("odoo down")).when(odooClient).create(any(), any());

        assertDoesNotThrow(() -> handler.noteVisitPayerChange(quotation(), VISIT_UUID));
    }

    @Test
    public void shouldNotCompareWhenTheFeatureIsOff() {
        handler.setPayerAttributeTypeUuid("");

        handler.noteVisitPayerChange(quotation(), VISIT_UUID);

        verifyNoInteractions(openmrsRestClient, odooClient);
    }
}

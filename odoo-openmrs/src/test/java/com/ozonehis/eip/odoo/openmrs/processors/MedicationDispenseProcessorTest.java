/*
 * Copyright © 2026, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.processors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ozonehis.eip.odoo.openmrs.Constants;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.SaleOrderHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.MedicationDispenseHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.MedicationDispenseHandler.DispenseChange;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.MedicationDispenseHandler.OrderVisit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MedicationDispenseProcessorTest {

    private static final Instant NOW = Instant.parse("2026-11-11T15:34:00Z");

    private MedicationDispenseHandler medicationDispenseHandler;

    private SaleOrderHandler saleOrderHandler;

    private ProducerTemplate producerTemplate;

    private Exchange exchange;

    private MedicationDispenseProcessor processor;

    @BeforeEach
    void setup() {
        medicationDispenseHandler = mock(MedicationDispenseHandler.class);
        saleOrderHandler = mock(SaleOrderHandler.class);
        producerTemplate = mock(ProducerTemplate.class);
        exchange = mock(Exchange.class);
        CamelContext context = mock(CamelContext.class);
        when(exchange.getContext()).thenReturn(context);
        when(context.createProducerTemplate()).thenReturn(producerTemplate);
        processor = new MedicationDispenseProcessor();
        processor.setMedicationDispenseHandler(medicationDispenseHandler);
        processor.setSaleOrderHandler(saleOrderHandler);
        processor.setDroppedNote(MedicationDispenseProcessor.DEFAULT_DROPPED_NOTE);
        processor.setLookbackMinutes(1440);
        processor.setOverlapSeconds(120);
        processor.setMaxAttempts(3);
    }

    @Test
    void shouldSendEachPrescriptionWithAChangedDispenseOnceAsADispenseUpdate() {
        when(medicationDispenseHandler.getDatabaseNow()).thenReturn(NOW);
        when(medicationDispenseHandler.getDispensesChangedSince(NOW.minus(Duration.ofDays(1))))
                .thenReturn(List.of(
                        new DispenseChange("md-1", "rx-1", NOW.minusSeconds(60)),
                        new DispenseChange("md-2", "rx-1", NOW.minusSeconds(30)),
                        new DispenseChange("md-3", "rx-2", NOW.minusSeconds(10))));

        processor.process(exchange);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Map<String, Object>> headers = ArgumentCaptor.forClass(Map.class);
        verify(producerTemplate, times(2))
                .sendBodyAndHeaders(
                        eq(MedicationDispenseProcessor.MEDICATION_REQUEST_ROUTE), body.capture(), headers.capture());
        assertEquals("rx-1", ((MedicationRequest) body.getAllValues().get(0)).getIdPart());
        assertEquals("rx-2", ((MedicationRequest) body.getAllValues().get(1)).getIdPart());
        assertEquals("u", headers.getValue().get(org.openmrs.eip.fhir.Constants.HEADER_FHIR_EVENT_TYPE));
        assertEquals(true, headers.getValue().get(Constants.HEADER_MEDICATION_DISPENSE_SYNC));
    }

    // The next run reads back over the overlap: a dispense already billed is not sent again, a later edit of it is
    @Test
    void shouldNotSendADispenseAgainForTheOverlapButSendItsLaterEdit() {
        Instant later = NOW.plusSeconds(30);
        when(medicationDispenseHandler.getDatabaseNow()).thenReturn(NOW, later, later.plusSeconds(30));
        DispenseChange partial = new DispenseChange("md-1", "rx-1", NOW.minusSeconds(10));
        when(medicationDispenseHandler.getDispensesChangedSince(any()))
                .thenReturn(List.of(partial))
                .thenReturn(List.of(partial))
                .thenReturn(List.of(new DispenseChange("md-1", "rx-1", later.plusSeconds(5))));

        processor.process(exchange);
        processor.process(exchange);
        processor.process(exchange);

        verify(medicationDispenseHandler).getDispensesChangedSince(NOW.minusSeconds(120));
        verify(producerTemplate, times(2))
                .sendBodyAndHeaders(eq(MedicationDispenseProcessor.MEDICATION_REQUEST_ROUTE), any(), anyMap());
    }

    // OpenMRS or Odoo failed for one prescription: it is tried on the next runs, then given up with an error
    @Test
    void shouldTryAFailedPrescriptionAgainUpToTheMaximumAttempts() {
        when(medicationDispenseHandler.getDatabaseNow()).thenReturn(NOW);
        when(medicationDispenseHandler.getDispensesChangedSince(any()))
                .thenReturn(List.of(new DispenseChange("md-1", "rx-1", NOW.minusSeconds(600))))
                .thenReturn(List.of());
        doThrow(new CamelExecutionException("Odoo down", exchange))
                .when(producerTemplate)
                .sendBodyAndHeaders(eq(MedicationDispenseProcessor.MEDICATION_REQUEST_ROUTE), any(), anyMap());

        for (int run = 0; run < 5; run++) {
            processor.process(exchange);
        }

        verify(producerTemplate, times(3))
                .sendBodyAndHeaders(
                        eq(MedicationDispenseProcessor.MEDICATION_REQUEST_ROUTE),
                        argThat(body -> "rx-1".equals(((MedicationRequest) body).getIdPart())),
                        anyMap());
    }

    @Test
    void shouldLeaveANoteNamingTheDrugOnTheVisitsQuotationWhenGivingUp() {
        when(medicationDispenseHandler.getDatabaseNow()).thenReturn(NOW);
        when(medicationDispenseHandler.getDispensesChangedSince(any()))
                .thenReturn(List.of(new DispenseChange("md-1", "rx-1", NOW.minusSeconds(600))))
                .thenReturn(List.of());
        when(medicationDispenseHandler.getVisitOfOrder("rx-1")).thenReturn(new OrderVisit("visit-1", "Amoxicilline"));
        when(saleOrderHandler.postNoteOnDraftSaleOrder(eq("visit-1"), anyString()))
                .thenReturn(true);
        doThrow(new CamelExecutionException("No unit\nsecond line", exchange))
                .when(producerTemplate)
                .sendBodyAndHeaders(eq(MedicationDispenseProcessor.MEDICATION_REQUEST_ROUTE), any(), anyMap());

        for (int run = 0; run < 5; run++) {
            processor.process(exchange);
        }

        // Once, on the third failed run; not on the runs before, not again afterwards
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(saleOrderHandler, times(1)).postNoteOnDraftSaleOrder(eq("visit-1"), note.capture());
        assertTrue(note.getValue().contains("Amoxicilline"));
        assertTrue(note.getValue().contains("rx-1"));
    }

    @Test
    void shouldStillGiveUpQuietlyWhenTheNoteCannotBeLeft() {
        when(medicationDispenseHandler.getDatabaseNow()).thenReturn(NOW);
        when(medicationDispenseHandler.getDispensesChangedSince(any()))
                .thenReturn(List.of(new DispenseChange("md-1", "rx-1", NOW.minusSeconds(600))))
                .thenReturn(List.of());
        when(medicationDispenseHandler.getVisitOfOrder("rx-1")).thenReturn(new OrderVisit("visit-1", "Amoxicilline"));
        when(saleOrderHandler.postNoteOnDraftSaleOrder(anyString(), anyString()))
                .thenThrow(new IllegalStateException("Odoo down"));
        doThrow(new CamelExecutionException("No unit", exchange))
                .when(producerTemplate)
                .sendBodyAndHeaders(eq(MedicationDispenseProcessor.MEDICATION_REQUEST_ROUTE), any(), anyMap());

        for (int run = 0; run < 5; run++) {
            processor.process(exchange);
        }

        verify(producerTemplate, times(3)).sendBodyAndHeaders(any(String.class), any(), anyMap());
    }

    @Test
    void shouldLeaveNoNoteWhenALaterAttemptSucceeds() {
        when(medicationDispenseHandler.getDatabaseNow()).thenReturn(NOW);
        when(medicationDispenseHandler.getDispensesChangedSince(any()))
                .thenReturn(List.of(new DispenseChange("md-1", "rx-1", NOW.minusSeconds(600))))
                .thenReturn(List.of());
        doThrow(new CamelExecutionException("Odoo down", exchange))
                .doNothing()
                .when(producerTemplate)
                .sendBodyAndHeaders(eq(MedicationDispenseProcessor.MEDICATION_REQUEST_ROUTE), any(), anyMap());

        for (int run = 0; run < 5; run++) {
            processor.process(exchange);
        }

        verify(saleOrderHandler, never()).postNoteOnDraftSaleOrder(anyString(), anyString());
        verify(medicationDispenseHandler, never()).getVisitOfOrder(anyString());
    }

    @Test
    void shouldWriteTheDefaultNoteInCorrectFrench() {
        String note = MedicationDispenseProcessor.DEFAULT_DROPPED_NOTE;
        assertTrue(note.contains("m\u00e9dicament"));
        assertTrue(note.contains("\u00eatre"));
        assertTrue(note.contains("factur\u00e9"));
        assertTrue(note.contains("\u00e0 la main"));
        assertFalse(note.contains("\u00c3"));
    }

    @Test
    void shouldCarryTheSameDefaultNoteInApplicationProperties() throws Exception {
        // java.util.Properties reads ISO-8859-1 like Spring does: the escapes must give the same text
        java.util.Properties properties = new java.util.Properties();
        try (java.io.InputStream in = getClass().getResourceAsStream("/config/application.properties")) {
            properties.load(in);
        }
        String raw = properties.getProperty("odoo.billing.dropped.note");
        assertEquals(
                "${ODOO_BILLING_DROPPED_NOTE:" + MedicationDispenseProcessor.DEFAULT_DROPPED_NOTE + "}", raw);
    }

    @Test
    void shouldReportTheRootCauseOnOneLine() {
        Exception root = new IllegalStateException("Multiple Partners exists\nwith reference id 42");
        Exception wrapped = new CamelExecutionException(
                "Error processing MedicationRequest on the exchange: Exchange[ABC]", exchange, new RuntimeException(root));
        assertEquals("Multiple Partners exists with reference id 42", MedicationDispenseProcessor.rootCauseReason(wrapped));
    }

    @Test
    void shouldTruncateALongReasonAndNameTheClassWhenThereIsNoMessage() {
        String reason = MedicationDispenseProcessor.rootCauseReason(new RuntimeException("x".repeat(1000)));
        assertEquals(MedicationDispenseProcessor.MAX_REASON_LENGTH + 3, reason.length());
        assertEquals("NullPointerException", MedicationDispenseProcessor.rootCauseReason(new NullPointerException()));
    }

    @Test
    void shouldSendNothingWhenNoDispenseChanged() {
        when(medicationDispenseHandler.getDatabaseNow()).thenReturn(NOW);
        when(medicationDispenseHandler.getDispensesChangedSince(any())).thenReturn(List.of());

        processor.process(exchange);

        verify(producerTemplate, never()).sendBodyAndHeaders(any(String.class), any(), anyMap());
    }
}

/*
 * Copyright © 2021, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.processors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.openmrs.eip.fhir.Constants.HEADER_FHIR_EVENT_TYPE;

import com.ozonehis.eip.odoo.openmrs.handlers.odoo.PartnerHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.SaleOrderHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.MedicationDispenseHandler;
import com.ozonehis.eip.odoo.openmrs.model.Partner;
import com.ozonehis.eip.odoo.openmrs.model.SaleOrder;
import java.util.ArrayList;
import java.util.List;
import org.apache.camel.Exchange;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class MedicationRequestProcessorTest extends BaseProcessorTest {

    private static final String ENCOUNTER_REFERENCE_ID = "Encounter/1234";

    private static final String ENCOUNTER_VISIT_ID = "1234";

    private static final String PATIENT_ID = "patient-id-987";

    private static final int PARTNER_ID = 12;

    private static final String MEDICATION_REQUEST_ID = "medication-request-id-123";

    @Mock
    private SaleOrderHandler saleOrderHandler;

    @Mock
    private PartnerHandler partnerHandler;

    @Mock
    private MedicationDispenseHandler medicationDispenseHandler;

    @InjectMocks
    private MedicationRequestProcessor medicationRequestProcessor;

    private static AutoCloseable mocksCloser;

    @BeforeEach
    void setup() {
        mocksCloser = openMocks(this);
    }

    @AfterAll
    static void close() throws Exception {
        mocksCloser.close();
    }

    @Test
    void shouldUpdateSaleOrderWithSaleOrderLineWhenPartnerAndSaleOrderExists() {
        // Arrange
        Patient patient = new Patient();
        patient.setId(PATIENT_ID);
        Encounter encounter = new Encounter();
        encounter.setPartOf(new Reference(ENCOUNTER_REFERENCE_ID));
        MedicationRequest medicationRequest = new MedicationRequest();
        medicationRequest.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
        Medication medication = new Medication();

        Bundle bundle = new Bundle();
        List<Bundle.BundleEntryComponent> entries = new ArrayList<>();
        entries.add(new Bundle.BundleEntryComponent().setResource(patient));
        entries.add(new Bundle.BundleEntryComponent().setResource(encounter));
        entries.add(new Bundle.BundleEntryComponent().setResource(medicationRequest));
        entries.add(new Bundle.BundleEntryComponent().setResource(medication));
        bundle.setEntry(entries);

        SaleOrder saleOrder = new SaleOrder();

        Exchange exchange = createExchange(bundle, "u");

        Partner partner = new Partner();
        partner.setPartnerId(PARTNER_ID);

        // Mock behavior
        when(partnerHandler.createOrUpdatePartner(any(), eq(patient), any())).thenReturn(partner);
        when(saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(eq(ENCOUNTER_VISIT_ID), any()))
                .thenReturn(saleOrder);

        // Act
        medicationRequestProcessor.process(exchange);

        // Assert
        assertEquals(exchange.getMessage().getHeader(HEADER_FHIR_EVENT_TYPE), "u");
        verify(saleOrderHandler, times(1))
                .updateSaleOrderIfExistsWithSaleOrderLine(
                        eq(medicationRequest),
                        eq(saleOrder),
                        eq(ENCOUNTER_VISIT_ID),
                        eq(PARTNER_ID),
                        eq(PATIENT_ID),
                        any(),
                        any());
        verify(saleOrderHandler, times(0))
                .createSaleOrderWithSaleOrderLine(
                        eq(medicationRequest),
                        eq(encounter),
                        eq(partner),
                        eq(ENCOUNTER_VISIT_ID),
                        eq(PATIENT_ID),
                        any(),
                        any());
    }

    @Test
    void shouldCreateSaleOrderAndSaleOrderLineWhenPartnerExists() {
        // Arrange
        Patient patient = new Patient();
        patient.setId(PATIENT_ID);
        Encounter encounter = new Encounter();
        encounter.setPartOf(new Reference(ENCOUNTER_REFERENCE_ID));
        MedicationRequest medicationRequest = new MedicationRequest();
        medicationRequest.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
        Medication medication = new Medication();

        Bundle bundle = new Bundle();
        List<Bundle.BundleEntryComponent> entries = new ArrayList<>();
        entries.add(new Bundle.BundleEntryComponent().setResource(patient));
        entries.add(new Bundle.BundleEntryComponent().setResource(encounter));
        entries.add(new Bundle.BundleEntryComponent().setResource(medicationRequest));
        entries.add(new Bundle.BundleEntryComponent().setResource(medication));
        bundle.setEntry(entries);

        Exchange exchange = createExchange(bundle, "c");

        Partner partner = new Partner();
        partner.setPartnerId(PARTNER_ID);

        // Mock behavior
        when(partnerHandler.createOrUpdatePartner(any(), eq(patient), any())).thenReturn(partner);
        when(saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(eq(ENCOUNTER_VISIT_ID), any()))
                .thenReturn(null);

        // Act
        medicationRequestProcessor.process(exchange);

        // Assert
        assertEquals(exchange.getMessage().getHeader(HEADER_FHIR_EVENT_TYPE), "c");
        verify(saleOrderHandler, times(0))
                .updateSaleOrderIfExistsWithSaleOrderLine(
                        eq(medicationRequest),
                        any(),
                        eq(ENCOUNTER_VISIT_ID),
                        eq(PARTNER_ID),
                        eq(PATIENT_ID),
                        any(),
                        any());
        verify(saleOrderHandler, times(1))
                .createSaleOrderWithSaleOrderLine(
                        eq(medicationRequest),
                        eq(encounter),
                        eq(partner),
                        eq(ENCOUNTER_VISIT_ID),
                        eq(PATIENT_ID),
                        any(),
                        any());
    }

    @Test
    void shouldDeleteSaleOrderLineWhenMedicationRequestStatusIsCancelled() {
        // Arrange
        Patient patient = new Patient();
        Encounter encounter = new Encounter();
        encounter.setPartOf(new Reference(ENCOUNTER_REFERENCE_ID));
        MedicationRequest medicationRequest = new MedicationRequest();
        medicationRequest.setStatus(MedicationRequest.MedicationRequestStatus.CANCELLED);
        Medication medication = new Medication();

        Bundle bundle = new Bundle();
        List<Bundle.BundleEntryComponent> entries = new ArrayList<>();
        entries.add(new Bundle.BundleEntryComponent().setResource(patient));
        entries.add(new Bundle.BundleEntryComponent().setResource(encounter));
        entries.add(new Bundle.BundleEntryComponent().setResource(medicationRequest));
        entries.add(new Bundle.BundleEntryComponent().setResource(medication));
        bundle.setEntry(entries);

        Exchange exchange = createExchange(bundle, "u");

        Partner partner = new Partner();
        partner.setPartnerId(PARTNER_ID);

        // Mock behavior
        when(partnerHandler.createOrUpdatePartner(any(), eq(patient), any())).thenReturn(partner);

        // Act
        medicationRequestProcessor.process(exchange);

        // Assert
        assertEquals(exchange.getMessage().getHeader(HEADER_FHIR_EVENT_TYPE), "u");
        verify(saleOrderHandler, times(1))
                .deleteSaleOrderLine(eq(medicationRequest), eq(ENCOUNTER_VISIT_ID), any(), any());
        verify(saleOrderHandler, times(0))
                .updateSaleOrderIfExistsWithSaleOrderLine(
                        eq(medicationRequest),
                        any(),
                        eq(ENCOUNTER_VISIT_ID),
                        eq(PARTNER_ID),
                        eq(PATIENT_ID),
                        any(),
                        any());
        verify(saleOrderHandler, times(0))
                .createSaleOrderWithSaleOrderLine(
                        eq(medicationRequest),
                        eq(encounter),
                        eq(partner),
                        eq(ENCOUNTER_VISIT_ID),
                        eq(PATIENT_ID),
                        any(),
                        any());
    }

    // UVL-EMR #393: the pharmacist closes the prescription without dispensing anything
    @Test
    void shouldRemoveSaleOrderLineWhenPrescriptionIsClosedWithNothingDispensed() {
        // Arrange
        Patient patient = new Patient();
        MedicationRequest medicationRequest = medicationRequestWithFulfillerStatus("DECLINED");
        Exchange exchange = createExchange(medicationRequestBundle(patient, medicationRequest), "u");
        mockPartner(patient);
        when(medicationDispenseHandler.hasDispensedQuantity(MEDICATION_REQUEST_ID))
                .thenReturn(false);

        // Act
        medicationRequestProcessor.process(exchange);

        // Assert
        verify(saleOrderHandler, times(1))
                .removeSaleOrderLineFromQuotation(eq(medicationRequest), eq(ENCOUNTER_VISIT_ID), any(), any());
        verify(saleOrderHandler, times(1))
                .cancelSaleOrderWhenNoSaleOrderLine(eq(PARTNER_ID), eq(ENCOUNTER_VISIT_ID), any(), any());
        verify(saleOrderHandler, never())
                .updateSaleOrderIfExistsWithSaleOrderLine(any(), any(), any(), any(Integer.class), any(), any(), any());
        verify(saleOrderHandler, never())
                .createSaleOrderWithSaleOrderLine(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldRemoveSaleOrderLineWhenPrescriptionIsPausedWithNothingDispensed() {
        // Arrange
        Patient patient = new Patient();
        MedicationRequest medicationRequest = medicationRequestWithFulfillerStatus("ON_HOLD");
        Exchange exchange = createExchange(medicationRequestBundle(patient, medicationRequest), "u");
        mockPartner(patient);
        when(medicationDispenseHandler.hasDispensedQuantity(MEDICATION_REQUEST_ID))
                .thenReturn(false);

        // Act
        medicationRequestProcessor.process(exchange);

        // Assert
        verify(saleOrderHandler, times(1))
                .removeSaleOrderLineFromQuotation(eq(medicationRequest), eq(ENCOUNTER_VISIT_ID), any(), any());
    }

    @Test
    void shouldKeepSaleOrderLineWhenPrescriptionIsClosedAfterAPartialDispense() {
        // Arrange: partial-dispense billing is unchanged (#385 is separate)
        Patient patient = new Patient();
        MedicationRequest medicationRequest = medicationRequestWithFulfillerStatus("DECLINED");
        Exchange exchange = createExchange(medicationRequestBundle(patient, medicationRequest), "u");
        mockPartner(patient);
        when(medicationDispenseHandler.hasDispensedQuantity(MEDICATION_REQUEST_ID))
                .thenReturn(true);
        SaleOrder saleOrder = new SaleOrder();
        when(saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(eq(ENCOUNTER_VISIT_ID), any()))
                .thenReturn(saleOrder);

        // Act
        medicationRequestProcessor.process(exchange);

        // Assert
        verify(saleOrderHandler, never()).removeSaleOrderLineFromQuotation(any(), any(), any(), any());
        verify(saleOrderHandler, times(1))
                .updateSaleOrderIfExistsWithSaleOrderLine(
                        eq(medicationRequest),
                        eq(saleOrder),
                        eq(ENCOUNTER_VISIT_ID),
                        eq(PARTNER_ID),
                        any(),
                        any(),
                        any());
    }

    @Test
    void shouldNotCheckDispensesWhenPrescriptionHasNoFulfillerStatus() {
        // Arrange
        Patient patient = new Patient();
        MedicationRequest medicationRequest = medicationRequestWithFulfillerStatus(null);
        Exchange exchange = createExchange(medicationRequestBundle(patient, medicationRequest), "c");
        mockPartner(patient);

        // Act
        medicationRequestProcessor.process(exchange);

        // Assert
        verify(medicationDispenseHandler, never()).hasDispensedQuantity(any());
        verify(saleOrderHandler, never()).removeSaleOrderLineFromQuotation(any(), any(), any(), any());
        verify(saleOrderHandler, times(1))
                .createSaleOrderWithSaleOrderLine(
                        eq(medicationRequest), any(), any(), eq(ENCOUNTER_VISIT_ID), any(), any(), any());
    }

    @Test
    void shouldRemoveSaleOrderLineFromQuotationWhenMedicationRequestIsDiscontinued() {
        // Arrange
        Patient patient = new Patient();
        MedicationRequest medicationRequest = medicationRequestWithFulfillerStatus(null);
        medicationRequest.setStatus(MedicationRequest.MedicationRequestStatus.CANCELLED);
        Exchange exchange = createExchange(medicationRequestBundle(patient, medicationRequest), "d");
        mockPartner(patient);

        // Act
        medicationRequestProcessor.process(exchange);

        // Assert
        verify(saleOrderHandler, times(1))
                .removeSaleOrderLineFromQuotation(eq(medicationRequest), eq(ENCOUNTER_VISIT_ID), any(), any());
        verify(saleOrderHandler, times(1))
                .cancelSaleOrderWhenNoSaleOrderLine(eq(PARTNER_ID), eq(ENCOUNTER_VISIT_ID), any(), any());
    }

    private MedicationRequest medicationRequestWithFulfillerStatus(String fulfillerStatus) {
        MedicationRequest medicationRequest = new MedicationRequest();
        medicationRequest.setId(MEDICATION_REQUEST_ID);
        medicationRequest.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
        if (fulfillerStatus != null) {
            medicationRequest.addExtension(
                    MedicationRequestProcessor.FULFILLER_STATUS_EXTENSION_URL, new CodeType(fulfillerStatus));
        }
        return medicationRequest;
    }

    private Bundle medicationRequestBundle(Patient patient, MedicationRequest medicationRequest) {
        Encounter encounter = new Encounter();
        encounter.setPartOf(new Reference(ENCOUNTER_REFERENCE_ID));
        Bundle bundle = new Bundle();
        List<Bundle.BundleEntryComponent> entries = new ArrayList<>();
        entries.add(new Bundle.BundleEntryComponent().setResource(patient));
        entries.add(new Bundle.BundleEntryComponent().setResource(encounter));
        entries.add(new Bundle.BundleEntryComponent().setResource(medicationRequest));
        entries.add(new Bundle.BundleEntryComponent().setResource(new Medication()));
        bundle.setEntry(entries);
        return bundle;
    }

    private void mockPartner(Patient patient) {
        Partner partner = new Partner();
        partner.setPartnerId(PARTNER_ID);
        when(partnerHandler.createOrUpdatePartner(any(), eq(patient), any())).thenReturn(partner);
    }
}

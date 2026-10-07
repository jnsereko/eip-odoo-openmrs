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
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.OrderHandler;
import com.ozonehis.eip.odoo.openmrs.model.Partner;
import com.ozonehis.eip.odoo.openmrs.model.SaleOrder;
import java.util.ArrayList;
import java.util.List;
import org.apache.camel.Exchange;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class ServiceRequestProcessorTest extends BaseProcessorTest {
    private static final String ENCOUNTER_REFERENCE_ID = "Encounter/1234";

    private static final String ENCOUNTER_VISIT_ID = "1234";

    private static final int PARTNER_ID = 12;

    private static final String PATIENT_ID = "patient-id-987";

    private static final String SERVICE_REQUEST_ID = "service-request-id-123";

    @Mock
    private SaleOrderHandler saleOrderHandler;

    @Mock
    private PartnerHandler partnerHandler;

    @Mock
    private OrderHandler orderHandler;

    @InjectMocks
    private ServiceRequestProcessor serviceRequestProcessor;

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
        ServiceRequest serviceRequest = new ServiceRequest();
        serviceRequest.setStatus(ServiceRequest.ServiceRequestStatus.ACTIVE);
        serviceRequest.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);

        Bundle bundle = new Bundle();
        List<Bundle.BundleEntryComponent> entries = new ArrayList<>();
        entries.add(new Bundle.BundleEntryComponent().setResource(patient));
        entries.add(new Bundle.BundleEntryComponent().setResource(encounter));
        entries.add(new Bundle.BundleEntryComponent().setResource(serviceRequest));
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
        serviceRequestProcessor.process(exchange);

        // Assert
        assertEquals(exchange.getMessage().getHeader(HEADER_FHIR_EVENT_TYPE), "u");
        verify(saleOrderHandler, times(1))
                .updateSaleOrderIfExistsWithSaleOrderLine(
                        eq(serviceRequest),
                        eq(saleOrder),
                        eq(ENCOUNTER_VISIT_ID),
                        eq(PARTNER_ID),
                        eq(PATIENT_ID),
                        any(),
                        any());
        verify(saleOrderHandler, times(0))
                .createSaleOrderWithSaleOrderLine(
                        eq(serviceRequest),
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
        ServiceRequest serviceRequest = new ServiceRequest();
        serviceRequest.setStatus(ServiceRequest.ServiceRequestStatus.ACTIVE);
        serviceRequest.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);

        Bundle bundle = new Bundle();
        List<Bundle.BundleEntryComponent> entries = new ArrayList<>();
        entries.add(new Bundle.BundleEntryComponent().setResource(patient));
        entries.add(new Bundle.BundleEntryComponent().setResource(encounter));
        entries.add(new Bundle.BundleEntryComponent().setResource(serviceRequest));
        bundle.setEntry(entries);

        Exchange exchange = createExchange(bundle, "c");

        Partner partner = new Partner();
        partner.setPartnerId(PARTNER_ID);

        // Mock behavior
        when(partnerHandler.createOrUpdatePartner(any(), eq(patient), any())).thenReturn(partner);
        when(saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(eq(ENCOUNTER_VISIT_ID), any()))
                .thenReturn(null);

        // Act
        serviceRequestProcessor.process(exchange);

        // Assert
        assertEquals(exchange.getMessage().getHeader(HEADER_FHIR_EVENT_TYPE), "c");
        verify(saleOrderHandler, times(0))
                .updateSaleOrderIfExistsWithSaleOrderLine(
                        eq(serviceRequest),
                        any(),
                        eq(ENCOUNTER_VISIT_ID),
                        eq(PARTNER_ID),
                        eq(PATIENT_ID),
                        any(),
                        any());
        verify(saleOrderHandler, times(1))
                .createSaleOrderWithSaleOrderLine(
                        eq(serviceRequest),
                        eq(encounter),
                        eq(partner),
                        eq(ENCOUNTER_VISIT_ID),
                        eq(PATIENT_ID),
                        any(),
                        any());
    }

    // Test for handling completed status
    @Test
    void shouldUpdateSaleOrderWhenServiceRequestIsCompleted() throws Exception {
        // Arrange
        Patient patient = new Patient();
        patient.setId(PATIENT_ID);
        Encounter encounter = new Encounter();
        encounter.setPartOf(new Reference(ENCOUNTER_REFERENCE_ID));
        ServiceRequest serviceRequest = new ServiceRequest();
        serviceRequest.setStatus(ServiceRequest.ServiceRequestStatus.COMPLETED);
        serviceRequest.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);

        Bundle bundle = new Bundle();
        List<Bundle.BundleEntryComponent> entries = new ArrayList<>();
        entries.add(new Bundle.BundleEntryComponent().setResource(patient));
        entries.add(new Bundle.BundleEntryComponent().setResource(encounter));
        entries.add(new Bundle.BundleEntryComponent().setResource(serviceRequest));
        bundle.setEntry(entries);

        Exchange exchange = createExchange(bundle, "u");

        Partner partner = new Partner();
        partner.setPartnerId(PARTNER_ID);

        SaleOrder saleOrder = new SaleOrder();

        // Mock behavior
        when(partnerHandler.createOrUpdatePartner(any(), eq(patient), any())).thenReturn(partner);
        when(saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(eq(ENCOUNTER_VISIT_ID), any()))
                .thenReturn(saleOrder);
        when(orderHandler.isFulfilled(any())).thenReturn(true);

        // Act
        serviceRequestProcessor.process(exchange);

        // Assert
        assertEquals("u", exchange.getMessage().getHeader(HEADER_FHIR_EVENT_TYPE));
        verify(saleOrderHandler, times(1))
                .updateSaleOrderIfExistsWithSaleOrderLine(
                        eq(serviceRequest),
                        eq(saleOrder),
                        eq(ENCOUNTER_VISIT_ID),
                        eq(PARTNER_ID),
                        eq(PATIENT_ID),
                        any(),
                        any());
    }

    // UVL-EMR #386 / #254: FHIR2 reads a discontinued order as COMPLETED even though nothing was done.
    @Test
    void shouldRemoveSaleOrderLineWhenDiscontinuedOrderWasNotFulfilled() throws Exception {
        // Arrange
        Patient patient = new Patient();
        patient.setId(PATIENT_ID);
        Exchange exchange = createExchange(
                discontinuedServiceRequestBundle(patient, ServiceRequest.ServiceRequestStatus.COMPLETED), "d");
        mockPartner(patient);
        when(orderHandler.getFulfillerStatus(SERVICE_REQUEST_ID)).thenReturn(null);

        // Act
        serviceRequestProcessor.process(exchange);

        // Assert
        verify(saleOrderHandler, times(1))
                .removeSaleOrderLineFromQuotation(any(ServiceRequest.class), eq(ENCOUNTER_VISIT_ID), any(), any());
        verify(saleOrderHandler, times(1))
                .cancelSaleOrderWhenNoSaleOrderLine(eq(PARTNER_ID), eq(ENCOUNTER_VISIT_ID), any(), any());
    }

    @Test
    void shouldRemoveSaleOrderLineWhenDiscontinuedOrderWasOnlyInProgress() throws Exception {
        // Arrange
        Patient patient = new Patient();
        patient.setId(PATIENT_ID);
        Exchange exchange = createExchange(
                discontinuedServiceRequestBundle(patient, ServiceRequest.ServiceRequestStatus.COMPLETED), "d");
        mockPartner(patient);
        when(orderHandler.getFulfillerStatus(SERVICE_REQUEST_ID)).thenReturn("IN_PROGRESS");

        // Act
        serviceRequestProcessor.process(exchange);

        // Assert
        verify(saleOrderHandler, times(1))
                .removeSaleOrderLineFromQuotation(any(ServiceRequest.class), eq(ENCOUNTER_VISIT_ID), any(), any());
    }

    @Test
    void shouldKeepSaleOrderLineWhenDiscontinuedOrderWasFulfilled() throws Exception {
        // Arrange: the lab app discontinues an order right after its result is entered
        Patient patient = new Patient();
        patient.setId(PATIENT_ID);
        Exchange exchange = createExchange(
                discontinuedServiceRequestBundle(patient, ServiceRequest.ServiceRequestStatus.COMPLETED), "d");
        mockPartner(patient);
        when(orderHandler.getFulfillerStatus(SERVICE_REQUEST_ID)).thenReturn(OrderHandler.FULFILLER_STATUS_COMPLETED);

        // Act
        serviceRequestProcessor.process(exchange);

        // Assert
        verify(saleOrderHandler, never()).removeSaleOrderLineFromQuotation(any(), any(), any(), any());
        verify(saleOrderHandler, never()).deleteSaleOrderLine(any(), any(), any(), any());
        verify(saleOrderHandler, never()).cancelSaleOrderWhenNoSaleOrderLine(any(Integer.class), any(), any(), any());
    }

    @Test
    void shouldNotReAddSaleOrderLineWhenStoppedOrderWasNotFulfilled() throws Exception {
        // Arrange: the update event of the discontinued order itself, which FHIR2 reads as COMPLETED
        Patient patient = new Patient();
        patient.setId(PATIENT_ID);
        Exchange exchange = createExchange(
                discontinuedServiceRequestBundle(patient, ServiceRequest.ServiceRequestStatus.COMPLETED), "u");
        mockPartner(patient);
        when(orderHandler.isFulfilled(SERVICE_REQUEST_ID)).thenReturn(false);

        // Act
        serviceRequestProcessor.process(exchange);

        // Assert
        verify(saleOrderHandler, never())
                .updateSaleOrderIfExistsWithSaleOrderLine(any(), any(), any(), any(Integer.class), any(), any(), any());
        verify(saleOrderHandler, never())
                .createSaleOrderWithSaleOrderLine(any(), any(), any(), any(), any(), any(), any());
        verify(saleOrderHandler, never()).deleteSaleOrderLine(any(), any(), any(), any());
        verify(saleOrderHandler, never()).removeSaleOrderLineFromQuotation(any(), any(), any(), any());
    }

    private Bundle discontinuedServiceRequestBundle(Patient patient, ServiceRequest.ServiceRequestStatus status) {
        Encounter encounter = new Encounter();
        encounter.setPartOf(new Reference(ENCOUNTER_REFERENCE_ID));
        ServiceRequest serviceRequest = new ServiceRequest();
        serviceRequest.setId(SERVICE_REQUEST_ID);
        serviceRequest.setStatus(status);
        serviceRequest.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);

        Bundle bundle = new Bundle();
        List<Bundle.BundleEntryComponent> entries = new ArrayList<>();
        entries.add(new Bundle.BundleEntryComponent().setResource(patient));
        entries.add(new Bundle.BundleEntryComponent().setResource(encounter));
        entries.add(new Bundle.BundleEntryComponent().setResource(serviceRequest));
        bundle.setEntry(entries);
        return bundle;
    }

    private void mockPartner(Patient patient) {
        Partner partner = new Partner();
        partner.setPartnerId(PARTNER_ID);
        when(partnerHandler.createOrUpdatePartner(any(), eq(patient), any())).thenReturn(partner);
    }
}

/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.processors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.ICriterion;
import ca.uhn.fhir.rest.gclient.IQuery;
import ca.uhn.fhir.rest.gclient.IUntypedQuery;
import com.ozonehis.eip.odoo.openmrs.FakeOdoo;
import com.ozonehis.eip.odoo.openmrs.RadiologyConcepts;
import com.ozonehis.eip.odoo.openmrs.client.OdooClient;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.RadiologyPaymentEvidenceHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.EncounterHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.TaskHandler;
import java.util.Date;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The payment processor's acceptance decision (#322 attempt 2): a Task is accepted only when the
 * invoice carrying the order's own sale order line is paid.
 */
class RadiologyPaymentTaskProcessorTest {

    private static final String SR_ID = "78140b36-a957-4597-848a-a47237701403";

    private static final String ENCOUNTER = "encounter-uuid";

    private static final String PROCEDURE = "RX03 - Radiographie du bassin";

    private static final Date ORDERED = Date.from(java.time.Instant.parse("2026-09-10T08:00:00Z"));

    private static final String EC01 = "8155e2e0-5b62-42bc-b47c-0702aaafe3df";

    private FakeOdoo odoo;

    private IQuery<Bundle> srQuery;

    private TaskHandler taskHandler;

    private Task task;

    private RadiologyPaymentTaskProcessor processor;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setup() {
        IGenericClient fhirClient = mock(IGenericClient.class);
        IUntypedQuery<IBaseBundle> untyped = mock(IUntypedQuery.class);
        IQuery<IBaseBundle> query = mock(IQuery.class);
        srQuery = mock(IQuery.class);
        when(fhirClient.search()).thenReturn((IUntypedQuery) untyped);
        when(untyped.forResource(ServiceRequest.class)).thenReturn(query);
        when(query.where(any(ICriterion.class))).thenReturn(query);
        when(query.returnBundle(Bundle.class)).thenReturn(srQuery);
        Bundle bundle = new Bundle();
        bundle.addEntry().setResource(serviceRequest(RadiologyConcepts.DEFAULT_UUIDS.iterator().next(), PROCEDURE));
        when(srQuery.execute()).thenReturn(bundle);

        task = new Task();
        task.setId("5f38de0a-2503-4dcf-9147-4fc20665a0cb");
        task.setStatus(Task.TaskStatus.REQUESTED);
        taskHandler = mock(TaskHandler.class);
        when(taskHandler.getTaskByServiceRequestId(SR_ID)).thenReturn(task);

        EncounterHandler encounterHandler = mock(EncounterHandler.class);
        Encounter encounter = new Encounter();
        encounter.setPartOf(new Reference("Encounter/" + FakeOdoo.VISIT));
        when(encounterHandler.getEncounterByEncounterID(ENCOUNTER)).thenReturn(encounter);

        OdooClient odooClient = mock(OdooClient.class);
        odoo = new FakeOdoo(odooClient);
        odoo.saleOrder(1, "S03007");
        RadiologyPaymentEvidenceHandler evidence = new RadiologyPaymentEvidenceHandler();
        evidence.setOdooClient(odooClient);

        processor = new RadiologyPaymentTaskProcessor();
        processor.setOpenmrsFhirClient(fhirClient);
        processor.setOdooClient(odooClient);
        processor.setTaskHandler(taskHandler);
        processor.setEncounterHandler(encounterHandler);
        processor.setPaymentEvidenceHandler(evidence);
        processor.setRadiologyConcepts(new RadiologyConcepts());
    }

    private static ServiceRequest serviceRequest(String concept, String procedure) {
        ServiceRequest sr = new ServiceRequest();
        sr.setId(SR_ID);
        sr.setStatus(ServiceRequest.ServiceRequestStatus.ACTIVE);
        sr.getCode().setText(procedure).addCoding().setCode(concept);
        sr.setSubject(new Reference("Patient/" + FakeOdoo.PATIENT));
        sr.setEncounter(new Reference("Encounter/" + ENCOUNTER));
        sr.getMeta().setLastUpdated(ORDERED);
        return sr;
    }

    private void poll() {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        processor.process(exchange);
    }

    /** UAT attempt 2: UVL7744 ORD-11176, S03007, accepted on the 0 BIF down payment INV/2026/09/0008. */
    @Test
    void paidDownPaymentWithUnpaidFinalInvoiceIsNotAccepted() {
        odoo.line(6200, 1, PROCEDURE, 1, "2026-09-10 08:00:05");
        odoo.line(6201, 1, "Down payment", 1, "2026-09-10 08:10:00");
        odoo.paidInvoice(8, "INV/2026/09/0008", 6201);
        odoo.unpaidInvoice(9, "INV/2026/09/0009", 1.0, 6200, 6201);

        poll();

        verify(taskHandler, never()).updateTaskStatus(any(), eq(Task.TaskStatus.ACCEPTED));
    }

    @Test
    void paidFinalInvoiceIsAccepted() {
        odoo.line(6200, 1, PROCEDURE, 1, "2026-09-10 08:00:05");
        odoo.line(6201, 1, "Down payment", 1, "2026-09-10 08:10:00");
        odoo.paidInvoice(8, "INV/2026/09/0008", 6201);
        odoo.paidInvoice(9, "INV/2026/09/0009", 6200, 6201);

        poll();

        verify(taskHandler).updateTaskStatus(task, Task.TaskStatus.ACCEPTED);
    }

    @Test
    void radiologyLineOnlyOnTheUnpaidOfTwoInvoicesIsNotAccepted() {
        odoo.line(6200, 1, PROCEDURE, 1, "2026-09-10 08:00:05");
        odoo.line(6202, 1, "CONS1 - Consultation", 1, "2026-09-10 08:00:01");
        odoo.paidInvoice(7, "INV/2026/09/0007", 6202);
        odoo.unpaidInvoice(9, "INV/2026/09/0009", 1.0, 6200);

        poll();

        verify(taskHandler, never()).updateTaskStatus(any(), eq(Task.TaskStatus.ACCEPTED));
    }

    @Test
    void creditNoteReversingThePaidInvoiceIsNotAccepted() {
        odoo.line(6200, 1, PROCEDURE, 1, "2026-09-10 08:00:05");
        odoo.paidInvoice(9, "INV/2026/09/0009", 6200);
        odoo.move(10, "RINV/2026/09/0001", "out_refund", "posted", "paid", 0.0, 9);

        poll();

        verify(taskHandler, never()).updateTaskStatus(any(), eq(Task.TaskStatus.ACCEPTED));
    }

    @Test
    void anotherPatientsPaidInvoiceIsNotAccepted() {
        odoo.saleOrder(3, "S03020", "other-patient", "other-visit", "sale");
        odoo.line(6300, 3, PROCEDURE, 1, "2026-09-10 08:00:05");
        odoo.paidInvoice(20, "INV/2026/09/0020", 6300);

        poll();

        verify(taskHandler, never()).updateTaskStatus(any(), eq(Task.TaskStatus.ACCEPTED));
    }

    /** UVL-EMR#304: an ultrasound order was not a radiology order, so it never got a Task. */
    @Test
    void paidUltrasoundOrderIsAccepted() {
        String ultrasound = "EC01 - Abdominal ultrasound";
        Bundle bundle = new Bundle();
        bundle.addEntry().setResource(serviceRequest(EC01, ultrasound));
        when(srQuery.execute()).thenReturn(bundle);
        odoo.line(6200, 1, ultrasound, 1, "2026-09-10 08:00:05");
        odoo.paidInvoice(9, "INV/2026/09/0009", 6200);

        poll();

        verify(taskHandler).updateTaskStatus(task, Task.TaskStatus.ACCEPTED);
    }
}

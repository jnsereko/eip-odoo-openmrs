/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.processors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.ICriterion;
import ca.uhn.fhir.rest.gclient.IQuery;
import ca.uhn.fhir.rest.gclient.IRead;
import ca.uhn.fhir.rest.gclient.IReadExecutable;
import ca.uhn.fhir.rest.gclient.IReadTyped;
import ca.uhn.fhir.rest.gclient.IUntypedQuery;
import com.ozonehis.eip.odoo.openmrs.Constants;
import com.ozonehis.eip.odoo.openmrs.RadiologyConcepts;
import com.ozonehis.eip.odoo.openmrs.client.OdooClient;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.RadiologyPaymentEvidenceHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.EncounterHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.TaskHandler;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The accepted-Task half of the unpaid-imaging audit (#322): an ACCEPTED Task is re-derived against
 * Odoo, and reported only when its order has no paid line on its own visit.
 */
class UnpaidImagingAuditProcessorTest {

    private static final String SR_ID = "75f4a89e-0000-0000-0000-000000000001";

    private static final String TASK_ID = "1eb3f3cf-0000-0000-0000-000000000001";

    private static final String PATIENT = "patient-uuid";

    private static final String ENCOUNTER = "encounter-uuid";

    private static final String VISIT = "visit-uuid";

    private static final String PROCEDURE = "RX01 - Radiographie thoracique";

    // 2026-09-10T08:00:00Z
    private static final Date ORDERED = Date.from(java.time.Instant.parse("2026-09-10T08:00:00Z"));

    private IGenericClient fhirClient;

    private OdooClient odooClient;

    private IReadExecutable<ServiceRequest> readServiceRequest;

    private IQuery<Bundle> taskQuery;

    private UnpaidImagingAuditProcessor processor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        fhirClient = mock(IGenericClient.class);
        odooClient = mock(OdooClient.class);

        IUntypedQuery<IBaseBundle> untyped = mock(IUntypedQuery.class);
        IQuery<IBaseBundle> query = mock(IQuery.class);
        taskQuery = mock(IQuery.class);
        when(fhirClient.search()).thenReturn((IUntypedQuery) untyped);
        when(untyped.forResource(Task.class)).thenReturn(query);
        when(query.where(any(ICriterion.class))).thenReturn(query);
        when(query.returnBundle(Bundle.class)).thenReturn(taskQuery);

        IRead read = mock(IRead.class);
        IReadTyped<ServiceRequest> readTyped = mock(IReadTyped.class);
        readServiceRequest = mock(IReadExecutable.class);
        when(fhirClient.read()).thenReturn(read);
        when(read.resource(ServiceRequest.class)).thenReturn(readTyped);
        when(readTyped.withId(SR_ID)).thenReturn(readServiceRequest);
        when(readServiceRequest.execute()).thenReturn(serviceRequest());

        EncounterHandler encounterHandler = mock(EncounterHandler.class);
        Encounter encounter = new Encounter();
        encounter.setPartOf(new Reference("Encounter/" + VISIT));
        when(encounterHandler.getEncounterByEncounterID(ENCOUNTER)).thenReturn(encounter);

        RadiologyPaymentEvidenceHandler evidence = new RadiologyPaymentEvidenceHandler();
        evidence.setOdooClient(odooClient);

        processor = new UnpaidImagingAuditProcessor();
        processor.setOpenmrsFhirClient(fhirClient);
        processor.setTaskHandler(mock(TaskHandler.class));
        processor.setEncounterHandler(encounterHandler);
        processor.setPaymentEvidenceHandler(evidence);
    }

    private static ServiceRequest serviceRequest() {
        ServiceRequest sr = new ServiceRequest();
        sr.setId(SR_ID);
        sr.setStatus(ServiceRequest.ServiceRequestStatus.COMPLETED);
        sr.getCode().setText(PROCEDURE).addCoding().setCode(RadiologyConcepts.UUIDS.iterator().next());
        sr.setSubject(new Reference("Patient/" + PATIENT));
        sr.setEncounter(new Reference("Encounter/" + ENCOUNTER));
        sr.setOccurrence(new Period().setStart(ORDERED));
        return sr;
    }

    private static Task task(String id, Task.TaskStatus status) {
        Task task = new Task();
        task.setId(id);
        task.setStatus(status);
        task.setBasedOn(List.of(new Reference("ServiceRequest/" + SR_ID)));
        return task;
    }

    private void givenTasks(Task... tasks) {
        Bundle bundle = new Bundle();
        for (Task task : tasks) {
            bundle.addEntry().setResource(task);
        }
        when(taskQuery.execute()).thenReturn(bundle);
    }

    private static Map<String, Object> line(int id, String orderName, double qtyInvoiced, String createDate) {
        Map<String, Object> line = new HashMap<>();
        line.put("id", id);
        line.put("name", PROCEDURE);
        line.put("qty_invoiced", qtyInvoiced);
        line.put("order_id", new Object[] {id * 10, orderName});
        line.put("create_date", createDate);
        return line;
    }

    private void givenLines(Object... lines) {
        when(odooClient.searchAndRead(eq(Constants.SALE_ORDER_LINE_MODEL), anyList(), anyList()))
                .thenReturn(lines);
    }

    private void givenPaidInvoice(String orderName) {
        Map<String, Object> invoice = new HashMap<>();
        invoice.put("payment_state", "paid");
        invoice.put("amount_residual", 0.0);
        when(odooClient.searchAndRead(
                        eq(Constants.ACCOUNT_MOVE_MODEL),
                        eq(List.of(Arrays.asList("invoice_origin", "=", orderName))),
                        anyList()))
                .thenReturn(new Object[] {invoice});
    }

    @Test
    void acceptedAndPaidIsNotAFinding() {
        givenTasks(task(TASK_ID, Task.TaskStatus.ACCEPTED));
        givenLines(line(6163, "S02986", 1, "2026-09-10 08:00:04"));
        givenPaidInvoice("S02986");

        assertEquals(0, processor.auditAcceptedTasks());
    }

    @Test
    void acceptedWithNoPaidLineIsAFinding() {
        givenTasks(task(TASK_ID, Task.TaskStatus.ACCEPTED));
        givenLines(line(6163, "S02986", 0, "2026-09-10 08:00:04"));

        assertEquals(1, processor.auditAcceptedTasks());
    }

    @Test
    void acceptedWithAPaidLineAndANewerDraftLineInTheSameVisitIsNotAFinding() {
        givenTasks(task(TASK_ID, Task.TaskStatus.ACCEPTED));
        givenLines(
                line(6163, "S02986", 1, "2026-09-10 08:00:04"),
                line(6170, "S03001", 0, "2026-09-10 11:30:00"));
        givenPaidInvoice("S02986");

        assertEquals(0, processor.auditAcceptedTasks());
    }

    /** A dropped status filter must not turn every requested Task into an "accepted without payment". */
    @Test
    void nonAcceptedTasksReturnedByTheSearchAreIgnored() {
        givenTasks(task(TASK_ID, Task.TaskStatus.REQUESTED));

        assertEquals(0, processor.auditAcceptedTasks());
        verify(odooClient, never()).searchAndRead(anyString(), anyList(), anyList());
    }

    /** An Odoo outage is "not verified", not an accusation. */
    @Test
    void odooFailureIsNotAFinding() {
        givenTasks(task(TASK_ID, Task.TaskStatus.ACCEPTED));
        when(odooClient.searchAndRead(eq(Constants.SALE_ORDER_LINE_MODEL), anyList(), anyList()))
                .thenThrow(new RuntimeException("Odoo unreachable"));

        assertEquals(0, processor.auditAcceptedTasks());
    }
}

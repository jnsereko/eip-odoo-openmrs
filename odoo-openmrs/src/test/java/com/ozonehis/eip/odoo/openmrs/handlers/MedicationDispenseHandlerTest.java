/*
 * Copyright © 2026, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.MedicationDispenseHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.MedicationDispense;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs the handler against a real HAPI client and a local HTTP server, so the assertion is on the
 * query string that actually goes over the wire.
 */
class MedicationDispenseHandlerTest {

    private static final FhirContext FHIR_CONTEXT = FhirContext.forR4();

    private static final String MEDICATION_REQUEST_ID = "3c2b1a09-8f7e-4d6c-9b5a-4f3e2d1c0b9a";

    private static final String OTHER_MEDICATION_REQUEST_ID = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";

    private HttpServer server;

    private final AtomicReference<String> requestQuery = new AtomicReference<>();

    private Bundle responseBundle = new Bundle();

    private MedicationDispenseHandler medicationDispenseHandler;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = FHIR_CONTEXT
                    .newJsonParser()
                    .encodeResourceToString(responseBundle)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/fhir+json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        FHIR_CONTEXT.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        IGenericClient client = FHIR_CONTEXT.newRestfulGenericClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/ws/fhir2/R4");
        medicationDispenseHandler = new MedicationDispenseHandler();
        medicationDispenseHandler.setOpenmrsFhirClient(client);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldSearchByPrescription() {
        medicationDispenseHandler.hasDispensedQuantity(MEDICATION_REQUEST_ID);

        String query = URLDecoder.decode(requestQuery.get(), StandardCharsets.UTF_8);
        assertEquals("prescription=MedicationRequest/" + MEDICATION_REQUEST_ID, query);
    }

    @Test
    void shouldReportNothingDispensedWhenThePrescriptionWasOnlyPausedAndClosed() {
        responseBundle = bundleOf(
                dispense(MEDICATION_REQUEST_ID, MedicationDispense.MedicationDispenseStatus.ONHOLD),
                dispense(MEDICATION_REQUEST_ID, MedicationDispense.MedicationDispenseStatus.DECLINED));

        assertFalse(medicationDispenseHandler.hasDispensedQuantity(MEDICATION_REQUEST_ID));
    }

    @Test
    void shouldReportDispensedWhenAPartialDispenseWasMade() {
        responseBundle = bundleOf(
                dispense(MEDICATION_REQUEST_ID, MedicationDispense.MedicationDispenseStatus.COMPLETED),
                dispense(MEDICATION_REQUEST_ID, MedicationDispense.MedicationDispenseStatus.DECLINED));

        assertTrue(medicationDispenseHandler.hasDispensedQuantity(MEDICATION_REQUEST_ID));
    }

    @Test
    void shouldIgnoreDispensesOfAnotherPrescription() {
        // as if the server had ignored the search parameter and returned every dispense
        responseBundle = bundleOf(
                dispense(OTHER_MEDICATION_REQUEST_ID, MedicationDispense.MedicationDispenseStatus.COMPLETED),
                dispense(MEDICATION_REQUEST_ID, MedicationDispense.MedicationDispenseStatus.DECLINED));

        assertFalse(medicationDispenseHandler.hasDispensedQuantity(MEDICATION_REQUEST_ID));
    }

    private static MedicationDispense dispense(
            String medicationRequestId, MedicationDispense.MedicationDispenseStatus status) {
        MedicationDispense dispense = new MedicationDispense();
        dispense.setStatus(status);
        dispense.addAuthorizingPrescription(new Reference("MedicationRequest/" + medicationRequestId));
        return dispense;
    }

    private static Bundle bundleOf(MedicationDispense... dispenses) {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        for (MedicationDispense dispense : dispenses) {
            bundle.addEntry().setResource(dispense);
        }
        return bundle;
    }
}

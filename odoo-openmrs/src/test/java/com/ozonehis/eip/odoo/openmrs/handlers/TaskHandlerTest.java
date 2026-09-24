/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.TaskHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs the handler against a real HAPI client and a local HTTP server, so the assertion is on the
 * query string that actually goes over the wire, not on a mocked criterion.
 */
class TaskHandlerTest {

    private static final FhirContext FHIR_CONTEXT = FhirContext.forR4();

    private static final String SERVICE_REQUEST_ID = "5f0c9b52-2c8e-4a39-9d2f-8a1b3c4d5e6f";

    private static final String OTHER_SERVICE_REQUEST_ID = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

    private HttpServer server;

    private final AtomicReference<String> requestQuery = new AtomicReference<>();

    private Bundle responseBundle = new Bundle();

    private TaskHandler taskHandler;

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
        taskHandler = new TaskHandler();
        taskHandler.setOpenmrsFhirClient(client);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldSearchWithTypedServiceRequestReference() {
        taskHandler.getTaskByServiceRequestId(SERVICE_REQUEST_ID);

        String query = URLDecoder.decode(requestQuery.get(), StandardCharsets.UTF_8);
        assertEquals("based-on=ServiceRequest/" + SERVICE_REQUEST_ID, query);
    }

    @Test
    void shouldIgnoreTasksBasedOnAnotherServiceRequest() {
        responseBundle = bundleOf(task("t-other", OTHER_SERVICE_REQUEST_ID), task("t-mine", SERVICE_REQUEST_ID));

        Task result = taskHandler.getTaskByServiceRequestId(SERVICE_REQUEST_ID);

        assertNotNull(result);
        assertEquals("t-mine", result.getIdElement().getIdPart());
    }

    @Test
    void shouldReturnNullWhenNoTaskIsBasedOnTheServiceRequest() {
        responseBundle = bundleOf(task("t-other", OTHER_SERVICE_REQUEST_ID));

        assertNull(taskHandler.getTaskByServiceRequestId(SERVICE_REQUEST_ID));
    }

    private static Task task(String id, String serviceRequestId) {
        Task task = new Task();
        task.setId(id);
        task.setStatus(Task.TaskStatus.ACCEPTED);
        task.setIntent(Task.TaskIntent.ORDER);
        task.setBasedOn(List.of(new Reference("ServiceRequest/" + serviceRequestId)));
        return task;
    }

    private static Bundle bundleOf(Task... tasks) {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        for (Task task : tasks) {
            bundle.addEntry().setResource(task);
        }
        return bundle;
    }
}

/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs;

import static com.ozonehis.eip.odoo.openmrs.FakeServiceRequestSearch.bundleOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.Test;

/** UVL-EMR#876: the radiology search asks the server for imaging orders only, page by page. */
class RadiologyOrderSearchTest {

    private static final String SINCE = "2026-11-15T00:00:00Z";

    private final FakeServiceRequestSearch server = new FakeServiceRequestSearch();

    private static ServiceRequest order(String id) {
        ServiceRequest serviceRequest = new ServiceRequest();
        serviceRequest.setId(id);
        return serviceRequest;
    }

    private static List<String> ids(List<ServiceRequest> serviceRequests) {
        return serviceRequests.stream().map(sr -> sr.getIdElement().getIdPart()).collect(Collectors.toList());
    }

    @Test
    void asksForTheRadiologyConceptsAndTheWindowOnly() {
        RadiologyOrderSearch.find(server.client, new RadiologyConcepts("e3dea2c8-62c6-4487-bdaa-1d009642f7ad"), SINCE);

        assertEquals(1, server.searches.size());
        assertEquals(
                "e3dea2c8-62c6-4487-bdaa-1d009642f7ad", server.searches.get(0).get("code"));
        assertEquals("gt" + SINCE, server.searches.get(0).get("_lastUpdated"));
        assertEquals(List.of(RadiologyOrderSearch.PAGE_SIZE), server.pageSizes);
    }

    @Test
    void sendsEveryConceptAcrossShortSearches() {
        RadiologyConcepts concepts = new RadiologyConcepts();
        assertTrue(concepts.getUuids().size() > RadiologyOrderSearch.CODES_PER_SEARCH);

        RadiologyOrderSearch.find(server.client, concepts, SINCE);

        assertTrue(server.searches.size() > 1);
        Set<String> sent = server.searches.stream()
                .flatMap(asked -> Arrays.stream(asked.get("code").split(",")))
                .collect(Collectors.toSet());
        assertEquals(concepts.getUuids(), sent);
        server.searches.forEach(
                asked -> assertTrue(asked.get("code").split(",").length <= RadiologyOrderSearch.CODES_PER_SEARCH));
    }

    @Test
    void followsTheNextLinksUntilThereAreNone() {
        Bundle first = bundleOf(true, order("a"), order("b"));
        Bundle second = bundleOf(true, order("c"));
        Bundle third = bundleOf(false, order("d"));
        server.nextPages.put(first, second);
        server.nextPages.put(second, third);
        server.firstPage = asked -> first;

        List<ServiceRequest> found = RadiologyOrderSearch.find(
                server.client, new RadiologyConcepts("e3dea2c8-62c6-4487-bdaa-1d009642f7ad"), SINCE);

        assertEquals(List.of("a", "b", "c", "d"), ids(found));
    }

    @Test
    void anOrderReturnedByTwoSearchesIsReturnedOnce() {
        server.firstPage = asked -> bundleOf(false, order("a"));

        List<ServiceRequest> found = RadiologyOrderSearch.find(server.client, new RadiologyConcepts(), SINCE);

        assertEquals(List.of("a"), ids(found));
    }

    @Test
    void aFailedSearchIsNotAnEmptyAnswer() {
        server.firstPage = asked -> {
            throw new FhirClientConnectionException("timed out", new SocketTimeoutException("Read timed out"));
        };

        assertThrows(
                FhirClientConnectionException.class,
                () -> RadiologyOrderSearch.find(server.client, new RadiologyConcepts(), SINCE));
    }

    @Test
    void describeNamesTheRootCauseOnOneLine() {
        Exception failure = new FhirClientConnectionException(
                "HAPI-1361: Failed to parse response", new SocketTimeoutException("Read timed out"));

        assertEquals("SocketTimeoutException: Read timed out", RadiologyOrderSearch.describe(failure));
    }
}

/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.DateClientParam;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ServiceRequest;

/**
 * Finds the radiology ServiceRequests changed since a given instant, asking OpenMRS for imaging
 * orders only (UVL-EMR#876).
 *
 * <p>The payment poll and the unpaid-imaging audit used to ask for EVERY ServiceRequest changed in the
 * last seven days and discard the laboratory and procedure orders afterwards. OpenMRS's FHIR2 module
 * answers that search in time proportional to the number of orders in the window: ~17 s and 1.1 MB for
 * 570 orders on UAT, which is past the HAPI client's read timeout, so every poll failed.
 *
 * <p>Measured on UAT, with the same {@code _lastUpdated} window of seven days:
 *
 * <pre>
 *     _lastUpdated only                         17.3 s   570 orders
 *     _lastUpdated + code=&lt;39 radiology uuids&gt;    0.19 s     6 orders
 * </pre>
 *
 * <p>{@code code} is honoured by FHIR2 as a token search on the concept uuid, comma-separated values
 * being an OR (so is the {@code _lastUpdated} bound, which it combines with). {@code status} and
 * {@code category} are rejected with HTTP 400, which is why callers keep their own status check. The
 * callers also keep their own {@link RadiologyConcepts#isRadiologyOrder} check: FHIR2 silently drops
 * search parameters it does not support, and a dropped filter must degrade to "slow", not to "wrong".
 *
 * <p>The answer is read page by page, so a busy week cannot turn one request into one oversized one.
 * The uuids are sent in several searches when there are many, to keep each URL short.
 */
public final class RadiologyOrderSearch {

    /** Concept uuids per search; 20 uuids is ~800 characters of URL. */
    static final int CODES_PER_SEARCH = 20;

    /** Entries per page. */
    static final int PAGE_SIZE = 100;

    /** Upper bound on pages followed per search, so a paging loop cannot run away. */
    static final int MAX_PAGES = 100;

    private RadiologyOrderSearch() {}

    /**
     * Every radiology ServiceRequest with {@code _lastUpdated} after {@code since}, without duplicates.
     *
     * @param since an ISO-8601 instant
     * @throws RuntimeException when any search or page fails: a partial answer is never returned, since
     *     callers read "absent from the answer" as "left the window"
     */
    public static List<ServiceRequest> find(IGenericClient client, RadiologyConcepts concepts, String since) {
        List<String> uuids = new ArrayList<>(concepts.getUuids());
        Map<String, ServiceRequest> found = new LinkedHashMap<>();
        for (int from = 0; from < uuids.size(); from += CODES_PER_SEARCH) {
            List<String> codes = uuids.subList(from, Math.min(from + CODES_PER_SEARCH, uuids.size()));
            Bundle page = client.search()
                    .forResource(ServiceRequest.class)
                    .where(new TokenClientParam("code").exactly().codes(codes))
                    .and(new DateClientParam("_lastUpdated").after().second(since))
                    .count(PAGE_SIZE)
                    .returnBundle(Bundle.class)
                    .execute();
            for (int pages = 0; page != null && pages < MAX_PAGES; pages++) {
                for (Bundle.BundleEntryComponent entry : page.getEntry()) {
                    if (entry.getResource() instanceof ServiceRequest) {
                        ServiceRequest serviceRequest = (ServiceRequest) entry.getResource();
                        String id = serviceRequest.getIdElement().getIdPart();
                        found.putIfAbsent(id != null ? id : "#" + found.size(), serviceRequest);
                    }
                }
                if (page.getLink(Bundle.LINK_NEXT) == null) {
                    break;
                }
                page = client.loadPage().next(page).execute();
            }
        }
        return new ArrayList<>(found.values());
    }

    /** The message of the innermost cause, for a single log line instead of a stack trace. */
    public static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}

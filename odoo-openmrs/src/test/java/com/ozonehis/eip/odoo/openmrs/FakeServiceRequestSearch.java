/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.IGetPage;
import ca.uhn.fhir.rest.gclient.IGetPageTyped;
import ca.uhn.fhir.rest.gclient.IQuery;
import ca.uhn.fhir.rest.gclient.IUntypedQuery;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ServiceRequest;

/**
 * A stand-in for the OpenMRS FHIR server's ServiceRequest search, for tests of the code that searches
 * it. Each search is answered by {@link #firstPage}; a bundle carrying a next link is followed through
 * {@link #nextPages}. What was asked (parameter name to value, one map per search) is in
 * {@link #searches}, and the page size requested in {@link #pageSizes}.
 */
public class FakeServiceRequestSearch {

    private static final FhirContext FHIR = FhirContext.forR4();

    public final IGenericClient client = mock(IGenericClient.class);

    public final List<Map<String, String>> searches = new ArrayList<>();

    public final List<Integer> pageSizes = new ArrayList<>();

    public final Map<Bundle, Bundle> nextPages = new HashMap<>();

    /** Answers a search given what it asked; may throw to simulate a failed search. */
    public Function<Map<String, String>, Bundle> firstPage = asked -> new Bundle();

    @SuppressWarnings({"unchecked", "rawtypes"})
    public FakeServiceRequestSearch() {
        IUntypedQuery<IBaseBundle> untyped = mock(IUntypedQuery.class);
        Map<String, String> asking = new HashMap<>();
        IQuery query = mock(IQuery.class, invocation -> {
            switch (invocation.getMethod().getName()) {
                case "where":
                case "and":
                    ca.uhn.fhir.rest.gclient.ICriterionInternal criterion = invocation.getArgument(0);
                    asking.put(criterion.getParameterName(), criterion.getParameterValue(FHIR));
                    return invocation.getMock();
                case "count":
                    pageSizes.add(invocation.getArgument(0));
                    return invocation.getMock();
                case "returnBundle":
                    return invocation.getMock();
                case "execute":
                    Map<String, String> asked = new HashMap<>(asking);
                    asking.clear();
                    searches.add(asked);
                    return firstPage.apply(asked);
                default:
                    return null;
            }
        });
        when(client.search()).thenReturn((IUntypedQuery) untyped);
        when(untyped.forResource(ServiceRequest.class)).thenReturn(query);

        IGetPage getPage = mock(IGetPage.class);
        when(client.loadPage()).thenReturn(getPage);
        when(getPage.next(any(Bundle.class))).thenAnswer(invocation -> {
            Bundle current = invocation.getArgument(0);
            IGetPageTyped<Bundle> typed = mock(IGetPageTyped.class);
            when(typed.execute()).thenAnswer(x -> nextPages.get(current));
            return typed;
        });
    }

    /** A bundle of the given ServiceRequests, with a next link when {@code hasNext}. */
    public static Bundle bundleOf(boolean hasNext, ServiceRequest... serviceRequests) {
        Bundle bundle = new Bundle();
        for (ServiceRequest serviceRequest : serviceRequests) {
            bundle.addEntry().setResource(serviceRequest);
        }
        if (hasNext) {
            bundle.addLink().setRelation(Bundle.LINK_NEXT).setUrl("http://openmrs/next");
        }
        return bundle;
    }
}

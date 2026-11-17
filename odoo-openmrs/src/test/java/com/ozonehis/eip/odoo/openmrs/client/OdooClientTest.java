/*
 * Copyright © 2021, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class OdooClientTest {

    private XmlRpcClient xmlRpcClient;

    private OdooClient odooClient;

    @BeforeEach
    void setup() throws Exception {
        xmlRpcClient = mock(XmlRpcClient.class);
        when(xmlRpcClient.execute(any(XmlRpcClientConfig.class), eq("authenticate"), anyList()))
                .thenReturn(2);
        odooClient = new OdooClient("http://odoo:8069", "odoo", "admin", "secret");
        ReflectionTestUtils.setField(odooClient, "client", xmlRpcClient);
    }

    private void userContextIs(Object context) throws XmlRpcException {
        when(xmlRpcClient.execute(eq("execute_kw"), anyList())).thenAnswer(invocation -> {
            List<?> params = invocation.getArgument(1);
            if ("context_get".equals(params.get(4))) {
                if (context instanceof XmlRpcException e) {
                    throw e;
                }
                return context;
            }
            return switch ((String) params.get(4)) {
                case "create" -> 7;
                case "write", "unlink" -> true;
                default -> new Object[0];
            };
        });
    }

    @SuppressWarnings("unchecked")
    private List<List<Object>> executeKwCalls() throws XmlRpcException {
        ArgumentCaptor<List<Object>> captor = ArgumentCaptor.forClass(List.class);
        verify(xmlRpcClient, atLeastOnce()).execute(eq("execute_kw"), captor.capture());
        return captor.getAllValues();
    }

    @Test
    void shouldSendTheOdooUsersLanguageOnEveryCall() throws Exception {
        Map<String, Object> context = new HashMap<>();
        context.put("lang", "fr_FR");
        context.put("tz", "Africa/Bujumbura");
        context.put("uid", 2);
        userContextIs(context);

        odooClient.create("sale.order", List.of(Map.of("partner_id", 1)));
        odooClient.write("sale.order", List.of(List.of(7), Map.of("note", "x")));
        odooClient.delete("sale.order.line", List.of(List.of(7)));
        odooClient.search("res.country", List.of("name", "=", "Burundi"));
        odooClient.searchAndRead("sale.order", List.of("state", "=", "draft"), List.of("name"));

        List<List<Object>> calls = executeKwCalls();
        // context_get once, then the five calls
        assertEquals(6, calls.size());
        assertEquals("res.users", calls.get(0).get(3));
        assertEquals("context_get", calls.get(0).get(4));
        verify(xmlRpcClient, times(1)).execute(any(XmlRpcClientConfig.class), eq("authenticate"), anyList());
        for (List<Object> call : calls.subList(1, calls.size())) {
            Map<?, ?> kwargs = (Map<?, ?>) call.get(6);
            assertEquals(Map.of("lang", "fr_FR"), kwargs.get("context"), "context on " + call.get(4));
        }
        Map<?, ?> searchReadKwargs = (Map<?, ?>) calls.get(5).get(6);
        assertEquals(List.of("name"), searchReadKwargs.get("fields"));
    }

    @Test
    void shouldSendNoContextWhenTheUsersLanguageCannotBeRead() throws Exception {
        userContextIs(new XmlRpcException("Access denied"));

        odooClient.create("sale.order", List.of(Map.of("partner_id", 1)));
        odooClient.searchAndRead("sale.order", List.of("state", "=", "draft"), List.of("name"));
        odooClient.search("res.country", List.of("name", "=", "Burundi"));

        List<List<Object>> calls = executeKwCalls();
        assertEquals(4, calls.size());
        // as before the change: no kwargs on create and search, only the fields on search_read
        assertEquals(6, calls.get(1).size());
        assertEquals(Map.of("fields", List.of("name")), calls.get(2).get(6));
        assertEquals(6, calls.get(3).size());
        assertFalse(calls.stream().skip(1).anyMatch(call -> call.size() > 6
                && ((Map<?, ?>) call.get(6)).containsKey("context")));
    }
}

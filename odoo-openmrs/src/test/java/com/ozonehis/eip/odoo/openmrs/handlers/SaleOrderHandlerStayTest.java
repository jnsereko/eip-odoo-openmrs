/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.handlers;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.ozonehis.eip.odoo.openmrs.Constants;
import com.ozonehis.eip.odoo.openmrs.client.OdooClient;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.SaleOrderHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.SaleOrderLineHandler;
import com.ozonehis.eip.odoo.openmrs.model.Product;
import com.ozonehis.eip.odoo.openmrs.model.SaleOrder;
import com.ozonehis.eip.odoo.openmrs.model.SaleOrderLine;
import java.util.List;
import java.util.Map;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

/** The stay line of UVL-EMR#246 on the visit's quotation. */
class SaleOrderHandlerStayTest {

    private static final String VISIT = "visit-uuid";

    private static final int QUOTATION = 7;

    private static final int CONFIRMED = 3;

    private static final int BED = 558;

    @Mock
    private OdooClient odooClient;

    @Mock
    private SaleOrderLineHandler saleOrderLineHandler;

    @InjectMocks
    private SaleOrderHandler saleOrderHandler;

    private AutoCloseable mocks;

    private ProducerTemplate producerTemplate;

    private Product bed;

    private SaleOrder quotation;

    @BeforeEach
    void setUp() {
        mocks = openMocks(this);
        producerTemplate = mock(ProducerTemplate.class);
        bed = new Product();
        bed.setProductResId(BED);
        quotation = new SaleOrder();
        quotation.setOrderId(QUOTATION);
        quotation.setOrderClientOrderRef(VISIT);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    private void visitLines(Object[]... lines) {
        when(odooClient.searchAndRead(
                        eq(Constants.SALE_ORDER_LINE_MODEL),
                        eq(List.of(
                                asList("order_id.client_order_ref", "=", VISIT),
                                asList("order_id.state", "!=", "cancel"),
                                asList("product_id", "=", BED))),
                        any()))
                .thenReturn(java.util.Arrays.stream(lines)
                        .map(l -> Map.of(
                                "id", l[0], "order_id", new Object[] {l[1], "S0000" + l[1]}, "product_uom_qty", l[2]))
                        .toArray());
    }

    @Test
    void nightsToBill_subtractWhatAConfirmedSaleOrderBills() {
        visitLines(new Object[] {1, CONFIRMED, 2.0}, new Object[] {2, QUOTATION, 1.0});

        assertEquals(3f, saleOrderHandler.getStayNightsToBill(bed, 5, QUOTATION, VISIT, null));
    }

    @Test
    void nightsToBill_neverBelowZero() {
        visitLines(new Object[] {1, CONFIRMED, 4.0});

        assertEquals(0f, saleOrderHandler.getStayNightsToBill(bed, 2, QUOTATION, VISIT, null));
    }

    @Test
    void noLine_addsOneWithTheNights() {
        when(saleOrderLineHandler.getSaleOrderLineIfExists(QUOTATION, BED)).thenReturn(null);

        saleOrderHandler.upsertStayLine(quotation, bed, 2f, null, producerTemplate);

        ArgumentCaptor<SaleOrderLine> line = ArgumentCaptor.forClass(SaleOrderLine.class);
        verify(producerTemplate).sendBody(eq("direct:odoo-create-sale-order-line-route"), line.capture());
        assertEquals(QUOTATION, line.getValue().getSaleOrderLineOrderId());
        assertEquals(BED, line.getValue().getSaleOrderLineProductId());
        assertEquals(2f, line.getValue().getSaleOrderLineProductUomQty());
        assertEquals(1, line.getValue().getSaleOrderLineProductUom());
    }

    @Test
    void existingLine_isSetToTheNights() {
        SaleOrderLine existing = new SaleOrderLine();
        existing.setSaleOrderLineId(40);
        existing.setSaleOrderLineProductUomQty(1f);
        when(saleOrderLineHandler.getSaleOrderLineIfExists(QUOTATION, BED)).thenReturn(existing);

        saleOrderHandler.upsertStayLine(quotation, bed, 3f, null, producerTemplate);

        SaleOrderLine update = new SaleOrderLine();
        update.setSaleOrderLineId(40);
        update.setSaleOrderLineProductUomQty(3f);
        verify(saleOrderLineHandler)
                .sendSaleOrderLine(producerTemplate, "direct:odoo-update-sale-order-line-route", update);
    }

    @Test
    void sameNights_changesNothing() {
        SaleOrderLine existing = new SaleOrderLine();
        existing.setSaleOrderLineId(40);
        existing.setSaleOrderLineProductUomQty(3f);
        when(saleOrderLineHandler.getSaleOrderLineIfExists(QUOTATION, BED)).thenReturn(existing);

        saleOrderHandler.upsertStayLine(quotation, bed, 3f, null, producerTemplate);

        verify(saleOrderLineHandler, never()).sendSaleOrderLine(any(), any(), any());
        verifyNoInteractions(producerTemplate);
    }

    @Test
    void noNightLeft_removesTheLine() {
        SaleOrderLine existing = new SaleOrderLine();
        existing.setSaleOrderLineId(40);
        existing.setSaleOrderLineProductUomQty(1f);
        when(saleOrderLineHandler.getSaleOrderLineIfExists(QUOTATION, BED)).thenReturn(existing);

        saleOrderHandler.upsertStayLine(quotation, bed, 0f, null, producerTemplate);

        verify(saleOrderLineHandler)
                .sendSaleOrderLine(producerTemplate, "direct:odoo-delete-sale-order-line-route", existing);
    }

    @Test
    void noNightAndNoLine_changesNothing() {
        when(saleOrderLineHandler.getSaleOrderLineIfExists(QUOTATION, BED)).thenReturn(null);

        saleOrderHandler.upsertStayLine(quotation, bed, 0f, null, producerTemplate);

        verifyNoInteractions(producerTemplate);
        verify(saleOrderLineHandler, never()).sendSaleOrderLine(any(), any(), any());
    }
}

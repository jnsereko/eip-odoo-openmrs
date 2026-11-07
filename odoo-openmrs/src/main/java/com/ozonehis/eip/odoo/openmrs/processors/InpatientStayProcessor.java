/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.processors;

import com.ozonehis.eip.odoo.openmrs.handlers.odoo.CompanyHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.PartnerHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.ProductHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.SaleOrderHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.EncounterHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.InpatientStayHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.InpatientStayHandler.InpatientStay;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.PatientHandler;
import com.ozonehis.eip.odoo.openmrs.model.Partner;
import com.ozonehis.eip.odoo.openmrs.model.Product;
import com.ozonehis.eip.odoo.openmrs.model.SaleOrder;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bills each inpatient stay's nights on its visit's quotation (UVL-EMR#246).
 *
 * <p>Nothing in OpenMRS orders a night, so no event carries it: this runs on a schedule, recounts every stay from
 * its Admission, Transfer and Discharge encounters ({@link InpatientStayHandler}) and sets one line per room-class
 * product on the visit's draft quotation to the nights not already billed on a confirmed sale order. A
 * confirmed sale order is never changed. A stay with no quotation yet gets one, as an order would. A stay that
 * fails is logged and the others go on.
 */
@Slf4j
@Setter
@Component
public class InpatientStayProcessor implements Processor {

    @Value("${enable.encounter.location.to.company.mapping:false}")
    private boolean enableEncounterLocationCompanyMapping;

    @Autowired
    private InpatientStayHandler inpatientStayHandler;

    @Autowired
    private SaleOrderHandler saleOrderHandler;

    @Autowired
    private ProductHandler productHandler;

    @Autowired
    private PartnerHandler partnerHandler;

    @Autowired
    private PatientHandler patientHandler;

    @Autowired
    private EncounterHandler encounterHandler;

    @Autowired
    private CompanyHandler companyHandler;

    @Override
    public void process(Exchange exchange) {
        if (!inpatientStayHandler.isConfigured()) {
            log.warn("Inpatient stay billing is enabled but eip.inpatient.stay.default.product is not set: nothing"
                    + " is billed");
            return;
        }
        List<InpatientStay> stays = inpatientStayHandler.getStays(Instant.now());
        Map<String, Product> products = new HashMap<>();
        int billed = 0;
        int failed = 0;
        try (ProducerTemplate producerTemplate = exchange.getContext().createProducerTemplate()) {
            for (InpatientStay stay : stays) {
                try {
                    bill(stay, products, producerTemplate);
                    billed++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Visit {}: inpatient stay not billed this run: {}", stay.visitUuid(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Inpatient stay billing failed: {}", e.getMessage(), e);
            return;
        }
        log.info("Inpatient stays: {} visit(s) checked, {} failed", billed, failed);
    }

    void bill(InpatientStay stay, Map<String, Product> products, ProducerTemplate producerTemplate) {
        String visitUuid = stay.visitUuid();
        Encounter admission = null;
        Integer companyId = null;
        if (enableEncounterLocationCompanyMapping) {
            admission = admissionEncounter(stay);
            companyId = companyHandler.getCompanyIdByEncounterLocation(admission);
            if (companyId == null) {
                log.warn("Visit {}: no company for the admission's location, stay not billed", visitUuid);
                return;
            }
        }
        SaleOrder quotation = saleOrderHandler.getDraftSaleOrderIfExistsByVisitId(visitUuid, companyId);
        for (Map.Entry<String, Integer> entry : stay.nightsByProduct().entrySet()) {
            Product product = products.computeIfAbsent(entry.getKey(), productHandler::getProductByExternalId);
            if (product == null) {
                if (entry.getValue() > 0) {
                    log.warn(
                            "Visit {}: no Odoo product with external id {}, {} night(s) not billed",
                            visitUuid,
                            entry.getKey(),
                            entry.getValue());
                }
                continue;
            }
            float toBill = saleOrderHandler.getStayNightsToBill(
                    product, entry.getValue(), quotation == null ? null : quotation.getOrderId(), visitUuid, companyId);
            if (quotation == null) {
                if (toBill <= 0) {
                    continue;
                }
                if (admission == null) {
                    admission = admissionEncounter(stay);
                }
                Patient patient = patientHandler.getPatientByPatientID(stay.patientUuid());
                if (patient == null) {
                    log.warn("Visit {}: patient {} not found, stay not billed", visitUuid, stay.patientUuid());
                    return;
                }
                Partner partner = partnerHandler.createOrUpdatePartner(producerTemplate, patient, companyId);
                quotation = saleOrderHandler.createStayQuotation(
                        admission, partner, visitUuid, patient.getIdPart(), companyId, producerTemplate);
                if (quotation == null) {
                    log.warn("Visit {}: the quotation could not be created or read back, stay not billed", visitUuid);
                    return;
                }
            }
            saleOrderHandler.upsertStayLine(quotation, product, toBill, companyId, producerTemplate);
        }
    }

    private Encounter admissionEncounter(InpatientStay stay) {
        Encounter encounter = encounterHandler.getEncounterByEncounterID(stay.admissionEncounterUuid());
        if (encounter == null || !encounter.hasPartOf()) {
            throw new IllegalStateException("admission encounter " + stay.admissionEncounterUuid()
                    + " not found in FHIR, or not part of a visit");
        }
        return encounter;
    }
}

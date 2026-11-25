/*
 * Copyright © 2026, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.processors;

import com.ozonehis.eip.odoo.openmrs.Constants;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.SaleOrderHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.MedicationDispenseHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.MedicationDispenseHandler.DispenseChange;
import com.ozonehis.eip.odoo.openmrs.handlers.openmrs.MedicationDispenseHandler.OrderVisit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Set;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bills what the pharmacy dispensed (UVL-EMR#605).
 *
 * <p>The dispensing app changes a prescription only when it is dispensed in full, paused or closed, so a partial
 * dispense, a top-up that does not complete it, or an edited or deleted dispense sends no prescription event.
 * This runs on a schedule, reads the dispenses created, edited or deleted since the last run, and sends each of
 * their prescriptions through the MedicationRequest route again, marked so that its drug line is set to what
 * the visit's prescriptions of the drug now bill.
 *
 * <p>Each run reads back a little before the previous one, so that a dispense saved while it ran is not missed;
 * a dispense already billed at the same change is not sent twice. A prescription that fails is tried again on
 * the next runs. On start, the look-back window is read again: billing the same dispense again changes nothing.
 *
 * <p>After {@code eip.medication.dispense.max.attempts} failed runs a prescription is given up (UVL-EMR#939): it is
 * logged at ERROR with the marker {@value #DROPPED_MARKER}, and a note naming the drug is left on the visit's draft
 * quotation, so that the medicine is added by hand. Restarting the bridge reads the look-back window again, which
 * bills it once the cause is fixed.
 */
@Slf4j
@Setter
@Component
public class MedicationDispenseProcessor implements Processor {

    static final String MEDICATION_REQUEST_ROUTE = "direct:fhir-medicationrequest";

    // Stable text to alert on in the bridge log
    static final String DROPPED_MARKER = "BILLING_DROPPED";

    @Value("${eip.medication.dispense.lookback.minutes:1440}")
    private long lookbackMinutes;

    @Value("${eip.medication.dispense.overlap.seconds:120}")
    private long overlapSeconds;

    @Value("${eip.medication.dispense.max.attempts:5}")
    private int maxAttempts;

    @Value("${odoo.billing.dropped.note:" + DEFAULT_DROPPED_NOTE + "}")
    private String droppedNote;

    static final String DEFAULT_DROPPED_NOTE = "Le m\u00e9dicament %s n'a pas pu \u00eatre factur\u00e9"
            + " automatiquement (prescription %s). Ajoutez-le \u00e0 la main sur ce devis.";

    @Autowired
    private MedicationDispenseHandler medicationDispenseHandler;

    @Autowired
    private SaleOrderHandler saleOrderHandler;

    // Database time the previous run read up to; null before the first run
    private Instant checkedUntil;

    // The change of each recent dispense already billed, so that the overlap does not send it again
    private final Map<String, Instant> billedChanges = new HashMap<>();

    // Prescriptions that failed, with their attempts so far
    private final Map<String, Integer> failedAttempts = new LinkedHashMap<>();

    // Why each failed prescription failed on its latest attempt
    private final Map<String, String> failureReasons = new HashMap<>();

    @Override
    public void process(Exchange exchange) {
        Instant now = medicationDispenseHandler.getDatabaseNow();
        Instant since = checkedUntil == null
                ? now.minus(Duration.ofMinutes(lookbackMinutes))
                : checkedUntil.minus(Duration.ofSeconds(overlapSeconds));

        Map<String, List<DispenseChange>> changesByOrder = new LinkedHashMap<>();
        for (DispenseChange change : medicationDispenseHandler.getDispensesChangedSince(since)) {
            if (!change.changed().equals(billedChanges.get(change.dispenseUuid()))) {
                changesByOrder
                        .computeIfAbsent(change.orderUuid(), k -> new ArrayList<>())
                        .add(change);
            }
        }
        Set<String> orders = new LinkedHashSet<>(failedAttempts.keySet());
        orders.addAll(changesByOrder.keySet());
        if (!orders.isEmpty()) {
            log.info("Medication dispenses: {} prescription(s) to bill again", orders.size());
        }

        try (ProducerTemplate producerTemplate = exchange.getContext().createProducerTemplate()) {
            for (String orderUuid : orders) {
                String failure = bill(producerTemplate, orderUuid);
                if (failure == null) {
                    failedAttempts.remove(orderUuid);
                    failureReasons.remove(orderUuid);
                    for (DispenseChange change : changesByOrder.getOrDefault(orderUuid, List.of())) {
                        billedChanges.put(change.dispenseUuid(), change.changed());
                    }
                } else {
                    int attempts = failedAttempts.getOrDefault(orderUuid, 0) + 1;
                    if (attempts >= maxAttempts) {
                        failedAttempts.remove(orderUuid);
                        failureReasons.remove(orderUuid);
                        log.error(
                                "{} order={} reason={}: MedicationRequest {}: its dispense could not be billed after"
                                        + " {} attempts. Check its drug line on the visit's quotation in Odoo.",
                                DROPPED_MARKER,
                                orderUuid,
                                failure,
                                orderUuid,
                                attempts);
                        noteDroppedOnQuotation(orderUuid);
                    } else {
                        failedAttempts.put(orderUuid, attempts);
                        failureReasons.put(orderUuid, failure);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Medication dispenses: could not bill the dispenses: {}", e.getMessage(), e);
            return;
        }

        checkedUntil = now;
        // What the next run reads back to is all that needs remembering
        Instant horizon = now.minus(Duration.ofSeconds(overlapSeconds));
        billedChanges.values().removeIf(changed -> changed.isBefore(horizon));
    }

    /** Leaves a note naming the drug on the visit's draft quotation; the log line is the signal if this fails. */
    private void noteDroppedOnQuotation(String orderUuid) {
        try {
            OrderVisit orderVisit = medicationDispenseHandler.getVisitOfOrder(orderUuid);
            if (orderVisit == null) {
                log.warn("{} order={}: no visit found, no note left on a quotation", DROPPED_MARKER, orderUuid);
                return;
            }
            String template = droppedNote == null || droppedNote.isBlank() ? DEFAULT_DROPPED_NOTE : droppedNote;
            String note = String.format(template, orderVisit.drugName(), orderUuid);
            if (!saleOrderHandler.postNoteOnDraftSaleOrder(orderVisit.visitUuid(), note)) {
                log.warn("{} order={}: the visit has no draft quotation, no note left", DROPPED_MARKER, orderUuid);
            }
        } catch (Exception e) {
            log.warn(
                    "{} order={}: could not leave a note on the quotation: {}",
                    DROPPED_MARKER,
                    orderUuid,
                    e.getMessage());
        }
    }

    /** @return null when billed, else why not (see {@link #rootCauseReason}) */
    private String bill(ProducerTemplate producerTemplate, String orderUuid) {
        MedicationRequest medicationRequest = new MedicationRequest();
        medicationRequest.setId(orderUuid);
        Map<String, Object> headers = new HashMap<>();
        headers.put(org.openmrs.eip.fhir.Constants.HEADER_FHIR_EVENT_TYPE, "u");
        headers.put(Constants.HEADER_MEDICATION_DISPENSE_SYNC, true);
        try {
            producerTemplate.sendBodyAndHeaders(MEDICATION_REQUEST_ROUTE, medicationRequest, headers);
            return null;
        } catch (Exception e) {
            log.warn("MedicationRequest {}: could not bill its dispense: {}", orderUuid, e.getMessage());
            return rootCauseReason(e);
        }
    }

    static final int MAX_REASON_LENGTH = 300;

    /** The message of the deepest cause that has one, on one line and cut to {@value #MAX_REASON_LENGTH} characters. */
    static String rootCauseReason(Throwable error) {
        String message = null;
        Throwable current = error;
        for (int depth = 0; current != null && depth < 25; depth++) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        if (message == null) {
            return error.getClass().getSimpleName();
        }
        String oneLine = message.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.joining(" "));
        return oneLine.length() > MAX_REASON_LENGTH ? oneLine.substring(0, MAX_REASON_LENGTH) + "..." : oneLine;
    }
}

/*
 * Copyright © 2021, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.config;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IRestfulClientFactory;
import com.ozonehis.eip.odoo.openmrs.ProductSynchronizer;
import com.ozonehis.eip.odoo.openmrs.RadiologyConcepts;
import com.ozonehis.eip.odoo.openmrs.client.OdooFhirClient;
import com.ozonehis.eip.odoo.openmrs.client.OpenmrsRestClient;
import org.openmrs.eip.app.config.AppConfig;
import org.openmrs.eip.fhir.spring.OpenmrsFhirAppConfig;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Import the {@link AppConfig} class to ensure that the required beans are created.
 */
@Configuration
@Import({AppConfig.class, OpenmrsFhirAppConfig.class})
@EnableScheduling
public class EIPAppConfig {

    @Bean
    public ProductSynchronizer productCatalogSynchronizer(
            OdooFhirClient odooFhirClient, IGenericClient openmrsFhirClient, OpenmrsRestClient openmrsRestClient) {
        return new ProductSynchronizer(odooFhirClient, openmrsFhirClient, openmrsRestClient);
    }

    /**
     * Explicit timeouts on the OpenMRS FHIR client, which the camel-openmrs-fhir library builds with
     * HAPI's defaults (10 s connect, 10 s socket read). A ServiceRequest search that took longer failed
     * every poll of the radiology payment job (UVL-EMR#876), and the same default would hit any other
     * read that grows with the data. The connect timeout stays short: an unreachable OpenMRS should
     * fail fast, a busy one should be given time to answer.
     *
     * <p>HAPI applies a factory's timeouts to the clients it has already created, so setting them here
     * covers the shared bean after the fact.
     */
    @Bean
    public InitializingBean openmrsFhirClientTimeouts(
            IGenericClient openmrsFhirClient,
            @Value("${openmrs.fhir.connect.timeout.ms:10000}") int connectTimeoutMs,
            @Value("${openmrs.fhir.socket.timeout.ms:120000}") int socketTimeoutMs) {
        return () -> {
            IRestfulClientFactory factory = openmrsFhirClient.getFhirContext().getRestfulClientFactory();
            factory.setConnectTimeout(connectTimeoutMs);
            factory.setSocketTimeout(socketTimeoutMs);
        };
    }

    /**
     * The radiology concept list, from RADIOLOGY_CONCEPT_UUIDS (see {@link RadiologyConcepts}). The
     * empty default means "unset", which RadiologyConcepts turns into its built-in list.
     */
    @Bean
    public RadiologyConcepts radiologyConcepts(@Value("${" + RadiologyConcepts.PROPERTY + ":}") String configured) {
        return new RadiologyConcepts(configured);
    }
}

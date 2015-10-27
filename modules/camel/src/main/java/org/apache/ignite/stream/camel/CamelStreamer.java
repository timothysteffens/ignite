/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.stream.camel;

import java.util.Map;

import org.apache.camel.CamelContext;
import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.NoSuchEndpointException;
import org.apache.camel.Processor;
import org.apache.camel.ServiceStatus;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.util.CamelContextHelper;
import org.apache.camel.util.ServiceHelper;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.internal.util.typedef.internal.A;
import org.apache.ignite.stream.StreamAdapter;
import org.apache.ignite.stream.StreamMultipleTupleExtractor;
import org.apache.ignite.stream.StreamSingleTupleExtractor;

/**
 * This streamer consumes messages from an Apache Camel consumer endpoint and feeds them into an Ignite data streamer.
 *
 * The only mandatory property is {@link #endpointUri} and the appropriate stream tuple extractor
 * (either {@link StreamSingleTupleExtractor} or {@link StreamMultipleTupleExtractor)}.
 *
 * The user can also provide a custom {@link CamelContext} in case they want to attach custom components, a
 * {@link org.apache.camel.component.properties.PropertiesComponent}, set tracers, management strategies, etc.
 *
 * @see <a href="http://camel.apache.org">Apache Camel</a>
 * @see <a href="http://camel.apache.org/components.html">Apache Camel components</a>
 */
public class CamelStreamer<K, V> extends StreamAdapter<Exchange, K, V> implements Processor {

    /** Logger. */
    private IgniteLogger log;

    /** The Camel Context. */
    private CamelContext camelContext;

    /** The endpoint URI to consume from. */
    private String endpointUri;

    /** Camel endpoint. */
    private Endpoint endpoint;

    /** Camel consumer. */
    private Consumer consumer;

    /** A {@link Processor} to generate the response. */
    private Processor responseProcessor;

    /**
     * Starts the streamer.
     *
     * @throws IgniteException
     */
    public void start() throws IgniteException {
        // Ensure that the endpoint URI is provided.
        A.notNullOrEmpty(endpointUri, "endpoint URI must be provided");

        // Check that one and only one tuple extractor is provided.
        A.ensure(!(getSingleTupleExtractor() == null && getMultipleTupleExtractor() == null),
            "tuple extractor missing");
        A.ensure(getSingleTupleExtractor() == null || getMultipleTupleExtractor() == null,
            "cannot provide both single and multiple tuple extractor");

        // If a custom CamelContext is not provided, initialize one.
        if (camelContext == null)
            camelContext = new DefaultCamelContext();

        // If the Camel Context is starting or started, reject this call to start.
        if (camelContext.getStatus() == ServiceStatus.Started || camelContext.getStatus() == ServiceStatus.Starting)
            throw new IgniteException("Failed to start Camel streamer (CamelContext already started or starting).");

        log = getIgnite().log();

        // Instantiate the Camel endpoint.
        try {
            endpoint = CamelContextHelper.getMandatoryEndpoint(camelContext, endpointUri);
        }
        catch (NoSuchEndpointException e) {
            throw new IgniteException("Failed to start Camel streamer (exception while instantiating endpoint).", e);
        }

        // Create the Camel consumer.
        try {
            consumer = endpoint.createConsumer(this);
        }
        catch (Exception e) {
            throw new IgniteException("Failed to start Camel streamer (exception while creating consumer).", e);
        }

        // Start the Camel services.
        try {
            ServiceHelper.startServices(camelContext, endpoint, consumer);
        }
        catch (Exception e) {
            throw new IgniteException("Failed to start Camel streamer (exception while starting services).", e);
        }

        log.info("Started Camel streamer consuming from endpoint URI: " + endpointUri);
    }

    /**
     * Stops the streamer.
     *
     * @throws IgniteException
     */
    public void stop() throws IgniteException {
        // If the Camel Context is stopping or stopped, reject this call to stop.
        if (camelContext.getStatus() == ServiceStatus.Stopped || camelContext.getStatus() == ServiceStatus.Stopping)
            throw new IgniteException("Failed to stop Camel streamer (CamelContext already stopped or stopping).");

        // Stop Camel services.
        try {
            ServiceHelper.stopAndShutdownServices(camelContext, endpoint, consumer);
        } catch (Exception e) {
            throw new IgniteException("Failed to stop Camel streamer (exception while stopping services).", e);
        }

        log.info("Stopped Camel streamer, formerly consuming from endpoint URI: " + endpointUri);
    }


    /**
     * Processes the incoming {@link Exchange} and adds the tuple(s) to the underlying streamer.
     *
     * @param exchange The Camel Exchange.
     */
    @Override
    public void process(Exchange exchange) throws Exception {
        // Extract and insert the tuple(s).
        if (getMultipleTupleExtractor() == null) {
            Map.Entry<K, V> entry = getSingleTupleExtractor().extract(exchange);
            getStreamer().addData(entry);
        }
        else {
            Map<K, V> entries = getMultipleTupleExtractor().extract(exchange);
            getStreamer().addData(entries);
        }

        // If the user has set a response processor, invoke it before finishing.
        if (responseProcessor != null)
            responseProcessor.process(exchange);

    }

    // ----------------------------
    //  Getters and setters
    // ----------------------------

    /**
     * Gets the underlying {@link CamelContext}, whether created automatically by Ignite or the context specified
     * by the user.
     *
     * @return The Camel Context.
     */
    public CamelContext getCamelContext() {
        return camelContext;
    }

    /**
     * Explicitly sets the {@link CamelContext} to use.
     *
     * Doing so gives the user the opportunity to attach custom components, a
     * {@link org.apache.camel.component.properties.PropertiesComponent}, set tracers, management strategies, etc.
     *
     * @param camelContext The Camel Context to use. In most cases, an instance of {@link DefaultCamelContext}.
     */
    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    /**
     * Gets the endpoint URI from which to consume.
     *
     * @return The endpoint URI.
     */
    public String getEndpointUri() {
        return endpointUri;
    }

    /**
     * Sets the endpoint URI from which to consume. <b>Mandatory.</b>
     *
     * @param endpointUri The endpoint URI.
     */
    public void setEndpointUri(String endpointUri) {
        this.endpointUri = endpointUri;
    }

    /**
     * Gets the {@link Processor} used to generate the response.
     *
     * @return The {@link Processor}.
     */
    public Processor getResponseProcessor() {
        return responseProcessor;
    }

    /**
     * Sets the {@link Processor} used to generate the response.
     *
     * @param responseProcessor The {@link Processor}.
     */
    public void setResponseProcessor(Processor responseProcessor) {
        this.responseProcessor = responseProcessor;
    }
}

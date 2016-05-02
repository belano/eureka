/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.shared.transport.jersey;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientRequestFilter;

import org.glassfish.jersey.message.GZipEncoder;

import com.netflix.appinfo.AbstractEurekaIdentity;
import com.netflix.appinfo.EurekaAccept;
import com.netflix.appinfo.EurekaClientIdentity;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.EurekaIdentityHeaderFilter;
import com.netflix.discovery.shared.resolver.EurekaEndpoint;
import com.netflix.discovery.shared.transport.EurekaClientFactoryBuilder;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import com.netflix.discovery.shared.transport.jersey.EurekaJerseyClientImpl.EurekaJerseyClientBuilder;

/**
 * @author Tomasz Bak
 */
public class JerseyEurekaHttpClientFactory implements TransportClientFactory {

    public static final String HTTP_X_DISCOVERY_ALLOW_REDIRECT = "X-Discovery-AllowRedirect";

    private final EurekaJerseyClient jerseyClient;;
    private final ApacheHttpClientConnectionCleaner cleaner;
    private final Map<String, String> additionalHeaders;

    /**
     * @deprecated {@link EurekaJerseyClient} is deprecated and will be removed
     */
    @Deprecated
    public JerseyEurekaHttpClientFactory(EurekaJerseyClient jerseyClient, boolean allowRedirects) {
        this(
                jerseyClient,
                -1,
                Collections.singletonMap(HTTP_X_DISCOVERY_ALLOW_REDIRECT, allowRedirects ? "true" : "false")
        );
    }

    @Deprecated
    public JerseyEurekaHttpClientFactory(EurekaJerseyClient jerseyClient, Map<String, String> additionalHeaders) {
        this(jerseyClient, -1, additionalHeaders);
    }

    private JerseyEurekaHttpClientFactory(EurekaJerseyClient jerseyClient,
            long connectionIdleTimeout,
            Map<String, String> additionalHeaders) {
        this.jerseyClient = jerseyClient;
        this.additionalHeaders = additionalHeaders;
        this.cleaner = new ApacheHttpClientConnectionCleaner(jerseyClient.getClient(), connectionIdleTimeout);
    }

    @Override
    public EurekaHttpClient newClient(EurekaEndpoint endpoint) {
        return new JerseyApplicationClient(jerseyClient.getClient(), endpoint.getServiceUrl(), additionalHeaders);
    }

    @Override
    public void shutdown() {
        if (jerseyClient != null) {
            jerseyClient.destroyResources();
        }
        cleaner.shutdown();
    }

    public static JerseyEurekaHttpClientFactory create(EurekaClientConfig clientConfig,
                                                       Collection<ClientRequestFilter> additionalFilters,
                                                       InstanceInfo myInstanceInfo,
                                                       AbstractEurekaIdentity clientIdentity) {
        JerseyEurekaHttpClientFactoryBuilder clientBuilder = newBuilder()
                .withAdditionalFilters(additionalFilters)
                .withMyInstanceInfo(myInstanceInfo)
                .withUserAgent("Java-EurekaClient")
                .withClientAccept(EurekaAccept.fromString(clientConfig.getClientDataAccept()))
                .withAllowRedirect(clientConfig.allowRedirects())
                .withConnectionTimeout(clientConfig.getEurekaServerConnectTimeoutSeconds() * 1000)
                .withReadTimeout(clientConfig.getEurekaServerReadTimeoutSeconds() * 1000)
                .withMaxConnectionsPerHost(clientConfig.getEurekaServerTotalConnectionsPerHost())
                .withMaxTotalConnections(clientConfig.getEurekaServerTotalConnections())
                .withConnectionIdleTimeout(clientConfig.getEurekaConnectionIdleTimeoutSeconds())
                .withEncoder(clientConfig.getEncoderName())
                .withDecoder(clientConfig.getDecoderName(), clientConfig.getClientDataAccept())
                .withClientIdentity(clientIdentity);

        if ("true".equals(System.getProperty("com.netflix.eureka.shouldSSLConnectionsUseSystemSocketFactory"))) {
            clientBuilder.withClientName("DiscoveryClient-HTTPClient-System").withSystemSSLConfiguration();
        } else if (clientConfig.getProxyHost() != null && clientConfig.getProxyPort() != null) {
            clientBuilder.withClientName("Proxy-DiscoveryClient-HTTPClient")
                    .withProxy(
                            clientConfig.getProxyHost(), Integer.parseInt(clientConfig.getProxyPort()),
                            clientConfig.getProxyUserName(), clientConfig.getProxyPassword()
                    );
        } else {
            clientBuilder.withClientName("DiscoveryClient-HTTPClient");
        }

        return clientBuilder.build();
    }

    public static JerseyEurekaHttpClientFactoryBuilder newBuilder() {
        return new JerseyEurekaHttpClientFactoryBuilder().withExperimental(false);
    }

    public static JerseyEurekaHttpClientFactoryBuilder experimentalBuilder() {
        return new JerseyEurekaHttpClientFactoryBuilder().withExperimental(true);
    }

    /**
     * Currently use EurekaJerseyClientBuilder. Once old transport in DiscoveryClient is removed, incorporate
     * EurekaJerseyClientBuilder here, and remove it.
     */
    public static class JerseyEurekaHttpClientFactoryBuilder extends EurekaClientFactoryBuilder<JerseyEurekaHttpClientFactory, JerseyEurekaHttpClientFactoryBuilder> {

        private Collection<ClientRequestFilter> additionalFilters = Collections.emptyList();
        private boolean experimental = false;

        public JerseyEurekaHttpClientFactoryBuilder withAdditionalFilters(Collection<ClientRequestFilter> additionalFilters) {
            this.additionalFilters = additionalFilters;
            return this;
        }

        public JerseyEurekaHttpClientFactoryBuilder withExperimental(boolean experimental) {
            this.experimental = experimental;
            return this;
        }

        @Override
        public JerseyEurekaHttpClientFactory build() {
            Map<String, String> additionalHeaders = new HashMap<>();
            if (allowRedirect) {
                additionalHeaders.put(HTTP_X_DISCOVERY_ALLOW_REDIRECT, "true");
            }
            if (EurekaAccept.compact == eurekaAccept) {
                additionalHeaders.put(EurekaAccept.HTTP_X_EUREKA_ACCEPT, eurekaAccept.name());
            }

            return buildLegacy(additionalHeaders);
        }

        private JerseyEurekaHttpClientFactory buildLegacy(Map<String, String> additionalHeaders) {
            EurekaJerseyClientBuilder clientBuilder = new EurekaJerseyClientBuilder()
                    .withClientName(clientName)
                    .withUserAgent("Java-EurekaClient")
                    .withConnectionTimeout(connectionTimeout)
                    .withReadTimeout(readTimeout)
                    .withMaxConnectionsPerHost(maxConnectionsPerHost)
                    .withMaxTotalConnections(maxTotalConnections)
                    .withConnectionIdleTimeout((int) connectionIdleTimeout)
                    .withEncoderWrapper(encoderWrapper)
                    .withDecoderWrapper(decoderWrapper);

            EurekaJerseyClient jerseyClient = clientBuilder.build();
            Client client = jerseyClient.getClient();
            addFilters(client);

            return new JerseyEurekaHttpClientFactory(jerseyClient, additionalHeaders);
        }

        private void addFilters(Client client) {
            // Add gzip content encoding support
            client.register(GZipEncoder.class);

            // always enable client identity headers
            String ip = myInstanceInfo == null ? null : myInstanceInfo.getIPAddr();
            AbstractEurekaIdentity identity = clientIdentity == null ? new EurekaClientIdentity(ip) : clientIdentity;
            client.register(new EurekaIdentityHeaderFilter(identity));

            if (additionalFilters != null) {
                for (ClientRequestFilter filter : additionalFilters) {
                    if (filter != null) {
                        client.register(filter);
                    }
                }
            }
        }
    }
}
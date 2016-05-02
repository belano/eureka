package com.netflix.discovery.shared.transport.jersey;

import com.netflix.appinfo.EurekaClientIdentity;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.resolver.EurekaEndpoint;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import com.netflix.discovery.shared.transport.decorator.MetricsCollectingEurekaHttpClient;
import javax.ws.rs.client.*;

import java.util.Collection;

public final class TransportClientFactories {

    @Deprecated
    public static TransportClientFactory newTransportClientFactory(final Collection<ClientRequestFilter> additionalFilters,
                                                                   final EurekaJerseyClient providedJerseyClient) {
        Client client = providedJerseyClient.getClient();
        if (additionalFilters != null) {
            for (ClientRequestFilter filter : additionalFilters) {
                if (filter != null) {
                    client.register(filter);
                }
            }
        }

        final TransportClientFactory jerseyFactory = new JerseyEurekaHttpClientFactory(providedJerseyClient, false);
        final TransportClientFactory metricsFactory = MetricsCollectingEurekaHttpClient.createFactory(jerseyFactory);

        return new TransportClientFactory() {
            @Override
            public EurekaHttpClient newClient(EurekaEndpoint serviceUrl) {
                return metricsFactory.newClient(serviceUrl);
            }

            @Override
            public void shutdown() {
                metricsFactory.shutdown();
                jerseyFactory.shutdown();
            }
        };
    }

    public static TransportClientFactory newTransportClientFactory(final EurekaClientConfig clientConfig,
                                                                   final Collection<ClientRequestFilter> additionalFilters,
                                                                   final InstanceInfo myInstanceInfo) {
        final TransportClientFactory jerseyFactory = JerseyEurekaHttpClientFactory.create(
                clientConfig,
                additionalFilters,
                myInstanceInfo,
                new EurekaClientIdentity(myInstanceInfo.getIPAddr())
        );
        final TransportClientFactory metricsFactory = MetricsCollectingEurekaHttpClient.createFactory(jerseyFactory);

        return new TransportClientFactory() {
            @Override
            public EurekaHttpClient newClient(EurekaEndpoint serviceUrl) {
                return metricsFactory.newClient(serviceUrl);
            }

            @Override
            public void shutdown() {
                metricsFactory.shutdown();
                jerseyFactory.shutdown();
            }
        };
    }

}
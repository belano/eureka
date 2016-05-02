package com.netflix.discovery.shared.transport.jersey;

import static com.netflix.discovery.util.DiscoveryBuildInfo.buildVersion;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;

import org.apache.http.client.params.ClientPNames;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.params.CoreProtocolPNames;
import org.glassfish.jersey.apache.connector.ApacheClientProperties;
import org.glassfish.jersey.apache.connector.ApacheConnectorProvider;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;

import com.netflix.discovery.converters.wrappers.CodecWrappers;
import com.netflix.discovery.converters.wrappers.DecoderWrapper;
import com.netflix.discovery.converters.wrappers.EncoderWrapper;
import com.netflix.discovery.provider.DiscoveryJerseyProvider;

/**
 * @author Tomasz Bak
 */
public class EurekaJerseyClientImpl implements EurekaJerseyClient {

    private static final String PROTOCOL = "https";
    private static final String PROTOCOL_SCHEME = "SSL";
    private static final String KEYSTORE_TYPE = "JKS";

    private final ApacheHttpClientConnectionCleaner apacheHttpClientConnectionCleaner;

    private final Client jersey2Client;

    ClientConfig jersey2ClientConfig;

    public EurekaJerseyClientImpl(int connectionTimeout, int readTimeout, final int connectionIdleTimeout,
            ClientConfig clientConfig) {
        try {
            jersey2ClientConfig = clientConfig;
            jersey2ClientConfig.connectorProvider(new ApacheConnectorProvider());
            jersey2ClientConfig.property(ClientProperties.CONNECT_TIMEOUT, connectionTimeout);
            jersey2ClientConfig.property(ClientProperties.READ_TIMEOUT, readTimeout);

            // Turn the whole damned auto-confusion system off in order to avoid
            // JacksonJaxbJsonProvider auto-registration
            // so as the custom DiscoveryJerseyProvider will eventually kick in
            jersey2ClientConfig.property(ClientProperties.METAINF_SERVICES_LOOKUP_DISABLE, true);

            // If HTTP Basic Auth credentials are provided (in the url) send them with the initial
            // request, rather
            // than waiting for a challenge request. ie. the request is sent with the Authorization
            // header
            jersey2ClientConfig.property(ApacheClientProperties.PREEMPTIVE_BASIC_AUTHENTICATION, true);

            jersey2Client = ClientBuilder.newClient(clientConfig);

            this.apacheHttpClientConnectionCleaner = new ApacheHttpClientConnectionCleaner(jersey2Client,
                    connectionIdleTimeout);
        } catch (Throwable e) {
            throw new RuntimeException("Cannot create Jersey 2 client", e);
        }
    }

    @Override
    public Client getClient() {
        return jersey2Client;
    }

    /**
     * Clean up resources.
     */
    @Override
    public void destroyResources() {
        apacheHttpClientConnectionCleaner.shutdown();
        jersey2Client.close();
    }

    public static class EurekaJerseyClientBuilder {

        private boolean systemSSL;
        private String clientName;
        private int maxConnectionsPerHost;
        private int maxTotalConnections;
        private String trustStoreFileName;
        private String trustStorePassword;
        private String userAgent;
        private String proxyUserName;
        private String proxyPassword;
        private String proxyHost;
        private String proxyPort;
        private int connectionTimeout;
        private int readTimeout;
        private int connectionIdleTimeout;
        private EncoderWrapper encoderWrapper;
        private DecoderWrapper decoderWrapper;

        public EurekaJerseyClientBuilder withClientName(String clientName) {
            this.clientName = clientName;
            return this;
        }

        public EurekaJerseyClientBuilder withUserAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public EurekaJerseyClientBuilder withConnectionTimeout(int connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
            return this;
        }

        public EurekaJerseyClientBuilder withReadTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public EurekaJerseyClientBuilder withConnectionIdleTimeout(int connectionIdleTimeout) {
            this.connectionIdleTimeout = connectionIdleTimeout;
            return this;
        }

        public EurekaJerseyClientBuilder withMaxConnectionsPerHost(int maxConnectionsPerHost) {
            this.maxConnectionsPerHost = maxConnectionsPerHost;
            return this;
        }

        public EurekaJerseyClientBuilder withMaxTotalConnections(int maxTotalConnections) {
            this.maxTotalConnections = maxTotalConnections;
            return this;
        }

        public EurekaJerseyClientBuilder withProxy(String proxyHost, String proxyPort, String user, String password) {
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
            this.proxyUserName = user;
            this.proxyPassword = password;
            return this;
        }

        public EurekaJerseyClientBuilder withSystemSSLConfiguration() {
            this.systemSSL = true;
            return this;
        }

        public EurekaJerseyClientBuilder withTrustStoreFile(String trustStoreFileName, String trustStorePassword) {
            this.trustStoreFileName = trustStoreFileName;
            this.trustStorePassword = trustStorePassword;
            return this;
        }

        public EurekaJerseyClientBuilder withEncoder(String encoderName) {
            return this.withEncoderWrapper(CodecWrappers.getEncoder(encoderName));
        }

        public EurekaJerseyClientBuilder withEncoderWrapper(EncoderWrapper encoderWrapper) {
            this.encoderWrapper = encoderWrapper;
            return this;
        }

        public EurekaJerseyClientBuilder withDecoder(String decoderName, String clientDataAccept) {
            return this.withDecoderWrapper(CodecWrappers.resolveDecoder(decoderName, clientDataAccept));
        }

        public EurekaJerseyClientBuilder withDecoderWrapper(DecoderWrapper decoderWrapper) {
            this.decoderWrapper = decoderWrapper;
            return this;
        }

        public EurekaJerseyClient build() {
            MyDefaultClientConfig config = new MyDefaultClientConfig();
            try {
                return new EurekaJerseyClientImpl(connectionTimeout, readTimeout, connectionIdleTimeout, config);
            } catch (Throwable e) {
                throw new RuntimeException("Cannot create Jersey client ", e);
            }
        }

        class MyDefaultClientConfig extends ClientConfig {

            MyDefaultClientConfig() {
                PoolingHttpClientConnectionManager cm;

                if (systemSSL) {
                    cm = createSystemSslCM();
                } else if (trustStoreFileName != null) {
                    cm = createCustomSslCM();
                } else {
                    cm = new PoolingHttpClientConnectionManager();
                }

                if (proxyHost != null) {
                    addProxyConfiguration();
                }

                addProviders();

                // Common properties to all clients
                cm.setDefaultMaxPerRoute(maxConnectionsPerHost);
                cm.setMaxTotal(maxTotalConnections);
                property(ApacheClientProperties.CONNECTION_MANAGER, cm);

                String fullUserAgentName = (userAgent == null ? clientName : userAgent) + "/v" + buildVersion();
                property(CoreProtocolPNames.USER_AGENT, fullUserAgentName);

                // To pin a client to specific server in case redirect happens, we handle redirects
                // directly
                // (see DiscoveryClient.makeRemoteCall methods).
                property(ClientProperties.FOLLOW_REDIRECTS, Boolean.FALSE);
                property(ClientPNames.HANDLE_REDIRECTS, Boolean.FALSE);
            }

            private void addProviders() {
                DiscoveryJerseyProvider discoveryJerseyProvider = new DiscoveryJerseyProvider(encoderWrapper,
                        decoderWrapper);
                register(discoveryJerseyProvider);
            }

            private void addProxyConfiguration() {
                if (proxyUserName != null && proxyPassword != null) {
                    property(ClientProperties.PROXY_USERNAME, proxyUserName);
                    property(ClientProperties.PROXY_PASSWORD, proxyPassword);
                } else {
                    // Due to bug in apache client, user name/password must always be set.
                    // Otherwise proxy configuration is ignored.
                    property(ClientProperties.PROXY_USERNAME, "guest");
                    property(ClientProperties.PROXY_PASSWORD, "guest");
                }
                property(ClientProperties.PROXY_URI, "http://" + proxyHost + ":" + proxyPort);
            }

            private PoolingHttpClientConnectionManager createSystemSslCM() {
                ConnectionSocketFactory socketFactory = SSLConnectionSocketFactory.getSystemSocketFactory();

                Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
                    .register(PROTOCOL, socketFactory)
                    .build();

                return new PoolingHttpClientConnectionManager(registry);
            }

            private PoolingHttpClientConnectionManager createCustomSslCM() {
                FileInputStream fin = null;
                try {
                    SSLContext sslContext = SSLContext.getInstance(PROTOCOL_SCHEME);
                    KeyStore sslKeyStore = KeyStore.getInstance(KEYSTORE_TYPE);

                    fin = new FileInputStream(trustStoreFileName);
                    sslKeyStore.load(fin, trustStorePassword.toCharArray());

                    TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    factory.init(sslKeyStore);

                    TrustManager[] trustManagers = factory.getTrustManagers();

                    sslContext.init(null, trustManagers, null);

                    ConnectionSocketFactory socketFactory =
                            new SSLConnectionSocketFactory(sslContext,
                                    SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

                    Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
                        .register(PROTOCOL, socketFactory)
                        .build();

                    return new PoolingHttpClientConnectionManager(registry);
                } catch (Exception ex) {
                    throw new IllegalStateException("SSL configuration issue", ex);
                } finally {
                    if (fin != null) {
                        try {
                            fin.close();
                        } catch (IOException ignore) {
                        }
                    }
                }
            }

        }
    }
}

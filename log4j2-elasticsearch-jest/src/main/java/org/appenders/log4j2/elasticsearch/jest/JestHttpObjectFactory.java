package org.appenders.log4j2.elasticsearch.jest;

/*-
 * #%L
 * log4j2-elasticsearch
 * %%
 * Copyright (C) 2018 Rafal Foltynski
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */


import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.JestResult;
import io.searchbox.client.JestResultHandler;
import io.searchbox.client.config.HttpClientConfig;
import io.searchbox.core.Bulk;
import io.searchbox.core.JestBatchIntrospector;
import io.searchbox.indices.template.PutTemplate;
import io.searchbox.indices.template.TemplateAction;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.ConfigurationException;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;
import org.apache.logging.log4j.status.StatusLogger;
import org.appenders.log4j2.elasticsearch.Operation;
import org.appenders.log4j2.elasticsearch.Auth;
import org.appenders.log4j2.elasticsearch.BatchOperations;
import org.appenders.log4j2.elasticsearch.ClientObjectFactory;
import org.appenders.log4j2.elasticsearch.ClientProvider;
import org.appenders.log4j2.elasticsearch.FailoverPolicy;
import org.appenders.log4j2.elasticsearch.IndexTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

@Plugin(name = "JestHttp", category = Node.CATEGORY, elementType = ClientObjectFactory.ELEMENT_TYPE, printObject = true)
public class JestHttpObjectFactory implements ClientObjectFactory<JestClient, Bulk> {

    private static Logger LOG = StatusLogger.getLogger();

    private final Collection<String> serverUris;
    private final int connTimeout;
    private final int readTimeout;
    private final int maxTotalConnections;
    private final int defaultMaxTotalConnectionsPerRoute;
    private final boolean discoveryEnabled;
    private final Auth<HttpClientConfig.Builder> auth;

    private final ConcurrentLinkedQueue<Operation> operations = new ConcurrentLinkedQueue<>();

    private JestClient client;

    protected JestHttpObjectFactory(Collection<String> serverUris, int connTimeout, int readTimeout, int maxTotalConnections, int defaultMaxTotalConnectionPerRoute, boolean discoveryEnabled, Auth<HttpClientConfig.Builder> auth) {
        this.serverUris = serverUris;
        this.connTimeout = connTimeout;
        this.readTimeout = readTimeout;
        this.maxTotalConnections = maxTotalConnections;
        this.defaultMaxTotalConnectionsPerRoute = defaultMaxTotalConnectionPerRoute;
        this.discoveryEnabled = discoveryEnabled;
        this.auth = auth;
    }

    @Override
    public Collection<String> getServerList() {
        return new ArrayList<>(serverUris);
    }

    @Override
    public JestClient createClient() {
        if (client == null) {

            HttpClientConfig.Builder builder = new HttpClientConfig.Builder(serverUris)
                    .maxTotalConnection(maxTotalConnections)
                    .defaultMaxTotalConnectionPerRoute(defaultMaxTotalConnectionsPerRoute)
                    .connTimeout(connTimeout)
                    .readTimeout(readTimeout)
                    .discoveryEnabled(discoveryEnabled)
                    .multiThreaded(true);

            if (this.auth != null) {
                auth.configure(builder);
            }

            client = getClientProvider(builder).createClient();
        }
        return client;
    }

    @Override
    public Function<Bulk, Boolean> createBatchListener(FailoverPolicy failoverPolicy) {
        return new Function<Bulk, Boolean>() {

            private Function<Bulk, Boolean> failureHandler = createFailureHandler(failoverPolicy);

            @Override
            public Boolean apply(Bulk bulk) {

                while (!operations.isEmpty()) {
                    try {
                        operations.remove().execute();
                    } catch (Exception e) {
                        // TODO: redirect to failover (?) retry with exp. backoff (?) multiple options here
                        LOG.error("Deferred operation failed: {}", e.getMessage());
                    }
                }

                JestResultHandler<JestResult> jestResultHandler = createResultHandler(bulk, failureHandler);
                createClient().executeAsync(bulk, jestResultHandler);
                return true;
            }

        };
    }

    @Override
    public Function<Bulk, Boolean> createFailureHandler(FailoverPolicy failover) {
        return new Function<Bulk, Boolean>() {

            private final JestBatchIntrospector introspector = new JestBatchIntrospector();

            @Override
            public Boolean apply(Bulk bulk) {
                List<Object> items = introspector.items(bulk);
                LOG.warn(String.format("Batch of %s items failed. Redirecting to %s", items.size(), failover.getClass().getName()));
                items.forEach(failedItem -> failover.deliver(failedItem));
                return true;
            }

        };
    }

    @Override
    public BatchOperations<Bulk> createBatchOperations() {
        return new JestBulkOperations();
    }

    @Override
    public void execute(IndexTemplate indexTemplate) {
        TemplateAction templateAction = new PutTemplate.Builder(indexTemplate.getName(), indexTemplate.getSource()).build();
        try {
            JestResult result = createClient().execute(templateAction);
            if (!result.isSucceeded()) {
                throw new ConfigurationException("IndexTemplate not added: " + result.getErrorMessage());
            }
        } catch (IOException e) {
            throw new ConfigurationException("IndexTemplate not added: " + e.getMessage());
        }
    }

    @Override
    public void addOperation(Operation operation) {
        operations.add(operation);
    }

    protected JestResultHandler<JestResult> createResultHandler(Bulk bulk, Function<Bulk, Boolean> failureHandler) {
        return new JestResultHandler<JestResult>() {
            @Override
            public void completed(JestResult result) {
                if (!result.isSucceeded()) {
                    LOG.warn(result.getErrorMessage());
                    failureHandler.apply(bulk);
                }
            }
            @Override
            public void failed(Exception ex) {
                LOG.warn(ex.getMessage(), ex);
                failureHandler.apply(bulk);
            }
        };
    }

    @PluginBuilderFactory
    public static Builder newBuilder() {
        return new Builder();
    }

    // visible for testing
    ClientProvider<JestClient> getClientProvider(HttpClientConfig.Builder clientConfigBuilder) {
        return new JestClientProvider(clientConfigBuilder);
    }

    public static class Builder implements org.apache.logging.log4j.core.util.Builder<JestHttpObjectFactory> {

        @PluginBuilderAttribute
        @Required(message = "No serverUris provided for JestClientConfig")
        protected String serverUris;

        @PluginBuilderAttribute
        protected int connTimeout = -1;

        @PluginBuilderAttribute
        protected int readTimeout = -1;

        @PluginBuilderAttribute
        protected int maxTotalConnection = 40;

        @PluginBuilderAttribute
        protected int defaultMaxTotalConnectionPerRoute = 4;

        @PluginBuilderAttribute
        protected boolean discoveryEnabled;

        @PluginElement("auth")
        protected Auth auth;

        @Override
        public JestHttpObjectFactory build() {

            validate();

            return new JestHttpObjectFactory(Arrays.asList(serverUris.split(";")), connTimeout, readTimeout, maxTotalConnection, defaultMaxTotalConnectionPerRoute, discoveryEnabled, auth);
        }

        protected void validate() {
            if (serverUris == null) {
                throw new ConfigurationException("No serverUris provided for JestClientConfig");
            }
        }

        public Builder withServerUris(String serverUris) {
            this.serverUris = serverUris;
            return this;
        }

        public Builder withMaxTotalConnection(int maxTotalConnection) {
            this.maxTotalConnection = maxTotalConnection;
            return this;
        }

        public Builder withDefaultMaxTotalConnectionPerRoute(int defaultMaxTotalConnectionPerRoute) {
            this.defaultMaxTotalConnectionPerRoute = defaultMaxTotalConnectionPerRoute;
            return this;
        }

        public Builder withConnTimeout(int connTimeout) {
            this.connTimeout = connTimeout;
            return this;
        }

        public Builder withReadTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public Builder withDiscoveryEnabled(boolean discoveryEnabled) {
            this.discoveryEnabled = discoveryEnabled;
            return this;
        }

        public Builder withAuth(Auth auth) {
            this.auth = auth;
            return this;
        }
    }

    class JestClientProvider implements ClientProvider<JestClient> {

        private final HttpClientConfig.Builder clientConfigBuilder;

        public JestClientProvider(HttpClientConfig.Builder clientConfigBuilder) {
            this.clientConfigBuilder = clientConfigBuilder;
        }

        @Override
        public JestClient createClient() {
            JestClientFactory jestClientFactory = new JestClientFactory();
            jestClientFactory.setHttpClientConfig(clientConfigBuilder.build());
            return jestClientFactory.getObject();
        }

    }

}

package com.samwan.kibetipay.search.internal;

import java.net.URISyntaxException;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * The low-level pieces: one HTTP transport (connection pool, JSON mapper) and the typed client on top.
 * The transport is {@link java.io.Closeable}, so Spring closes it on shutdown.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("app.search.enabled")
class OpenSearchConfig {

    @Bean
    OpenSearchTransport openSearchTransport(SearchProperties props) throws URISyntaxException {
        HttpHost host = HttpHost.create(props.url());
        return ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(new JacksonJsonpMapper())
                .setHttpClientConfigCallback(http -> {
                    if (StringUtils.hasText(props.username())) {
                        var credentials = new BasicCredentialsProvider();
                        credentials.setCredentials(new AuthScope(host),
                                new UsernamePasswordCredentials(props.username(), props.password().toCharArray()));
                        http.setDefaultCredentialsProvider(credentials);
                    }
                    return http;
                })
                .build();
    }

    @Bean
    OpenSearchClient openSearchClient(OpenSearchTransport transport) {
        return new OpenSearchClient(transport);
    }
}

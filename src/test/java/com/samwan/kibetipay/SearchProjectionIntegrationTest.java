package com.samwan.kibetipay;

import static org.assertj.core.api.Assertions.assertThat;

import com.samwan.kibetipay.transfer.TransferEvents.TransferCompleted;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * End to end through Spring: a TransferCompleted event is published in a committed transaction (exactly as the
 * transfer module does), goes through the Modulith outbox to the async search listener, and the transfer
 * becomes findable over the HTTP search API by its note text.
 */
@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "app.insights.enabled=false",
        "app.assistant.ingest-on-startup=false",
        "spring.ai.ollama.init.pull-model-strategy=never",
        "spring.docker.compose.enabled=false",
        "app.search.enabled=true",
        "app.security.users[0].username=admin",
        "app.security.users[0].password={noop}admin-test",
        "app.security.users[0].roles[0]=ADMIN"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, SearchProjectionIntegrationTest.OpenSearchTestConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class SearchProjectionIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class OpenSearchTestConfig {

        @Bean
        OpenSearchContainer<?> openSearch() {
            return new OpenSearchContainer<>("opensearchproject/opensearch:3.9.0");
        }

        @Bean
        DynamicPropertyRegistrar openSearchProperties(OpenSearchContainer<?> openSearch) {
            return registry -> registry.add("app.search.url", openSearch::getHttpHostAddress);
        }
    }

    /** The RAG assistant's vector store needs an embedding model; it plays no part in this test. */
    @MockitoBean EmbeddingModel embeddingModel;

    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;

    @Test
    void completedTransferBecomesSearchableByNoteText() throws Exception {
        String alice = "alice-" + UUID.randomUUID();
        String marker = "zanzibar" + UUID.randomUUID().toString().substring(0, 6);

        tx.executeWithoutResult(status -> events.publishEvent(new TransferCompleted(UUID.randomUUID(), alice,
                "bob", 700_00, "KES", "trip to " + marker, 15, Instant.now())));

        // After commit: outbox row -> async listener -> scripted upsert -> refresh (~1s). Poll the real endpoint.
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        String body = "";
        while (Instant.now().isBefore(deadline)) {
            body = mvc.perform(get("/api/admin/search/transfers").param("q", marker)
                            .with(httpBasic("admin", "admin-test")))
                    .andReturn().getResponse().getContentAsString();
            if (body.contains("\"total\":1")) {
                break;
            }
            Thread.sleep(250);
        }
        assertThat(body).contains("\"total\":1").contains("\"status\":\"COMPLETED\"").contains(alice);
    }
}

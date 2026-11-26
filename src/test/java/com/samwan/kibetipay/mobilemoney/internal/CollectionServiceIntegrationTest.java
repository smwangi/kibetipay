package com.samwan.kibetipay.mobilemoney.internal;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.samwan.kibetipay.TestcontainersConfig;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.shared.paging.Page;
import com.samwan.kibetipay.shared.time.TimeConfig;
import com.samwan.kibetipay.wallet.WalletApi;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "app.ai.enabled=false", "app.insights.enabled=false", "app.assistant.ingest-on-startup=false",
        "spring.ai.ollama.init.pull-model-strategy=never", "spring.docker.compose.enabled=false"
})
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class CollectionServiceIntegrationTest {

    /** The RAG assistant's vector store needs an embedding model; it plays no part in this test. */
    @MockitoBean EmbeddingModel embeddingModel;
    @Autowired CollectionService collections;
    @Autowired WalletApi wallets;
    @Autowired JdbcClient jdbc;

    @Test
    void collectionCreditsOnceOnlyAfterMatchingSuccessfulResult() {
        String owner = "collection-" + UUID.randomUUID();
        wallets.open(owner, "KES");
        var amount = Money.of(10000, "KES");
        var first = collections.initiate(owner, "collection-key", "254712345678", amount);
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(wallets.get(owner, "KES").balance()).isEqualTo(Money.of(0, "KES"));
        assertThat(collections.initiate(owner, "collection-key", "254712345678", amount).replayed()).isTrue();
        assertThatThrownBy(() -> collections.initiate(owner, "collection-key", "254712345678", Money.of(20000, "KES")))
                .isInstanceOf(DomainException.Conflict.class);
        assertThatThrownBy(() -> collections.get(first.id(), "other")).isInstanceOf(DomainException.NotFound.class);
        assertThatThrownBy(() -> collections.complete(first.id(), first.providerRequestId(), 9999, "KES", true, "receipt-1"))
                .isInstanceOf(DomainException.Conflict.class);
        var settled = collections.complete(first.id(), first.providerRequestId(), 10000, "KES", true, "receipt-1");
        assertThat(settled.status()).isEqualTo("COMPLETED");
        assertThat(collections.complete(first.id(), first.providerRequestId(), 10000, "KES", true, "receipt-1").replayed()).isTrue();
        assertThatThrownBy(() -> collections.complete(first.id(), first.providerRequestId(), 10000, "KES", true, "other-receipt"))
                .isInstanceOf(DomainException.Conflict.class);
        assertThat(wallets.get(owner, "KES").balance()).isEqualTo(amount);
    }

    @Test
    void failedTopUpStaysVisibleInHistoryAndNotifiesTheOwner() throws InterruptedException {
        String owner = "history-" + UUID.randomUUID();
        wallets.open(owner, "KES");
        var ok = collections.initiate(owner, "history-ok", "254712345678", Money.of(500_00, "KES"));
        var bad = collections.initiate(owner, "history-bad", "254712345678", Money.of(200_00, "KES"));
        collections.complete(ok.id(), ok.providerRequestId(), 500_00, "KES", true, "receipt-" + ok.id());
        collections.complete(bad.id(), bad.providerRequestId(), 200_00, "KES", false, null);

        var page = collections.history(owner, new Page.Query(null, null, null, 1));
        assertThat(page.items()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo(bad.id());
            assertThat(c.status()).isEqualTo("FAILED");
            assertThat(c.msisdn()).isEqualTo("2547••••5678");
        });
        var rest = collections.history(owner, Page.Query.of(null, null, page.nextCursor(), 10,
                TimeConfig.BUSINESS_ZONE));
        assertThat(rest.items()).extracting(CollectionService.CollectionView::status).containsExactly("COMPLETED");
        assertThat(rest.nextCursor()).isNull();
        assertThat(collections.history("someone-else", new Page.Query(null, null, null, 10)).items()).isEmpty();

        // Both outcomes reach the inbox (async listener), the failure included.
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        var kinds = List.<String>of();
        while (Instant.now().isBefore(deadline) && kinds.size() < 2) {
            kinds = jdbc.sql("SELECT kind FROM notification_message WHERE recipient_id = :owner ORDER BY kind")
                    .param("owner", owner).query(String.class).list();
            Thread.sleep(100);
        }
        assertThat(kinds).containsExactly("TOPUP_COMPLETED", "TOPUP_FAILED");
    }
}

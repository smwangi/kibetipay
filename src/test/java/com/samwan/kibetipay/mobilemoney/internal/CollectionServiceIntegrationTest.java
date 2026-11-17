package com.samwan.kibetipay.mobilemoney.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.samwan.kibetipay.TestcontainersConfig;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.wallet.WalletApi;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
    @Autowired CollectionService collections;
    @Autowired WalletApi wallets;

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
}

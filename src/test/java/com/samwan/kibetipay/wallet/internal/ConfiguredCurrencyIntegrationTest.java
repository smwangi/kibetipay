package com.samwan.kibetipay.wallet.internal;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.samwan.kibetipay.TestcontainersConfig;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.shared.money.CurrencyRegistry;
import com.samwan.kibetipay.wallet.WalletApi;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "app.ledger.default-currency=USD", "app.ai.enabled=false", "app.insights.enabled=false",
        "app.assistant.ingest-on-startup=false", "spring.docker.compose.enabled=false"
})
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class ConfiguredCurrencyIntegrationTest {

    /** The RAG assistant's vector store needs an embedding model; it plays no part in this test. */
    @MockitoBean EmbeddingModel embeddingModel;
    @Autowired WalletApi wallets;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;

    @Test
    void createsUsdSystemAccountsAndRefusesCurrencySwitch() {
        String owner = "usd-test-" + UUID.randomUUID();
        wallets.open(owner, "USD");
        wallets.deposit(owner, Money.of(1200, "USD"), "test:deposit:" + owner);
        assertThat(wallets.get(owner, "USD").balance()).isEqualTo(Money.of(1200, "USD"));
        var initializer = new PlatformCurrencyInitializer(jdbc, new LedgerProperties("TZS", null), transactions,
                new CurrencyRegistry(java.util.List.of("TZS")));
        assertThatThrownBy(() -> transactions.executeWithoutResult(s -> initializer.initialize()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(wallets.get(owner, "USD").balance()).isEqualTo(Money.of(1200, "USD"));
    }
}

package com.samwan.kibetipay.mobilemoney.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samwan.kibetipay.TestcontainersConfig;
import com.samwan.kibetipay.mobilemoney.internal.SuspenseService.Status;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.id.Ids;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.wallet.WalletApi;
import com.samwan.kibetipay.wallet.WalletApi.SystemAccounts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "app.ai.enabled=false", "app.insights.enabled=false", "app.assistant.ingest-on-startup=false",
        "spring.ai.ollama.init.pull-model-strategy=never", "spring.docker.compose.enabled=false"
})
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class SuspenseServiceIntegrationTest {

    /** The RAG assistant's vector store needs an embedding model; it plays no part in this test. */
    @MockitoBean EmbeddingModel embeddingModel;
    @Autowired SuspenseService suspense;
    @Autowired WalletApi wallets;
    @Autowired JdbcClient jdbc;

    @Test
    void unmatchedCollectionIsHeldInSuspenseThenAllocatedOnce() {
        String owner = "suspense-owner-" + UUID.randomUUID();
        wallets.open(owner, "KES");
        UUID txId = providerTransaction("COLLECTION", 25_000, null);
        long suspenseBefore = balance(SystemAccounts.SUSPENSE);
        long floatBefore = balance(SystemAccounts.FLOAT);

        var opened = suspense.open(txId, "No account reference on statement line", "ops-1");
        assertThat(opened.status()).isEqualTo(Status.OPEN);
        assertThat(opened.replayed()).isFalse();
        assertThat(balance(SystemAccounts.SUSPENSE)).isEqualTo(suspenseBefore + 25_000);
        assertThat(balance(SystemAccounts.FLOAT)).isEqualTo(floatBefore - 25_000);

        var replay = suspense.open(txId, "No account reference on statement line", "ops-1");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.id()).isEqualTo(opened.id());
        assertThat(balance(SystemAccounts.SUSPENSE)).isEqualTo(suspenseBefore + 25_000);
        assertThat(suspense.list(Status.OPEN)).extracting(SuspenseService.Item::id).contains(opened.id());

        assertThatThrownBy(() -> suspense.allocate(opened.id(), SystemAccounts.FEE_INCOME, "note", "ops-2"))
                .isInstanceOf(DomainException.RuleViolation.class);
        assertThatThrownBy(() -> suspense.allocate(opened.id(), "missing-" + UUID.randomUUID(), "note", "ops-2"))
                .isInstanceOf(WalletApi.WalletNotFoundException.class);

        var allocated = suspense.allocate(opened.id(), owner, "Customer supplied the receipt", "ops-2");
        assertThat(allocated.status()).isEqualTo(Status.ALLOCATED);
        assertThat(allocated.allocatedTo()).isEqualTo(owner);
        assertThat(wallets.get(owner, "KES").balance()).isEqualTo(Money.of(25_000, "KES"));
        assertThat(balance(SystemAccounts.SUSPENSE)).isEqualTo(suspenseBefore);

        assertThat(suspense.allocate(opened.id(), owner, "Customer supplied the receipt", "ops-2").replayed()).isTrue();
        assertThat(wallets.get(owner, "KES").balance()).isEqualTo(Money.of(25_000, "KES"));
        assertThatThrownBy(() -> suspense.allocate(opened.id(), "someone-else", "note", "ops-2"))
                .isInstanceOf(DomainException.Conflict.class);
        assertThat(suspense.list(Status.OPEN)).extracting(SuspenseService.Item::id).doesNotContain(opened.id());
    }

    @Test
    void onlyUnmatchedUncreditedCollectionsCanEnterSuspense() {
        long suspenseBefore = balance(SystemAccounts.SUSPENSE);

        UUID payout = providerTransaction("PAYOUT", 1_000, null);
        assertThatThrownBy(() -> suspense.open(payout, "reason", "ops"))
                .isInstanceOf(DomainException.RuleViolation.class);

        String reference = "expected-" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO mobilemoney_expected_payment (id, provider, reference, direction, amount_minor, currency, created_by)
                VALUES (:id, 'test-provider', :reference, 'COLLECTION', 1000, 'KES', 'ops')
                """).param("id", Ids.newId()).param("reference", reference).update();
        UUID matched = providerTransaction("COLLECTION", 1_000, reference);
        assertThatThrownBy(() -> suspense.open(matched, "reason", "ops"))
                .isInstanceOf(DomainException.RuleViolation.class);

        assertThatThrownBy(() -> suspense.open(UUID.randomUUID(), "reason", "ops"))
                .isInstanceOf(DomainException.NotFound.class);
        assertThat(balance(SystemAccounts.SUSPENSE)).isEqualTo(suspenseBefore);
    }

    private UUID providerTransaction(String direction, long amountMinor, String expectedReference) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO mobilemoney_provider_transaction (id, provider, provider_reference, expected_reference,
                  direction, amount_minor, currency, occurred_at, imported_by)
                VALUES (:id, 'test-provider', :ref, :expected, :direction, :amount, 'KES', :at, 'ops')
                """).param("id", id).param("ref", "receipt-" + id).param("expected", expectedReference)
                .param("direction", direction).param("amount", amountMinor)
                .param("at", Timestamp.from(Instant.now())).update();
        return id;
    }

    private long balance(String systemOwner) {
        return jdbc.sql("SELECT balance_minor FROM wallet_account WHERE owner_id = :owner AND currency = 'KES' AND type = 'SYSTEM'")
                .param("owner", systemOwner).query(Long.class).single();
    }
}

package com.samwan.kibetipay.wallet.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.samwan.kibetipay.TestcontainersConfig;
import com.samwan.kibetipay.shared.id.Ids;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.wallet.WalletApi;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reconciliation must stay clean under normal postings and must catch corruption the
 * posting path cannot see in itself. Corruption is written inside a transaction that is rolled
 * back, so it never leaks into other tests sharing the database.
 */
@Tag("financial")
@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "app.insights.enabled=false",
        "app.assistant.ingest-on-startup=false",
        "spring.ai.ollama.init.pull-model-strategy=never",
        "spring.docker.compose.enabled=false"
})
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class LedgerReconciliationIntegrationTest {

    @MockitoBean EmbeddingModel embeddingModel;

    @Autowired LedgerReconciliation reconciliation;
    @Autowired WalletApi wallets;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired MeterRegistry metrics;

    @Test
    void cleanAfterNormalPostings() {
        String a = "recon-a-" + UUID.randomUUID();
        String b = "recon-b-" + UUID.randomUUID();
        wallets.open(a, "KES");
        wallets.open(b, "KES");
        wallets.deposit(a, Money.of(10_000_00, "KES"), "deposit:" + a + ":1");
        wallets.deposit(a, Money.of(10_000_00, "KES"), "deposit:" + a + ":1");   // replay: no second credit
        wallets.post(new WalletApi.Posting("recon:" + a, a, b, Money.of(2_500_00, "KES"), WalletApi.Posting.Kind.TRANSFER));

        var report = reconciliation.run();

        assertThat(report.samples()).isEmpty();
        assertThat(report.clean()).isTrue();
        assertThat(gauge("trial_balance")).isZero();
    }

    @Test
    void detectsHalfPostedJournalAndBalanceEditedOutsideTheLedger() {
        String owner = "recon-c-" + UUID.randomUUID();
        wallets.open(owner, "KES");
        wallets.deposit(owner, Money.of(1_000_00, "KES"), "deposit:" + owner + ":1");
        UUID walletId = jdbc.sql("SELECT id FROM wallet_account WHERE owner_id = :o AND type = 'USER'")
                .param("o", owner).query(UUID.class).single();

        LedgerReconciliation.Report report = tx.execute(status -> {
            // 1. A journal with only its credit leg: money appeared from nowhere.
            UUID journalId = Ids.newId();
            var now = Timestamp.from(Instant.now());
            jdbc.sql("INSERT INTO wallet_journal (id, reference, kind, created_at) VALUES (:id, :ref, 'TEST', :now)")
                    .param("id", journalId).param("ref", "recon-test:" + journalId).param("now", now).update();
            jdbc.sql("""
                    INSERT INTO wallet_ledger_entry (id, journal_id, wallet_id, direction, amount_minor, currency,
                        balance_after, created_at)
                    VALUES (:id, :journal, :wallet, 'CREDIT', 500, 'KES', 100500, :now)
                    """).param("id", Ids.newId()).param("journal", journalId).param("wallet", walletId)
                    .param("now", now).update();
            // 2. Simulate a manual "fix": the stored balance no longer matches the entries.
            jdbc.sql("UPDATE wallet_account SET balance_minor = balance_minor + 700 WHERE id = :id")
                    .param("id", walletId).update();

            var r = reconciliation.run();
            status.setRollbackOnly();
            return r;
        });

        assertThat(report.clean()).isFalse();
        assertThat(report.mismatches()).containsEntry("unbalanced_journal", 1L)
                .containsEntry("balance_drift", 1L)       // stored +700 vs entries +500
                .containsEntry("trial_balance", 1L)       // KES no longer nets to zero
                .containsEntry("entry_currency", 0L);
        assertThat(report.samples()).extracting(LedgerReconciliation.Finding::check)
                .containsAll(List.of("unbalanced_journal", "balance_drift", "trial_balance"));
        assertThat(report.samples()).filteredOn(f -> f.check().equals("balance_drift"))
                .singleElement().satisfies(f -> {
                    assertThat(f.subject()).isEqualTo(walletId.toString());
                    assertThat(f.detail()).contains("stored=100700").contains("fromEntries=100500");
                });
        assertThat(gauge("unbalanced_journal")).isEqualTo(1.0);

        // Rolled back: the real ledger is still clean.
        assertThat(reconciliation.run().clean()).isTrue();
    }

    private double gauge(String check) {
        return metrics.get("kibetipay.reconciliation.mismatches").tags("scope", "ledger", "check", check)
                .gauge().value();
    }
}

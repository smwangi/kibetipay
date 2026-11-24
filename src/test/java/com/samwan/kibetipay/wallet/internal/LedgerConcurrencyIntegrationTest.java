package com.samwan.kibetipay.wallet.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samwan.kibetipay.TestcontainersConfig;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.support.Race;
import com.samwan.kibetipay.wallet.WalletApi;
import com.samwan.kibetipay.wallet.WalletApi.Posting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

/**
 * Financial integrity of the ledger under real concurrency, against real Postgres row locks.
 * Every test releases all of its threads at once from a start gate, then checks the money, not
 * just the return values, and finishes with the independent reconciliation.
 *
 * <p>Run only these with {@code mvn test -Dgroups=financial}.
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
class LedgerConcurrencyIntegrationTest {

    private static final String KES = "KES";

    @MockitoBean EmbeddingModel embeddingModel;

    @Autowired WalletApi wallets;
    @Autowired LedgerReconciliation reconciliation;
    @Autowired JdbcClient jdbc;

    /** 25 debits of 100.00 race for a balance of 1,000.00: exactly 10 may succeed. */
    @Test
    void concurrentDebitsNeverOverspend() throws Exception {
        String payer = user("payer");
        String payee = user("payee");
        wallets.deposit(payer, kes(1_000_00), "deposit:" + payer);

        var outcomes = race(25, i -> () -> wallets.post(
                new Posting("race-debit:" + payer + ":" + i, payer, payee, kes(100_00), Posting.Kind.TRANSFER)));

        assertThat(successes(outcomes)).hasSize(10);
        assertThat(failures(outcomes)).hasSize(15)
                .allSatisfy(e -> assertThat(e).isInstanceOf(WalletApi.InsufficientFundsException.class));
        assertThat(balance(payer)).isEqualTo(0);
        assertThat(balance(payee)).isEqualTo(1_000_00);
        assertThat(journalCount("race-debit:" + payer + ":%")).isEqualTo(10);
        assertLedgerClean();
    }

    /**
     * Opposite-direction transfers between the same two wallets are the classic deadlock. The
     * ledger locks wallets in id order, so every posting must succeed.
     */
    @Test
    void opposingTransfersDoNotDeadlock() throws Exception {
        String a = user("a");
        String b = user("b");
        wallets.deposit(a, kes(1_000_00), "deposit:" + a);
        wallets.deposit(b, kes(1_000_00), "deposit:" + b);

        var outcomes = race(40, i -> () -> {
            boolean aToB = i % 2 == 0;
            return wallets.post(new Posting("race-swap:" + a + ":" + i, aToB ? a : b, aToB ? b : a,
                    kes(10_00), Posting.Kind.TRANSFER));
        });

        assertThat(failures(outcomes)).as("no deadlock or lock timeout").isEmpty();
        // 20 each way: both end where they started, and nothing was created or lost.
        assertThat(balance(a)).isEqualTo(1_000_00);
        assertThat(balance(b)).isEqualTo(1_000_00);
        assertLedgerClean();
    }

    /**
     * A client retrying the same deposit from several connections at once (double-tapped button,
     * proxy retry). Money must move once, and every caller gets a receipt for that one posting:
     * a retry is never answered with an error.
     */
    @Test
    void sameReferenceRaceMovesMoneyOnce() throws Exception {
        String owner = user("retry");
        String reference = "deposit:" + owner + ":same-key";

        var outcomes = race(16, i -> () -> wallets.deposit(owner, kes(250_00), reference));

        assertThat(failures(outcomes)).as("no caller sees duplicate_posting").isEmpty();
        var receipts = successes(outcomes);
        assertThat(receipts).hasSize(16);
        assertThat(receipts.stream().filter(r -> !r.replayed()).count()).as("exactly one real posting").isEqualTo(1);
        assertThat(receipts).extracting(WalletApi.PostingReceipt::journalId).containsOnly(receipts.getFirst().journalId());
        assertThat(receipts).extracting(WalletApi.PostingReceipt::fromBalanceAfter).containsOnly(receipts.getFirst().fromBalanceAfter());
        assertThat(balance(owner)).isEqualTo(250_00);
        assertThat(journalCount(reference)).isEqualTo(1);

        // A different amount under the same reference is still a client bug, rejected loudly.
        assertThatThrownBy(() -> wallets.deposit(owner, kes(999_00), reference))
                .isInstanceOf(DomainException.Conflict.class);
        assertLedgerClean();
    }

    /**
     * Whole-ledger invariant under a random concurrent workload: deposits, transfers (some
     * overdrawing), and retries of earlier references. Afterwards every wallet must equal what an
     * independent model computes from the receipts that succeeded, and the reconciliation must be
     * clean. Fixed seed, so a failure is reproducible.
     */
    @Test
    void randomConcurrentWorkloadPreservesTheLedger() throws Exception {
        var random = new Random(20261124L);
        List<String> users = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            users.add(user("w" + i));
        }

        record Op(String reference, String from, String to, long amount) {}
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            if (i > 20 && random.nextInt(10) == 0) {
                ops.add(ops.get(random.nextInt(ops.size())));          // retry of an earlier operation
                continue;
            }
            String to = users.get(random.nextInt(users.size()));
            if (random.nextInt(4) == 0) {
                ops.add(new Op("inv-dep:" + to + ":" + i, null, to, 1 + random.nextInt(50_000)));
            } else {
                String from;
                do {
                    from = users.get(random.nextInt(users.size()));
                } while (from.equals(to));
                ops.add(new Op("inv-trf:" + from + ":" + i, from, to, 1 + random.nextInt(30_000)));
            }
        }

        var outcomes = race(ops.size(), i -> () -> {
            Op op = ops.get(i);
            return op.from() == null
                    ? wallets.deposit(op.to(), kes(op.amount()), op.reference())
                    : wallets.post(new Posting(op.reference(), op.from(), op.to(), kes(op.amount()), Posting.Kind.TRANSFER));
        });

        // The only failure the domain allows: overdraft. Concurrent retries replay, never conflict.
        assertThat(failures(outcomes)).allSatisfy(e -> assertThat(e).isInstanceOf(
                WalletApi.InsufficientFundsException.class));

        // Model: apply each reference that actually posted exactly once.
        Map<String, Long> expected = new HashMap<>();
        users.forEach(u -> expected.put(u, 0L));
        var applied = new java.util.HashSet<String>();
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (outcomes.get(i) instanceof WalletApi.PostingReceipt && applied.add(op.reference())) {
                expected.merge(op.to(), op.amount(), Long::sum);
                if (op.from() != null) {
                    expected.merge(op.from(), -op.amount(), Long::sum);
                }
            }
        }
        for (String u : users) {
            assertThat(balance(u)).as("balance of %s", u).isEqualTo(expected.get(u)).isGreaterThanOrEqualTo(0);
        }
        for (String reference : applied) {
            assertThat(journalCount(reference)).as("journals for %s", reference).isEqualTo(1);
        }
        assertThat(applied).as("the workload must actually exercise the ledger").hasSizeGreaterThan(100);
        assertLedgerClean();
    }

    // ---- helpers ----

    private static List<Object> race(int n, java.util.function.IntFunction<java.util.concurrent.Callable<?>> task)
            throws Exception {
        return Race.run(n, 16, task);
    }

    private static List<WalletApi.PostingReceipt> successes(List<Object> outcomes) {
        return Race.successes(outcomes, WalletApi.PostingReceipt.class);
    }

    private static List<Throwable> failures(List<Object> outcomes) {
        return Race.failures(outcomes);
    }

    private String user(String prefix) {
        String id = "fin-" + prefix + "-" + UUID.randomUUID();
        wallets.open(id, KES);
        return id;
    }

    private long balance(String owner) {
        return wallets.get(owner, KES).balance().minor();
    }

    private long journalCount(String referencePattern) {
        return jdbc.sql("SELECT count(*) FROM wallet_journal WHERE reference LIKE :r")
                .param("r", referencePattern).query(Long.class).single();
    }

    private void assertLedgerClean() {
        var report = reconciliation.run();
        assertThat(report.samples()).as("reconciliation findings").isEmpty();
        assertThat(report.clean()).isTrue();
    }

    private static Money kes(long minor) {
        return Money.of(minor, KES);
    }
}

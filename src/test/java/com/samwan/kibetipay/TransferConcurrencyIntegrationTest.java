package com.samwan.kibetipay;

import static org.assertj.core.api.Assertions.assertThat;

import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.support.Race;
import com.samwan.kibetipay.transfer.TransferQueries;
import com.samwan.kibetipay.transfer.internal.command.TransferCommandHandler;
import com.samwan.kibetipay.transfer.internal.command.TransferCommandHandler.Accepted;
import com.samwan.kibetipay.transfer.internal.command.TransferCommandHandler.InitiateTransfer;
import com.samwan.kibetipay.wallet.WalletApi;
import java.time.Duration;
import java.time.Instant;
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
 * The client-facing idempotency guarantee under concurrency: the same Idempotency-Key sent from
 * many connections at once creates one transfer and moves money once, and every caller is told
 * about that same transfer.
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
class TransferConcurrencyIntegrationTest {

    @MockitoBean EmbeddingModel embeddingModel;

    @Autowired WalletApi wallets;
    @Autowired TransferCommandHandler commands;
    @Autowired TransferQueries queries;
    @Autowired JdbcClient jdbc;

    @Test
    void sameIdempotencyKeyRaceCreatesOneTransfer() throws Exception {
        String alice = "fin-alice-" + UUID.randomUUID();
        String bob = "fin-bob-" + UUID.randomUUID();
        wallets.open(alice, "KES");
        wallets.open(bob, "KES");
        wallets.deposit(alice, Money.of(5_000_00, "KES"), "deposit:" + alice);
        var cmd = new InitiateTransfer(alice, bob, Money.of(1_000_00, "KES"), "rent share", "idem-" + UUID.randomUUID());

        var outcomes = Race.run(12, 12, i -> () -> commands.initiate(cmd));

        assertThat(Race.failures(outcomes)).as("every retry is answered, none errors").isEmpty();
        var accepted = Race.successes(outcomes, Accepted.class);
        assertThat(accepted).extracting(Accepted::transferId).containsOnly(accepted.getFirst().transferId());
        assertThat(accepted.stream().filter(a -> !a.replayed()).count()).as("exactly one created it").isEqualTo(1);
        UUID transferId = accepted.getFirst().transferId();
        assertThat(jdbc.sql("SELECT count(*) FROM transfer WHERE initiator_id = :a").param("a", alice)
                .query(Long.class).single()).isEqualTo(1);

        awaitStatus(transferId, alice, "COMPLETED");
        assertThat(wallets.get(alice, "KES").balance()).isEqualTo(Money.of(4_000_00, "KES"));
        assertThat(wallets.get(bob, "KES").balance()).isEqualTo(Money.of(1_000_00, "KES"));
        assertThat(jdbc.sql("SELECT count(*) FROM wallet_journal WHERE reference = :r")
                .param("r", "transfer:" + transferId).query(Long.class).single()).isEqualTo(1);
    }

    private void awaitStatus(UUID transferId, String owner, String expected) throws InterruptedException {
        var deadline = Instant.now().plus(Duration.ofSeconds(20));
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            status = queries.byId(transferId, owner).map(TransferQueries.TransferView::status).orElse(null);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Transfer " + transferId + " is " + status + ", expected " + expected);
    }
}

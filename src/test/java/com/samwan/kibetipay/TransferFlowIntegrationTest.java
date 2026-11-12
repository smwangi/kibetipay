package com.samwan.kibetipay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.transfer.TransferQueries;
import com.samwan.kibetipay.transfer.internal.command.TransferCommandHandler;
import com.samwan.kibetipay.transfer.internal.command.TransferCommandHandler.InitiateTransfer;
import com.samwan.kibetipay.wallet.WalletApi;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end through the real modules and a real Postgres: deposit ─▶ transfer ─▶ async risk
 * ─▶ ledger ─▶ COMPLETED, plus idempotency. AI calls are switched off so no model is needed.
 */
@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "app.insights.enabled=false",
        "app.assistant.ingest-on-startup=false",
        "spring.ai.ollama.init.pull-model-strategy=never",
        "spring.docker.compose.enabled=false"
})
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class TransferFlowIntegrationTest {

    @Autowired WalletApi wallets;
    @Autowired TransferCommandHandler commands;
    @Autowired TransferQueries queries;

    @Test
    void transferSettlesAndIsIdempotent() throws Exception {
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        wallets.open(alice, "KES");
        wallets.open(bob, "KES");
        wallets.deposit(alice, Money.of(5_000_00, "KES"), "deposit:" + alice + ":1");

        var cmd = new InitiateTransfer(alice, bob, Money.of(1_000_00, "KES"), "lunch", "idem-" + UUID.randomUUID());
        var first = commands.initiate(cmd);
        var retry = commands.initiate(cmd);

        assertThat(retry.transferId()).isEqualTo(first.transferId());
        assertThat(retry.replayed()).isTrue();

        // First transfer for alice -> "first_transfer" (15) only, well under review threshold.
        awaitStatus(first.transferId(), alice, "COMPLETED");
        assertThat(wallets.get(alice, "KES").balance()).isEqualTo(Money.of(4_000_00, "KES"));
        assertThat(wallets.get(bob, "KES").balance()).isEqualTo(Money.of(1_000_00, "KES"));
    }

    @Test
    void replayedDepositDoesNotDoubleCredit() {
        String carol = "carol-" + UUID.randomUUID();
        wallets.open(carol, "KES");
        var original = wallets.deposit(carol, Money.of(100_00, "KES"), "deposit:" + carol + ":same");
        wallets.deposit(carol, Money.of(20_00, "KES"), "deposit:" + carol + ":other");
        var replay = wallets.deposit(carol, Money.of(100_00, "KES"), "deposit:" + carol + ":same");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.fromBalanceAfter()).isEqualTo(original.fromBalanceAfter());
        assertThat(wallets.get(carol, "KES").balance()).isEqualTo(Money.of(120_00, "KES"));
        assertThatThrownBy(() -> wallets.deposit(carol, Money.of(200_00, "KES"), "deposit:" + carol + ":same"))
                .isInstanceOf(DomainException.Conflict.class);
    }

    private void awaitStatus(UUID id, String viewer, String expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            status = queries.byId(id, viewer).map(TransferQueries.TransferView::status).orElse(null);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(status).as("transfer status").isEqualTo(expected);
    }
}

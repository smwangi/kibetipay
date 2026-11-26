package com.samwan.kibetipay;

import static org.assertj.core.api.Assertions.assertThat;

import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.shared.paging.Page;
import com.samwan.kibetipay.shared.time.TimeConfig;
import com.samwan.kibetipay.transfer.TransferQueries;
import com.samwan.kibetipay.transfer.TransferQueries.TransferView;
import com.samwan.kibetipay.transfer.internal.command.TransferCommandHandler;
import com.samwan.kibetipay.transfer.internal.command.TransferCommandHandler.InitiateTransfer;
import com.samwan.kibetipay.wallet.WalletApi;
import com.samwan.kibetipay.wallet.WalletApi.StatementLine;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A customer can walk their whole history page by page, statements name the other person in a
 * transfer, and the dashboard summary reconciles with the wallet balance.
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
@Testcontainers(disabledWithoutDocker = true)
class CustomerHistoryIntegrationTest {

    /** The RAG assistant's vector store needs an embedding model; it plays no part in this test. */
    @MockitoBean EmbeddingModel embeddingModel;

    @Autowired WalletApi wallets;
    @Autowired TransferCommandHandler commands;
    @Autowired TransferQueries queries;

    @Test
    void historyPagesStatementNamesCounterpartyAndSummaryReconciles() throws Exception {
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        wallets.open(alice, "KES");
        wallets.open(bob, "KES");
        wallets.deposit(alice, Money.of(5_000_00, "KES"), "deposit:" + alice + ":1");

        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(commands.initiate(new InitiateTransfer(alice, bob, Money.of(100_00, "KES"), "t" + i,
                    "idem-" + UUID.randomUUID())).transferId());
        }
        for (UUID id : ids) {
            awaitProcessed(id, alice);
        }

        // Transfers: two pages of two, newest first, no duplicates and nothing skipped.
        var first = queries.historyFor(alice, new Page.Query(null, null, null, 2));
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();
        var second = queries.historyFor(alice, Page.Query.of(null, null, first.nextCursor(), 2, TimeConfig.BUSINESS_ZONE));
        assertThat(second.items()).hasSize(1);
        assertThat(second.nextCursor()).isNull();
        var seen = new ArrayList<UUID>();
        first.items().forEach(t -> seen.add(t.id()));
        second.items().forEach(t -> seen.add(t.id()));
        assertThat(seen).containsExactlyElementsOf(ids.reversed());
        // The recipient sees the same transfers in their own history.
        assertThat(queries.historyFor(bob, new Page.Query(null, null, null, 10)).items()).hasSize(3);

        // Statement: every completed transfer names bob; the deposit names nobody.
        long completed = ids.stream().map(id -> queries.byId(id, alice).orElseThrow())
                .filter(t -> "COMPLETED".equals(t.status())).count();
        assertThat(completed).as("at least the first low-risk transfer settles").isPositive();
        List<StatementLine> lines = wallets.statement(alice, "KES", new Page.Query(null, null, null, 50)).items();
        assertThat(lines).hasSize((int) completed + 1);
        assertThat(lines).filteredOn(l -> "TRANSFER".equals(l.kind()))
                .hasSize((int) completed)
                .allSatisfy(l -> assertThat(l.counterparty()).isEqualTo(bob));
        assertThat(lines).filteredOn(l -> "DEPOSIT".equals(l.kind()))
                .singleElement().satisfies(l -> assertThat(l.counterparty()).isNull());

        // Statement paging uses its own cursor and also walks every entry once.
        var page1 = wallets.statement(alice, "KES", new Page.Query(null, null, null, 1));
        var page2 = wallets.statement(alice, "KES", Page.Query.of(null, null, page1.nextCursor(), 50, TimeConfig.BUSINESS_ZONE));
        assertThat(page1.items().size() + page2.items().size()).isEqualTo(lines.size());

        // Summary: opening + in - out = closing = the wallet balance.
        var summary = wallets.summary(alice, "KES", 30, TimeConfig.BUSINESS_ZONE);
        var balance = wallets.get(alice, "KES").balance();
        assertThat(summary.closingBalance()).isEqualTo(balance);
        assertThat(summary.totalIn()).isEqualTo(Money.of(5_000_00, "KES"));
        assertThat(summary.totalOut()).isEqualTo(Money.of(100_00 * completed, "KES"));
        assertThat(summary.openingBalance().minor() + summary.totalIn().minor() - summary.totalOut().minor())
                .isEqualTo(balance.minor());
        assertThat(summary.daily()).hasSize(30);
        assertThat(summary.daily().getLast().closingBalance()).isEqualTo(balance);
        assertThat(summary.byKind()).extracting(WalletApi.KindTotal::kind).contains("DEPOSIT", "TRANSFER");
    }

    /** Waits until the saga has moved the transfer past risk scoring (completed, held or rejected). */
    private void awaitProcessed(UUID id, String viewer) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            status = queries.byId(id, viewer).map(TransferView::status).orElse(null);
            if (status != null && !"PENDING_RISK".equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(status).as("transfer " + id + " still pending").isNotEqualTo("PENDING_RISK");
    }
}

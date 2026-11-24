package com.samwan.kibetipay.wallet.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.samwan.kibetipay.TestcontainersConfig;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.transfer.TransferQueries;
import com.samwan.kibetipay.transfer.internal.command.TransferCommandHandler;
import com.samwan.kibetipay.transfer.internal.command.TransferCommandHandler.InitiateTransfer;
import com.samwan.kibetipay.wallet.WalletApi;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * "A down Collector loses telemetry, never money." Every OTLP exporter (traces, logs, metrics)
 * points at a black hole: a server that accepts connections and never answers, so each export
 * hangs until its timeout. That is worse than a refused connection and what a stuck Collector or
 * a dropped network route looks like. Payments must still complete promptly and correctly.
 */
@Tag("financial")
@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "app.insights.enabled=false",
        "app.assistant.ingest-on-startup=false",
        "spring.ai.ollama.init.pull-model-strategy=never",
        "spring.docker.compose.enabled=false",
        "management.tracing.sampling.probability=1.0",
        "management.otlp.metrics.export.enabled=true",
        "management.otlp.metrics.export.step=1s",
        // Many transfers from one payer: keep the velocity rule from sending them to review.
        "app.risk.velocity-max-transfers=1000"
})
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class TelemetryOutageIntegrationTest {

    private static final BlackHole COLLECTOR = BlackHole.start();

    @DynamicPropertySource
    static void exportToTheBlackHole(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + COLLECTOR.port();
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint", () -> base + "/v1/traces");
        registry.add("management.opentelemetry.logging.export.otlp.endpoint", () -> base + "/v1/logs");
        registry.add("management.otlp.metrics.export.url", () -> base + "/v1/metrics");
    }

    @AfterAll
    static void closeBlackHole() {
        COLLECTOR.close();
    }

    @MockitoBean EmbeddingModel embeddingModel;

    @Autowired WalletApi wallets;
    @Autowired TransferCommandHandler commands;
    @Autowired TransferQueries queries;
    @Autowired LedgerReconciliation reconciliation;

    @Test
    void paymentsCompleteWhileEveryExporterHangs() throws Exception {
        String payer = "outage-a-" + UUID.randomUUID();
        String payee = "outage-b-" + UUID.randomUUID();
        wallets.open(payer, "KES");
        wallets.open(payee, "KES");
        wallets.deposit(payer, Money.of(10_000_00, "KES"), "deposit:" + payer);

        List<UUID> transfers = new ArrayList<>();
        long slowestMillis = 0;
        var deadline = Instant.now().plus(Duration.ofSeconds(12));
        // Keep paying for long enough that exports are attempted and stuck while requests run
        // (spans are batched every 5s, metrics pushed every 1s), with a floor of 20 transfers.
        for (int i = 0; transfers.size() < 20 || Instant.now().isBefore(deadline); i++) {
            long start = System.nanoTime();
            var accepted = commands.initiate(new InitiateTransfer(payer, payee, Money.of(10_00, "KES"), "outage",
                    "outage-" + payer + "-" + i));
            slowestMillis = Math.max(slowestMillis, Duration.ofNanos(System.nanoTime() - start).toMillis());
            transfers.add(accepted.transferId());
            Thread.sleep(100);
        }

        assertThat(COLLECTOR.connections()).as("exports were attempted, so the test exercised the outage")
                .isGreaterThan(0);
        // The exporters' timeout is 10s; a request that waited on export would show it.
        assertThat(slowestMillis).as("slowest transfer request (ms)").isLessThan(3_000);

        for (UUID id : transfers) {
            awaitCompleted(id, payer);
        }
        long moved = transfers.size() * 10_00L;
        assertThat(wallets.get(payer, "KES").balance().minor()).isEqualTo(10_000_00 - moved);
        assertThat(wallets.get(payee, "KES").balance().minor()).isEqualTo(moved);
        assertThat(reconciliation.run().clean()).isTrue();
    }

    private void awaitCompleted(UUID id, String viewer) throws InterruptedException {
        var deadline = Instant.now().plus(Duration.ofSeconds(30));
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            status = queries.byId(id, viewer).map(TransferQueries.TransferView::status).orElse(null);
            if ("COMPLETED".equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Transfer " + id + " is " + status + " during the telemetry outage");
    }

    /** Accepts TCP connections and never reads or writes: every request hangs until the client gives up. */
    static final class BlackHole implements AutoCloseable {
        private final ServerSocket server;
        private final AtomicInteger connections = new AtomicInteger();
        private final List<Socket> held = new CopyOnWriteArrayList<>();

        private BlackHole(ServerSocket server) {
            this.server = server;
            Thread.ofVirtual().name("otlp-black-hole").start(() -> {
                while (!server.isClosed()) {
                    try {
                        held.add(server.accept());
                        connections.incrementAndGet();
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
        }

        static BlackHole start() {
            try {
                return new BlackHole(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        int port() {
            return server.getLocalPort();
        }

        int connections() {
            return connections.get();
        }

        @Override
        public void close() {
            held.forEach(s -> {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            });
            try {
                server.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }
}

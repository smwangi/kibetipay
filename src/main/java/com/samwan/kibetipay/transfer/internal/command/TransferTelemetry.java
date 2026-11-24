package com.samwan.kibetipay.transfer.internal.command;

import com.samwan.kibetipay.shared.observability.TraceOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/**
 * One place for the transfer module's business telemetry, so event names, log fields and metric
 * tags stay consistent and bounded.
 *
 * <p>Rules this class enforces:
 * <ul>
 *   <li>Metric tags only take values from fixed vocabularies (outcomes, {@code DomainException}
 *       codes). Ids such as transferId, txnRef or user ids belong in logs and traces, never in tags.</li>
 *   <li>Log events have a stable {@code event} name and carry {@code transferId} so every attempt
 *       of one transfer can be found across retries and restarts; the trace id is added by the
 *       logging integration. No user ids, balances, notes or raw idempotency keys are logged.</li>
 *   <li>Amounts are logged in minor units.</li>
 * </ul>
 *
 * <p>Telemetry describes what the system attempted; the {@code transfer} table and the ledger
 * remain the record of what actually happened to the money.
 */
@Component
class TransferTelemetry {

    private static final Logger log = LoggerFactory.getLogger("kibetipay.transfer.events");

    private final MeterRegistry metrics;
    private final TraceOutcome trace;

    TransferTelemetry(MeterRegistry metrics, TraceOutcome trace) {
        this.metrics = metrics;
        this.trace = trace;
    }

    // ---- request side (synchronous, inside POST /api/transfers) ----

    void requested(Transfer t) {
        event(log.atInfo(), "transfer.requested", t.id())
                .addKeyValue("txnRef", t.txnRef())
                .addKeyValue("amountMinor", t.amount().minor())
                .addKeyValue("currency", t.amount().currencyCode())
                .log("Transfer {} requested", t.id());
        metrics.counter("kibetipay.transfer.requests", "result", "accepted").increment();
    }

    /** A legitimate client retry: same key, same payload. Not an error. */
    void replayed(Transfer t) {
        event(log.atInfo(), "transfer.idempotency.replayed", t.id())
                .addKeyValue("status", t.status().name())
                .log("Transfer {} replayed for a repeated Idempotency-Key", t.id());
        metrics.counter("kibetipay.transfer.requests", "result", "replayed").increment();
        metrics.counter("kibetipay.idempotency.replays", "scope", "transfer").increment();
    }

    /** Same key, different payload: a client bug worth surfacing. */
    void idempotencyConflict(Transfer existing) {
        trace.mark("rejected");
        event(log.atWarn(), "transfer.idempotency.conflict", existing.id())
                .log("Idempotency-Key reused with a different payload (original transfer {})", existing.id());
        metrics.counter("kibetipay.transfer.requests", "result", "rejected").increment();
        metrics.counter("kibetipay.transfer.rejected", "stage", "request", "reason", "idempotency_key_reused")
                .increment();
    }

    /** Validation refused the request before anything was stored; {@code code} is a DomainException code. */
    void requestRejected(String code) {
        trace.mark("rejected");
        log.atInfo()
                .addKeyValue("event", "transfer.request.rejected")
                .addKeyValue("reason", code)
                .log("Transfer request rejected: {}", code);
        metrics.counter("kibetipay.transfer.requests", "result", "rejected").increment();
        metrics.counter("kibetipay.transfer.rejected", "stage", "request", "reason", code).increment();
    }

    // ---- saga side (asynchronous, after commit) ----

    void completed(Transfer t, UUID journalId) {
        Duration settlement = Duration.between(t.createdAt(), Instant.now());
        event(log.atInfo(), "transfer.completed", t.id())
                .addKeyValue("journalId", journalId)
                .addKeyValue("amountMinor", t.amount().minor())
                .addKeyValue("currency", t.amount().currencyCode())
                .addKeyValue("riskScore", t.riskScore())
                .addKeyValue("settlementMs", settlement.toMillis())
                .log("Transfer {} completed", t.id());
        outcome("completed");
        // Request accepted -> money moved. Includes risk scoring and any time held for review.
        metrics.timer("kibetipay.transfer.settlement").record(settlement);
    }

    /** Rejected after acceptance, by the risk engine or by an analyst. */
    void rejected(Transfer t, Integer score, RejectionStage stage) {
        trace.mark("rejected");
        event(log.atInfo(), "transfer.rejected", t.id())
                .addKeyValue("stage", stage.tag)
                .addKeyValue("riskScore", score)
                .log("Transfer {} rejected ({})", t.id(), stage.tag);
        outcome("rejected");
        metrics.counter("kibetipay.transfer.rejected", "stage", stage.tag, "reason", stage.reason).increment();
    }

    enum RejectionStage {
        RISK("risk", "risk_rejected"),
        REVIEW("review", "review_declined");

        final String tag;
        final String reason;

        RejectionStage(String tag, String reason) {
            this.tag = tag;
            this.reason = reason;
        }
    }

    void heldForReview(Transfer t, int score) {
        trace.mark("review");
        event(log.atInfo(), "transfer.held_for_review", t.id())
                .addKeyValue("riskScore", score)
                .log("Transfer {} held for review", t.id());
        outcome("review");
    }

    /**
     * The ledger refused the posting for a business reason (funds spent since the request, wallet
     * frozen). A confirmed, final failure: no money moved.
     */
    void failed(UUID transferId, String code) {
        trace.mark("failed");
        event(log.atWarn(), "transfer.failed", transferId)
                .addKeyValue("reason", code)
                .log("Transfer {} failed: ledger refused posting ({})", transferId, code);
        outcome("failed");
        metrics.counter("kibetipay.ledger.posting.failures", "kind", "TRANSFER", "reason", code).increment();
    }

    /**
     * An unexpected error interrupted the saga. Unlike {@link #failed}, the outcome is unknown at
     * this point: the outbox event stays incomplete and is republished, and the idempotent steps
     * resume. Alert when these persist, and reconcile against the ledger, not against this log.
     */
    void processingError(UUID transferId, Throwable error) {
        trace.mark("unknown");
        event(log.atError(), "transfer.processing.error", transferId)
                .addKeyValue("outcome", "unknown")
                .addKeyValue("exception", error.getClass().getName())
                .setCause(error)
                .log("Transfer {} processing interrupted; outcome unknown until the saga resumes", transferId);
        metrics.counter("kibetipay.transfer.processing.errors",
                "exception", error.getClass().getSimpleName()).increment();
    }

    /** Final saga outcomes; counted once per transfer because each state change is guarded by isTerminal(). */
    private void outcome(String outcome) {
        metrics.counter("kibetipay.transfers", "outcome", outcome).increment();
    }

    private static LoggingEventBuilder event(LoggingEventBuilder builder, String event, UUID transferId) {
        return builder.addKeyValue("event", event).addKeyValue("transferId", transferId);
    }
}

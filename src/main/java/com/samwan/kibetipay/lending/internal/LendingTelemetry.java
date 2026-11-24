package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.observability.AfterCommit;
import com.samwan.kibetipay.shared.observability.TraceOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/**
 * Business telemetry for the lending module: loan decisions, repayments and agent commission.
 * Same rules as the transfer module's telemetry:
 * <ul>
 *   <li>Metric tags take values from fixed vocabularies only. loanId, repaymentId and user ids go
 *       to logs and traces, never to tags.</li>
 *   <li>Every state change is reported after its transaction commits, and only by the call that
 *       made it, so replays and retries are not counted twice.</li>
 *   <li>No borrower ids, phone numbers or balances in logs; amounts in minor units.</li>
 * </ul>
 * The loan, repayment and commission tables plus the ledger remain the financial record.
 */
@Component
class LendingTelemetry {

    private static final Logger log = LoggerFactory.getLogger("kibetipay.lending.events");

    /** Why a loan was declined. Bounded, so it can be a metric tag. */
    enum DeclineReason {
        CREDIT("credit"),
        AGENT_LIMIT("agent_limit_exceeded"),
        FRAUD("fraud"),
        POOL_EXHAUSTED("lending_pool_exhausted"),
        ANALYST("analyst");

        final String tag;

        DeclineReason(String tag) {
            this.tag = tag;
        }
    }

    /** Why a loan went to a human. */
    enum ReviewReason {
        BORDERLINE_CREDIT("borderline_credit"),
        FRAUD_SIGNALS("fraud_signals");

        final String tag;

        ReviewReason(String tag) {
            this.tag = tag;
        }
    }

    private final MeterRegistry metrics;
    private final TraceOutcome trace;

    LendingTelemetry(MeterRegistry metrics, TraceOutcome trace) {
        this.metrics = metrics;
        this.trace = trace;
    }

    // ---- loan decisions ----

    void disbursed(Loan loan, UUID journalId) {
        UUID loanId = loan.id();
        long principal = loan.principal().minor();
        String currency = loan.currency();
        String product = loan.productCode();
        AfterCommit.run(() -> {
            event(log.atInfo(), "loan.disbursed", loanId)
                    .addKeyValue("journalId", journalId)
                    .addKeyValue("product", product)
                    .addKeyValue("amountMinor", principal)
                    .addKeyValue("currency", currency)
                    .log("Loan {} disbursed", loanId);
            outcome("disbursed");
        });
    }

    void declined(UUID loanId, DeclineReason reason) {
        trace.mark("rejected");
        AfterCommit.run(() -> {
            event(log.atInfo(), "loan.declined", loanId)
                    .addKeyValue("reason", reason.tag)
                    .log("Loan {} declined ({})", loanId, reason.tag);
            outcome("declined");
            metrics.counter("kibetipay.loans.declined", "reason", reason.tag).increment();
        });
    }

    void heldForReview(UUID loanId, ReviewReason reason) {
        trace.mark("review");
        AfterCommit.run(() -> {
            event(log.atInfo(), "loan.held_for_review", loanId)
                    .addKeyValue("reason", reason.tag)
                    .log("Loan {} held for review ({})", loanId, reason.tag);
            outcome("review");
        });
    }

    // ---- repayments ----

    void repaymentPosted(UUID loanId, UUID repaymentId, RepaymentService.Channel channel, long amountMinor,
                         String currency, long principalMinor, long interestMinor, long feeMinor, long penaltyMinor,
                         boolean loanClosed) {
        AfterCommit.run(() -> {
            event(log.atInfo(), "loan.repayment.posted", loanId)
                    .addKeyValue("repaymentId", repaymentId)
                    .addKeyValue("channel", channel.name())
                    .addKeyValue("amountMinor", amountMinor)
                    .addKeyValue("currency", currency)
                    .addKeyValue("principalMinor", principalMinor)
                    .addKeyValue("interestMinor", interestMinor)
                    .addKeyValue("feeMinor", feeMinor)
                    .addKeyValue("penaltyMinor", penaltyMinor)
                    .addKeyValue("loanClosed", loanClosed)
                    .log("Repayment {} posted to loan {}", repaymentId, loanId);
            metrics.counter("kibetipay.lending.repayments", "result", "posted", "channel", channel.name()).increment();
            if (loanClosed) {
                event(log.atInfo(), "loan.repaid", loanId).log("Loan {} fully repaid", loanId);
                outcome("repaid");
            }
        });
    }

    /** Same reference, same payload: a legitimate retry. */
    void repaymentReplayed(UUID loanId, RepaymentService.Channel channel) {
        event(log.atInfo(), "loan.repayment.replayed", loanId)
                .addKeyValue("channel", channel.name())
                .log("Repayment replayed for loan {}", loanId);
        metrics.counter("kibetipay.lending.repayments", "result", "replayed", "channel", channel.name()).increment();
        metrics.counter("kibetipay.idempotency.replays", "scope", "repayment").increment();
    }

    /** Refused before money moved; nothing was committed. */
    void repaymentRejected(UUID loanId, RepaymentService.Channel channel, DomainException rejected) {
        trace.mark("rejected");
        event(log.atInfo(), "loan.repayment.rejected", loanId)
                .addKeyValue("channel", channel.name())
                .addKeyValue("reason", rejected.code())
                .log("Repayment for loan {} rejected: {}", loanId, rejected.code());
        metrics.counter("kibetipay.lending.repayments", "result", "rejected", "channel", channel.name()).increment();
        metrics.counter("kibetipay.lending.repayment.rejected", "reason", rejected.code()).increment();
    }

    // ---- commission ----

    void commissionPosted(UUID loanId, UUID repaymentId, long poolMinor, String currency, int levels) {
        AfterCommit.run(() -> {
            event(log.atInfo(), "commission.posted", loanId)
                    .addKeyValue("repaymentId", repaymentId)
                    .addKeyValue("amountMinor", poolMinor)
                    .addKeyValue("currency", currency)
                    .addKeyValue("levels", levels)
                    .log("Commission for repayment {} posted across {} agent levels", repaymentId, levels);
            metrics.counter("kibetipay.commission.postings", "result", "posted").increment();
            metrics.counter("kibetipay.commission.paid.minor", "currency", currency).increment(poolMinor);
        });
    }

    /**
     * Commission posting failed. It shares the repayment's transaction, so the whole repayment
     * rolls back: no money moved and the payer can retry with the same reference.
     */
    void commissionFailed(UUID loanId, UUID repaymentId, RuntimeException error) {
        trace.mark("failed");
        String reason = error instanceof DomainException d ? d.code() : error.getClass().getSimpleName();
        event(log.atError(), "commission.posting.failed", loanId)
                .addKeyValue("repaymentId", repaymentId)
                .addKeyValue("reason", reason)
                .setCause(error)
                .log("Commission posting for repayment {} failed; repayment rolled back", repaymentId);
        metrics.counter("kibetipay.commission.postings", "result", "failed").increment();
        metrics.counter("kibetipay.commission.posting.failures", "reason", reason).increment();
    }

    private void outcome(String outcome) {
        metrics.counter("kibetipay.loans", "outcome", outcome).increment();
    }

    private static LoggingEventBuilder event(LoggingEventBuilder builder, String event, UUID loanId) {
        return builder.addKeyValue("event", event).addKeyValue("loanId", loanId);
    }
}

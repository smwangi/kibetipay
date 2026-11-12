package com.samwan.kibetipay.search.internal;

import static com.samwan.kibetipay.search.internal.DocumentWriter.fields;

import com.samwan.kibetipay.lending.LendingEvents.LoanDeclined;
import com.samwan.kibetipay.lending.LendingEvents.LoanDefaulted;
import com.samwan.kibetipay.lending.LendingEvents.LoanDisbursed;
import com.samwan.kibetipay.lending.LendingEvents.LoanRepaid;
import com.samwan.kibetipay.lending.LendingEvents.RepaymentReceived;
import com.samwan.kibetipay.search.internal.SearchIndices.Kind;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.opensearch.client.json.JsonData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Projects lending events into the {@code loans} index: one document per loan, enriched as events arrive.
 * Loans enter the index when disbursed or declined ({@code LoanApplied} only carries an id).
 */
@Component
@ConditionalOnBooleanProperty("app.search.enabled")
class LoanIndexer {

    private enum Status { ACTIVE, DEFAULTED, REPAID, DECLINED }

    static final Map<String, Integer> RANKS = Map.of(Status.ACTIVE.name(), 1, Status.DEFAULTED.name(), 2, Status.REPAID.name(), 3, Status.DECLINED.name(), 3);

    /**
     * A running total is NOT naturally idempotent: a redelivered repayment would be counted twice.
     * So the document remembers which repayment ids it has applied and turns a repeat into a no-op.
     */
    static final String REPAYMENT_SCRIPT = """
            if (ctx._source.repaymentIds == null) {
              ctx._source.repaymentIds = [];
            }
            if (ctx._source.repaymentIds.contains(params.repaymentId)) {
              ctx.op = 'none';
            } else {
              ctx._source.repaymentIds.add(params.repaymentId);
              ctx._source.loanId = params.loanId;
              ctx._source.borrowerId = params.borrowerId;
              ctx._source.repaidMinor = (ctx._source.repaidMinor == null ? 0 : ctx._source.repaidMinor) + params.amountMinor;
              ctx._source.repaymentCount = (ctx._source.repaymentCount == null ? 0 : ctx._source.repaymentCount) + 1;
              if (ctx._source.lastRepaymentAt == null || params.at > ctx._source.lastRepaymentAt) {
                ctx._source.lastRepaymentAt = params.at;
              }
              if (ctx._source.updatedAt == null || params.at > ctx._source.updatedAt) {
                ctx._source.updatedAt = params.at;
              }
            }
            """;

    private final DocumentWriter writer;

    LoanIndexer(DocumentWriter writer) {
        this.writer = writer;
    }

    @ApplicationModuleListener
    @Retryable(maxRetries = 3, delay = 200, multiplier = 3)
    void on(LoanDisbursed e) {
        writer.upsertWithStatus(Kind.LOANS, e.loanId().toString(), fields(
                "loanId", e.loanId().toString(),
                "borrowerId", e.borrowerId(),
                "agentId", str(e.agentId()),
                "principalMinor", e.principalMinor(),
                "currency", e.currency(),
                "disbursedAt", e.at().toEpochMilli()), Status.ACTIVE.name(), RANKS, e.at());
    }

    @ApplicationModuleListener
    @Retryable(maxRetries = 3, delay = 200, multiplier = 3)
    void on(LoanDeclined e) {
        writer.upsertWithStatus(Kind.LOANS, e.loanId().toString(), fields(
                "loanId", e.loanId().toString(),
                "borrowerId", e.borrowerId(),
                "declineReasons", e.reasons()), Status.DECLINED.name(), RANKS, e.at());
    }

    @ApplicationModuleListener
    @Retryable(maxRetries = 3, delay = 200, multiplier = 3)
    void on(RepaymentReceived e) {
        writer.scriptedUpsert(Kind.LOANS, e.loanId().toString(), REPAYMENT_SCRIPT, Map.of(
                "loanId", JsonData.of(e.loanId().toString()),
                "repaymentId", JsonData.of(e.repaymentId().toString()),
                "borrowerId", JsonData.of(e.borrowerId()),
                "amountMinor", JsonData.of(e.amountMinor()),
                "at", JsonData.of(e.at().toEpochMilli())));
    }

    @ApplicationModuleListener
    @Retryable(maxRetries = 3, delay = 200, multiplier = 3)
    void on(LoanRepaid e) {
        writer.upsertWithStatus(Kind.LOANS, e.loanId().toString(), fields(
                "loanId", e.loanId().toString(),
                "borrowerId", e.borrowerId(),
                "agentId", str(e.agentId())), Status.REPAID.name(), RANKS, e.at());
    }

    @ApplicationModuleListener
    @Retryable(maxRetries = 3, delay = 200, multiplier = 3)
    void on(LoanDefaulted e) {
        writer.upsertWithStatus(Kind.LOANS, e.loanId().toString(), fields(
                "loanId", e.loanId().toString(),
                "borrowerId", e.borrowerId(),
                "agentId", str(e.agentId()),
                "outstandingMinor", e.outstandingMinor()), Status.DEFAULTED.name(), RANKS, e.at());
    }

    private static String str(UUID id) {
        return Objects.toString(id, null);
    }
}

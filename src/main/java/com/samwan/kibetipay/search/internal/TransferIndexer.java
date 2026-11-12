package com.samwan.kibetipay.search.internal;

import static com.samwan.kibetipay.search.internal.DocumentWriter.fields;

import com.samwan.kibetipay.search.internal.SearchIndices.Kind;
import com.samwan.kibetipay.transfer.TransferEvents.TransferCompleted;
import com.samwan.kibetipay.transfer.TransferEvents.TransferHeldForReview;
import com.samwan.kibetipay.transfer.TransferEvents.TransferRejected;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Projects transfer events into the {@code transfers} index. Postgres stays the source of truth; this is a
 * read model that may lag by about a second.
 *
 * <p>Failure handling, outermost first: Spring Modulith's outbox keeps the event until a listener succeeds
 * (republished on restart); {@code @Retryable} absorbs short blips; the writer's scripts make retries safe.
 */
@Component
@ConditionalOnBooleanProperty("app.search.enabled")
class TransferIndexer {

    private enum Status { HELD_FOR_REVIEW, COMPLETED, REJECTED }

    static final Map<String, Integer> RANKS = Map.of(Status.HELD_FOR_REVIEW.name(), 1, Status.COMPLETED.name(), 2, Status.REJECTED.name(), 2);

    private final DocumentWriter writer;

    TransferIndexer(DocumentWriter writer) {
        this.writer = writer;
    }

    @ApplicationModuleListener
    @Retryable(maxRetries = 3, delay = 200, multiplier = 3)
    void on(TransferCompleted e) {
        writer.upsertWithStatus(Kind.TRANSFERS, e.transferId().toString(), fields(
                "transferId", e.transferId().toString(),
                "initiatorId", e.initiatorId(),
                "recipientId", e.recipientId(),
                "amountMinor", e.amountMinor(),
                "currency", e.currency(),
                "note", e.note(),
                "riskScore", e.riskScore()), Status.COMPLETED.name(), RANKS, e.completedAt());
    }

    @ApplicationModuleListener
    @Retryable(maxRetries = 3, delay = 200, multiplier = 3)
    void on(TransferHeldForReview e) {
        writer.upsertWithStatus(Kind.TRANSFERS, e.transferId().toString(), fields(
                "transferId", e.transferId().toString(),
                "initiatorId", e.initiatorId(),
                "riskScore", e.riskScore()), Status.HELD_FOR_REVIEW.name(), RANKS, e.at());
    }

    @ApplicationModuleListener
    @Retryable(maxRetries = 3, delay = 200, multiplier = 3)
    void on(TransferRejected e) {
        writer.upsertWithStatus(Kind.TRANSFERS, e.transferId().toString(), fields(
                "transferId", e.transferId().toString(),
                "initiatorId", e.initiatorId(),
                "reason", e.reason()), Status.REJECTED.name(), RANKS, e.at());
    }
}

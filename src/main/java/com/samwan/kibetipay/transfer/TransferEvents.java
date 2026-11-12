package com.samwan.kibetipay.transfer;

import java.time.Instant;
import java.util.UUID;

/**
 * Published integration events. They are the transfer module's public contract: other
 * modules (anomaly, insights) react to them without the transfer module knowing they exist.
 *
 * <p>Events carry primitives and ids only, never entities, so they can later be externalized
 * to Kafka/Service Bus unchanged (Spring Modulith {@code @Externalized}).
 */
public final class TransferEvents {

    private TransferEvents() {
    }

    /** Internal kick-off of the async pipeline (risk, then ledger posting). */
    public record TransferRequested(UUID transferId) {}

    public record TransferCompleted(UUID transferId, String initiatorId, String recipientId,
                                    long amountMinor, String currency, String note,
                                    int riskScore, Instant completedAt) {}

    public record TransferRejected(UUID transferId, String initiatorId, String reason, Instant at) {}

    public record TransferHeldForReview(UUID transferId, String initiatorId, int riskScore, Instant at) {}
}

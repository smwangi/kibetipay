package com.samwan.kibetipay.transfer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of the transfer module (the Q in CQRS). Used by the REST API and the AI assistant. */
public interface TransferQueries {

    Optional<TransferView> byId(UUID id, String viewerId);

    List<TransferView> recentFor(String ownerId, int limit);

    List<TransferView> inReview(int limit);

    record TransferView(UUID id, String initiatorId, String recipientId, String amount, String currency,
                        String note, String status, Integer riskScore, String failureReason,
                        Instant createdAt, Instant updatedAt) {}
}

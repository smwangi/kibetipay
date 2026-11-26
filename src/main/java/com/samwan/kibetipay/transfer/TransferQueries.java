package com.samwan.kibetipay.transfer;

import com.samwan.kibetipay.shared.paging.Page;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of the transfer module (the Q in CQRS). Used by the REST API and the AI assistant. */
public interface TransferQueries {

    Optional<TransferView> byId(UUID id, String viewerId);

    /** Lookup by the customer-facing reference printed on receipts. */
    Optional<TransferView> byTxnRef(String txnRef, String viewerId);

    List<TransferView> recentFor(String ownerId, int limit);

    /** The participant's transfers (sent and received), newest first, one keyset page at a time. */
    Page<TransferView> historyFor(String ownerId, Page.Query query);

    List<TransferView> inReview(int limit);

    /**
     * Internal view, including the risk score and the raw failure reason (risk rules that fired,
     * analyst notes, ledger codes). For staff and internal processing only: never return it to a
     * customer, use {@link CustomerTransferView#of} instead.
     */
    record TransferView(UUID id, String txnRef, String initiatorId, String recipientId, String amount, String currency,
                        String note, String status, Integer riskScore, String failureReason,
                        Instant createdAt, Instant updatedAt) {}

    /**
     * What a sender or recipient may see. Risk scores and rule names are withheld because they
     * show a fraudster exactly which thresholds to stay under; {@code statusMessage} explains the
     * outcome in plain language instead.
     */
    record CustomerTransferView(UUID id, String txnRef, String initiatorId, String recipientId, String amount,
                                String currency, String note, String status, String statusMessage,
                                Instant createdAt, Instant updatedAt) {

        public static CustomerTransferView of(TransferView t) {
            return new CustomerTransferView(t.id(), t.txnRef(), t.initiatorId(), t.recipientId(), t.amount(),
                    t.currency(), t.note(), t.status(), customerMessage(t.status(), t.failureReason()), t.createdAt(),
                    t.updatedAt());
        }
    }

    /**
     * Plain-language explanation of a transfer's status, safe to show the customer. {@code null}
     * for a completed transfer. {@code failureReason} is only used to pick between fixed messages;
     * its text is never passed through.
     */
    static String customerMessage(String status, String failureReason) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "PENDING_RISK" -> "We're processing this transfer.";
            case "IN_REVIEW" -> "We're running an extra check on this transfer. The money has not left your wallet yet.";
            case "REJECTED" -> "This transfer was declined after a security check. No money left your wallet. "
                    + "If you think this is a mistake, contact support and quote the reference.";
            case "FAILED" -> failedMessage(failureReason);
            default -> null;
        };
    }

    private static String failedMessage(String code) {
        String reason = code == null ? "" : code;
        return switch (reason) {
            case "insufficient_funds" ->
                    "Your balance was too low when we processed this transfer. No money left your wallet.";
            case "wallet_not_found" -> "The recipient's wallet could not be found. No money left your wallet.";
            default -> "This transfer could not be completed. No money left your wallet. "
                    + "Contact support and quote the reference if this keeps happening.";
        };
    }
}

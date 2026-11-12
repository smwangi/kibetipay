package com.samwan.kibetipay.transfer.internal.command;

import com.samwan.kibetipay.shared.error.DomainException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Human-in-the-loop: an analyst approves or declines a transfer that risk sent to review. */
@Service
public class TransferReviewHandler {

    private final TransferRepository transfers;
    private final TransferProcessor processor;

    TransferReviewHandler(TransferRepository transfers, TransferProcessor processor) {
        this.transfers = transfers;
        this.processor = processor;
    }

    public void approve(UUID transferId) {
        requireInReview(transferId);
        processor.settle(transferId, null);
    }

    public void decline(UUID transferId, String reason) {
        requireInReview(transferId);
        processor.reject(transferId, null, "declined_by_analyst: " + reason);
    }

    private void requireInReview(UUID transferId) {
        var t = transfers.findById(transferId)
                .orElseThrow(() -> new DomainException.NotFound("transfer_not_found", "No transfer " + transferId));
        if (t.status() != Transfer.Status.IN_REVIEW) {
            throw new DomainException.Conflict("not_in_review", "Transfer is " + t.status());
        }
    }
}

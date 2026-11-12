package com.samwan.kibetipay.transfer.internal.command;

import com.samwan.kibetipay.risk.RiskApi;
import com.samwan.kibetipay.risk.RiskApi.RiskDecision;
import com.samwan.kibetipay.risk.RiskApi.RiskRequest;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.transfer.TransferEvents.TransferCompleted;
import com.samwan.kibetipay.transfer.TransferEvents.TransferHeldForReview;
import com.samwan.kibetipay.transfer.TransferEvents.TransferRejected;
import com.samwan.kibetipay.transfer.TransferEvents.TransferRequested;
import com.samwan.kibetipay.wallet.WalletApi;
import com.samwan.kibetipay.wallet.WalletApi.Posting;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates the transfer saga: risk ─▶ ledger posting ─▶ final state.
 *
 * <p>Each step is its own local transaction and each step is idempotent:
 * <ul>
 *   <li>risk returns the stored assessment if the transfer was already scored,</li>
 *   <li>the ledger ignores a second posting with the same reference,</li>
 *   <li>the final state change is skipped if the transfer is already terminal.</li>
 * </ul>
 * So if the process crashes between steps, Spring Modulith republishes the incomplete
 * event on restart and the saga simply resumes. No distributed transaction needed.
 */
@Component
class TransferProcessor {

    private static final Logger log = LoggerFactory.getLogger(TransferProcessor.class);

    private final TransferRepository transfers;
    private final RiskApi risk;
    private final WalletApi wallets;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;

    TransferProcessor(TransferRepository transfers, RiskApi risk, WalletApi wallets,
                      ApplicationEventPublisher events, TransactionTemplate tx, MeterRegistry metrics) {
        this.transfers = transfers;
        this.risk = risk;
        this.wallets = wallets;
        this.events = events;
        this.tx = tx;
        this.metrics = metrics;
    }

    /**
     * Runs after the initiating transaction commits, on a separate (virtual) thread.
     * Not {@code @ApplicationModuleListener} on purpose: that wraps the whole listener in one
     * transaction, and a failed ledger posting would then roll back the risk record too.
     */
    @Async
    @TransactionalEventListener
    @Observed(name = "transfer.process")
    void on(TransferRequested event) {
        process(event.transferId());
    }

    void process(UUID transferId) {
        var transfer = transfers.findById(transferId).orElseThrow();
        if (transfer.status() != Transfer.Status.PENDING_RISK) {
            log.debug("Transfer {} already {}; nothing to do", transferId, transfer.status());
            return;
        }

        var balance = wallets.get(transfer.initiatorId(), transfer.amount().currencyCode()).balance();
        RiskDecision decision = risk.assess(new RiskRequest(transferId, transfer.initiatorId(),
                transfer.recipientId(), transfer.amount(), balance, transfer.createdAt()));

        switch (decision.outcome()) {
            case REJECT -> reject(transferId, decision.score(), "risk_rejected: " + String.join(", ", decision.reasons()));
            case REVIEW -> holdForReview(transferId, decision.score());
            case APPROVE -> settle(transferId, decision.score());
        }
    }

    /** Also called by the admin review flow once a human approves. */
    void settle(UUID transferId, Integer score) {
        var transfer = transfers.findById(transferId).orElseThrow();
        if (transfer.isTerminal()) {
            return;
        }
        WalletApi.PostingReceipt receipt;
        try {
            receipt = wallets.post(new Posting("transfer:" + transferId, transfer.initiatorId(),
                    transfer.recipientId(), transfer.amount(), Posting.Kind.TRANSFER));
        } catch (DomainException.RuleViolation | DomainException.NotFound businessFailure) {
            // Funds spent elsewhere since the request, wallet frozen, etc. A final outcome, not a retry.
            fail(transferId, businessFailure.code());
            return;
        }
        tx.executeWithoutResult(s -> {
            var t = transfers.findById(transferId).orElseThrow();
            if (t.isTerminal()) {
                return;
            }
            t.complete(receipt.journalId(), score);
            events.publishEvent(new TransferCompleted(t.id(), t.initiatorId(), t.recipientId(),
                    t.amount().minor(), t.amount().currencyCode(), t.note(),
                    t.riskScore() == null ? 0 : t.riskScore(), Instant.now()));
        });
        metrics.counter("kibetipay.transfers", "outcome", "completed").increment();
    }

    void reject(UUID transferId, Integer score, String reason) {
        tx.executeWithoutResult(s -> {
            var t = transfers.findById(transferId).orElseThrow();
            if (t.isTerminal()) {
                return;
            }
            t.reject(score, reason);
            events.publishEvent(new TransferRejected(t.id(), t.initiatorId(), reason, Instant.now()));
        });
        metrics.counter("kibetipay.transfers", "outcome", "rejected").increment();
    }

    private void holdForReview(UUID transferId, int score) {
        tx.executeWithoutResult(s -> {
            var t = transfers.findById(transferId).orElseThrow();
            t.holdForReview(score);
            events.publishEvent(new TransferHeldForReview(t.id(), t.initiatorId(), score, Instant.now()));
        });
        metrics.counter("kibetipay.transfers", "outcome", "review").increment();
    }

    private void fail(UUID transferId, String reason) {
        tx.executeWithoutResult(s -> transfers.findById(transferId).ifPresent(t -> {
            if (!t.isTerminal()) {
                t.fail(reason);
            }
        }));
        metrics.counter("kibetipay.transfers", "outcome", "failed").increment();
    }
}

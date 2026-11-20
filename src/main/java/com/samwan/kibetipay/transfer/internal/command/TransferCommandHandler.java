package com.samwan.kibetipay.transfer.internal.command;

import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.transfer.TransferEvents.TransferRequested;
import com.samwan.kibetipay.wallet.WalletApi;
import io.micrometer.observation.annotation.Observed;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Write side (the C in CQRS). Accepts commands, validates them, persists the aggregate and
 * publishes an event in the same transaction. Spring Modulith stores the event in the
 * event_publication table (transactional outbox) so it is never lost, even on a crash.
 */
@Service
public class TransferCommandHandler {

    public record InitiateTransfer(String initiatorId, String recipientId, Money amount, String note,
                                   String idempotencyKey) {}

    public record Accepted(UUID transferId, String txnRef, boolean replayed) {}

    /** A fresh txn_ref colliding twice in a row means something other than bad luck is wrong. */
    private static final int MAX_INSERT_ATTEMPTS = 2;

    private final TransferRepository transfers;
    private final WalletApi wallets;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final TransferProperties props;
    private final TransferTelemetry telemetry;

    TransferCommandHandler(TransferRepository transfers, WalletApi wallets, ApplicationEventPublisher events,
                           TransactionTemplate tx, TransferProperties props, TransferTelemetry telemetry) {
        this.transfers = transfers;
        this.wallets = wallets;
        this.events = events;
        this.tx = tx;
        this.props = props;
        this.telemetry = telemetry;
    }

    @Observed(name = "transfer.initiate")
    public Accepted initiate(InitiateTransfer cmd) {
        // Fast path: client retried with the same Idempotency-Key.
        var existing = transfers.findByInitiatorIdAndIdempotencyKey(cmd.initiatorId(), cmd.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), cmd);
        }
        try {
            validate(cmd);
        } catch (DomainException rejected) {
            telemetry.requestRejected(rejected.code());
            throw rejected;
        }
        for (int attempt = 1; ; attempt++) {
            try {
                var transfer = tx.execute(status -> {
                    var t = Transfer.initiate(cmd.initiatorId(), cmd.recipientId(), cmd.amount(), cmd.note(),
                            cmd.idempotencyKey());
                    transfers.saveAndFlush(t);
                    events.publishEvent(new TransferRequested(t.id()));
                    return t;
                });
                telemetry.requested(transfer);
                return new Accepted(transfer.id(), transfer.txnRef(), false);
            } catch (DataIntegrityViolationException conflict) {
                // Usually two identical requests raced and the idempotency constraint picked a winner.
                var winner = transfers.findByInitiatorIdAndIdempotencyKey(cmd.initiatorId(), cmd.idempotencyKey());
                if (winner.isPresent()) {
                    return replay(winner.get(), cmd);
                }
                // Otherwise the random txn_ref collided (~1 in 10^15 per insert); retry with a new one.
                if (attempt >= MAX_INSERT_ATTEMPTS) {
                    throw conflict;
                }
            }
        }
    }

    private void validate(InitiateTransfer cmd) {
        if (cmd.initiatorId().equals(cmd.recipientId())) {
            throw new DomainException.RuleViolation("self_transfer", "You cannot send money to yourself");
        }
        if (!cmd.amount().isPositive() || cmd.amount().minor() > props.maxAmountMinor()) {
            throw new DomainException.RuleViolation("amount_out_of_range",
                    "Amount must be between 0 and " + Money.of(props.maxAmountMinor(), cmd.amount().currencyCode()));
        }
        // Fail fast on obvious problems; the ledger re-checks everything under lock.
        if (!wallets.exists(cmd.recipientId(), cmd.amount().currencyCode())) {
            throw new WalletApi.WalletNotFoundException(cmd.recipientId(), cmd.amount().currencyCode());
        }
        var balance = wallets.get(cmd.initiatorId(), cmd.amount().currencyCode()).balance();
        if (balance.compareTo(cmd.amount()) < 0) {
            throw new WalletApi.InsufficientFundsException(cmd.initiatorId(), cmd.amount());
        }
    }

    /** Same key + same payload = replay. Same key + different payload = client bug, reject loudly. */
    private Accepted replay(Transfer t, InitiateTransfer cmd) {
        boolean samePayload = t.recipientId().equals(cmd.recipientId())
                && t.amount().equals(cmd.amount())
                && Objects.equals(t.note(), cmd.note());
        if (!samePayload) {
            telemetry.idempotencyConflict(t);
            throw new DomainException.RuleViolation("idempotency_key_reused",
                    "Idempotency-Key was already used for a different transfer");
        }
        telemetry.replayed(t);
        return new Accepted(t.id(), t.txnRef(), true);
    }
}

package com.samwan.kibetipay.wallet;

import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.shared.error.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The only door into the wallet module. Other modules depend on this interface and the
 * records below; entities, repositories and the ledger engine stay internal.
 *
 * <p>Posting operations write balanced double-entry journals atomically. A reference identifies
 * one posting: an identical retry returns its original result, while reuse with different
 * journal details is rejected. Concurrent retries may receive a conflict and be retried.
 */
public interface WalletApi {

    /** Platform currency (single-currency for now; multi-currency + FX is a later phase). */
    String defaultCurrency();

    WalletView open(String ownerId, String currency);

    WalletView get(String ownerId, String currency);

    boolean exists(String ownerId, String currency);

    /** Money enters the platform (e.g. M-Pesa C2B callback). Credits the user, debits the float account. */
    PostingReceipt deposit(String ownerId, Money amount, String reference);

    /** Move money between two users' wallets. */
    PostingReceipt post(Posting posting);

    /**
     * General N-leg journal (e.g. a loan repayment that credits the pool, interest income and
     * fee income at once). Debits must equal credits; same idempotency-by-reference guarantee.
     */
    PostingReceipt postJournal(JournalRequest request);

    record Leg(String ownerId, Side side, Money amount) {
        public enum Side { DEBIT, CREDIT }

        public static Leg debit(String ownerId, Money amount) {
            return new Leg(ownerId, Side.DEBIT, amount);
        }

        public static Leg credit(String ownerId, Money amount) {
            return new Leg(ownerId, Side.CREDIT, amount);
        }
    }

    record JournalRequest(String reference, String kind, List<Leg> legs) {
        public JournalRequest {
            legs = List.copyOf(legs);
        }
    }

    /**
     * Well-known platform accounts (seeded by Flyway). DEBIT = money leaves the account,
     * CREDIT = money enters it, the same convention as user wallets.
     */
    final class SystemAccounts {
        public static final String FLOAT = "system:float";
        public static final String LENDING_POOL = "system:lending-pool";
        public static final String INTEREST_INCOME = "system:interest-income";
        public static final String FEE_INCOME = "system:fee-income";
        public static final String PENALTY_INCOME = "system:penalty-income";
        public static final String COMMISSION_EXPENSE = "system:commission-expense";

        private SystemAccounts() {
        }
    }

    List<StatementLine> statement(String ownerId, String currency, int limit);

    enum WalletStatus { ACTIVE, FROZEN }

    record WalletView(UUID id, String ownerId, Money balance, WalletStatus status, Instant createdAt) {}

    record Posting(String reference, String fromOwnerId, String toOwnerId, Money amount, Kind kind) {
        public enum Kind { TRANSFER, REVERSAL }
    }

    /** The balance is for the first debit owner after the entire journal, including on replay. */
    record PostingReceipt(UUID journalId, String reference, Money fromBalanceAfter, boolean replayed) {}

    record StatementLine(UUID journalId, String reference, Leg.Side direction, Money amount,
                         Money balanceAfter, Instant at) {}

    /** 422: debit would take a user wallet below zero. */
    final class InsufficientFundsException extends DomainException.RuleViolation {
        public InsufficientFundsException(String ownerId, Money requested) {
            super("insufficient_funds", "Wallet of " + ownerId + " cannot cover " + requested);
        }
    }

    final class WalletNotFoundException extends DomainException.NotFound {
        public WalletNotFoundException(String ownerId, String currency) {
            super("wallet_not_found", "No " + currency + " wallet for " + ownerId);
        }
    }
}

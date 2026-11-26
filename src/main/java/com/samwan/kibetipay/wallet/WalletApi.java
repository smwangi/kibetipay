package com.samwan.kibetipay.wallet;

import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.paging.Page;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
        public enum Side {
            DEBIT,
            CREDIT
        }

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
        /**
         * Money the platform has received but cannot yet attribute to a customer (e.g. a provider
         * collection with no matching expected payment). Never negative; should trend to zero.
         */
        public static final String SUSPENSE = "system:suspense";

        private SystemAccounts() {
        }
    }

    /** The wallet's ledger entries, newest first, one keyset page at a time. */
    Page<StatementLine> statement(String ownerId, String currency, Page.Query query);

    /**
     * Totals and a daily balance series for {@code days} calendar days ending today (in
     * {@code zone}), computed from the ledger, so every kind of movement is included.
     */
    WalletSummary summary(String ownerId, String currency, int days, ZoneId zone);

    enum WalletStatus {
        ACTIVE,
        FROZEN
    }

    record WalletView(UUID id, String ownerId, Money balance, WalletStatus status, Instant createdAt) {}

    record Posting(String reference, String fromOwnerId, String toOwnerId, Money amount, Kind kind) {
        public enum Kind {
            TRANSFER,
            REVERSAL
        }
    }

    /** The balance is for the first debit owner after the entire journal, including on replay. */
    record PostingReceipt(UUID journalId, String reference, Money fromBalanceAfter, boolean replayed) {}

    /**
     * One ledger entry on the wallet. {@code kind} is the journal kind (DEPOSIT, TRANSFER,
     * LOAN_DISBURSEMENT, ...). {@code counterparty} is the other wallet's owner for person-to-person
     * movements (TRANSFER, REVERSAL), otherwise {@code null}: system accounts are not named.
     */
    record StatementLine(UUID entryId, UUID journalId, String reference, String kind, Leg.Side direction,
                         Money amount, Money balanceAfter, String counterparty, Instant at) {}

    record WalletSummary(LocalDate from, LocalDate to, Money openingBalance, Money closingBalance,
                         Money totalIn, Money totalOut, List<KindTotal> byKind, List<DailyBalance> daily) {}

    /** Money in and out of the wallet for one journal kind over the summary period. */
    record KindTotal(String kind, Money in, Money out, long count) {}

    /** {@code closingBalance} is carried forward over days without movements. */
    record DailyBalance(LocalDate date, Money in, Money out, Money closingBalance) {}

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

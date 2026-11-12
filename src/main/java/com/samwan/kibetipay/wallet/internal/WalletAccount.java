package com.samwan.kibetipay.wallet.internal;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.samwan.kibetipay.shared.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root. The balance is a cached projection of the ledger entries, updated in
 * the same transaction as the entries, so it is always consistent with them.
 */
@Entity
@Table(name = "wallet_account")
class WalletAccount {

    enum Type { USER, SYSTEM }

    enum Status { ACTIVE, FROZEN }

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private String ownerId;

    @JdbcTypeCode(SqlTypes.CHAR)   // column is CHAR(3) in Flyway; Hibernate defaults to VARCHAR
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    /** Float and expense accounts may go negative; user wallets and the lending pool may not. */
    @Column(name = "allow_negative", nullable = false, updatable = false)
    private boolean allowNegative;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WalletAccount() {
        // JPA
    }

    static WalletAccount openUser(String ownerId, String currency) {
        var w = new WalletAccount();
        w.id = UUID.randomUUID();
        w.ownerId = ownerId;
        w.currency = currency;
        w.type = Type.USER;
        w.status = Status.ACTIVE;
        w.balanceMinor = 0;
        w.createdAt = Instant.now();
        return w;
    }

    /** Domain rule lives here, not in the service: only allow-negative accounts can go below zero. */
    long debit(Money amount) {
        requireUsable(amount);
        long next = Math.subtractExact(balanceMinor, amount.minor());
        if (!allowNegative && next < 0) {
            throw new com.samwan.kibetipay.wallet.WalletApi.InsufficientFundsException(ownerId, amount);
        }
        balanceMinor = next;
        return balanceMinor;
    }

    long credit(Money amount) {
        requireUsable(amount);
        balanceMinor = Math.addExact(balanceMinor, amount.minor());
        return balanceMinor;
    }

    private void requireUsable(Money amount) {
        if (status != Status.ACTIVE) {
            throw new com.samwan.kibetipay.shared.error.DomainException.RuleViolation(
                    "wallet_frozen", "Wallet " + id + " is frozen");
        }
        if (!amount.currencyCode().equals(currency)) {
            throw new com.samwan.kibetipay.shared.error.DomainException.RuleViolation(
                    "currency_mismatch", "Wallet currency is " + currency);
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    UUID id() { return id; }
    String ownerId() { return ownerId; }
    String currency() { return currency; }
    Status status() { return status; }
    Instant createdAt() { return createdAt; }
    Money balance() { return Money.of(balanceMinor, currency); }
}

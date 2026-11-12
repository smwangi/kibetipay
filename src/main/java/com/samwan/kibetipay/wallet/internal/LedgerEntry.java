package com.samwan.kibetipay.wallet.internal;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.samwan.kibetipay.shared.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Append-only. Ledger entries are never updated or deleted; corrections are new reversal journals. */
@Entity
@Immutable
@Table(name = "wallet_ledger_entry")
class LedgerEntry {

    enum Direction { DEBIT, CREDIT }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_id")
    private Journal journal;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)   // column is CHAR(3) in Flyway; Hibernate defaults to VARCHAR
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    LedgerEntry(Journal journal, UUID walletId, Direction direction, Money amount, long balanceAfter) {
        this.id = UUID.randomUUID();
        this.journal = journal;
        this.walletId = walletId;
        this.direction = direction;
        this.amountMinor = amount.minor();
        this.currency = amount.currencyCode();
        this.balanceAfter = balanceAfter;
        this.createdAt = Instant.now();
    }

    Direction direction() { return direction; }
    long amountMinor() { return amountMinor; }
    UUID walletId() { return walletId; }
    long balanceAfter() { return balanceAfter; }
}

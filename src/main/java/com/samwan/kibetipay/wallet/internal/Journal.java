package com.samwan.kibetipay.wallet.internal;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One business event in the ledger (a deposit, a transfer). Owns its entries and refuses to
 * exist unless debits equal credits: the core double-entry invariant.
 */
@Entity
@Table(name = "wallet_journal")
class Journal {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String reference;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "journal", cascade = CascadeType.PERSIST)
    private List<LedgerEntry> entries = new ArrayList<>();

    protected Journal() {
    }

    Journal(String reference, String kind) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.kind = kind;
        this.createdAt = Instant.now();
    }

    void debit(WalletAccount wallet, com.samwan.kibetipay.shared.money.Money amount) {
        long after = wallet.debit(amount);
        entries.add(new LedgerEntry(this, wallet.id(), LedgerEntry.Direction.DEBIT, amount, after));
    }

    void credit(WalletAccount wallet, com.samwan.kibetipay.shared.money.Money amount) {
        long after = wallet.credit(amount);
        entries.add(new LedgerEntry(this, wallet.id(), LedgerEntry.Direction.CREDIT, amount, after));
    }

    /** Called before persisting. A programming error, not a user error, if it fails. */
    void assertBalanced() {
        long debits = entries.stream().filter(e -> e.direction() == LedgerEntry.Direction.DEBIT)
                .mapToLong(LedgerEntry::amountMinor).sum();
        long credits = entries.stream().filter(e -> e.direction() == LedgerEntry.Direction.CREDIT)
                .mapToLong(LedgerEntry::amountMinor).sum();
        if (entries.size() < 2 || debits != credits) {
            throw new IllegalStateException("Unbalanced journal " + reference
                    + ": debits=" + debits + " credits=" + credits);
        }
    }

    UUID id() { return id; }
    String reference() { return reference; }
    String kind() { return kind; }
    List<LedgerEntry> entries() { return List.copyOf(entries); }
}

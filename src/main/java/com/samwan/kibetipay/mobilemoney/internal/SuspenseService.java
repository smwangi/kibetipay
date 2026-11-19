package com.samwan.kibetipay.mobilemoney.internal;

import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.id.Ids;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.wallet.WalletApi;
import com.samwan.kibetipay.wallet.WalletApi.JournalRequest;
import com.samwan.kibetipay.wallet.WalletApi.Leg;
import com.samwan.kibetipay.wallet.WalletApi.SystemAccounts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Suspense for provider collections that cannot be attributed to a customer.
 *
 * <p>Opening an item books {@code Dr system:float / Cr system:suspense}: the money is in the
 * platform's provider account, so the float must reflect it, but no customer owns it yet.
 * Allocating clears it with {@code Dr system:suspense / Cr customer wallet}. Both postings use
 * deterministic references, so retries never move money twice.
 */
@Service
class SuspenseService {
    static final String KIND_OPEN = "SUSPENSE_OPEN";
    static final String KIND_ALLOCATE = "SUSPENSE_ALLOCATION";

    enum Status {
        OPEN,
        ALLOCATED
    }

    record Item(UUID id, UUID providerTransactionId, String provider, String providerReference,
                long amountMinor, String currency, Status status, String reason,
                UUID suspenseJournalId, Instant openedAt, String openedBy,
                String allocatedTo, String allocationNote, UUID allocationJournalId,
                Instant allocatedAt, String allocatedBy, boolean replayed) {
        Item replay() {
            return new Item(id, providerTransactionId, provider, providerReference, amountMinor, currency, status,
                    reason, suspenseJournalId, openedAt, openedBy, allocatedTo, allocationNote, allocationJournalId,
                    allocatedAt, allocatedBy, true);
        }
    }

    private record ProviderTransaction(UUID id, String provider, String providerReference, String expectedReference,
                                       String direction, long amountMinor, String currency) {}

    private final JdbcClient jdbc;
    private final WalletApi wallets;
    private final TransactionTemplate tx;
    private final Clock clock;

    SuspenseService(JdbcClient jdbc, WalletApi wallets, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.wallets = wallets;
        this.tx = tx;
        this.clock = clock;
    }

    /** Books an unmatched provider collection into suspense. Replays return the existing item. */
    Item open(UUID providerTransactionId, String reason, String actor) {
        return tx.execute(s -> {
            // The row lock serializes concurrent attempts for the same statement line.
            ProviderTransaction p = jdbc.sql("""
                    SELECT id, provider, provider_reference, expected_reference, direction, amount_minor, currency
                    FROM mobilemoney_provider_transaction WHERE id = :id FOR UPDATE
                    """).param("id", providerTransactionId)
                    .query((r, n) -> new ProviderTransaction(r.getObject("id", UUID.class), r.getString("provider"),
                            r.getString("provider_reference"), r.getString("expected_reference"), r.getString("direction"),
                            r.getLong("amount_minor"), r.getString("currency").trim()))
                    .optional().orElseThrow(() -> new DomainException.NotFound("provider_transaction_not_found",
                            "No provider transaction " + providerTransactionId));

            var existing = findByProviderTransaction(p.id());
            if (existing != null) {
                return existing.replay();
            }
            if (!"COLLECTION".equals(p.direction())) {
                throw new DomainException.RuleViolation("suspense_collection_only",
                        "Only inbound collections can be booked into suspense");
            }
            boolean matched = p.expectedReference() != null && jdbc.sql("""
                    SELECT count(*) FROM mobilemoney_expected_payment WHERE provider = :provider AND reference = :reference
                    """).param("provider", p.provider()).param("reference", p.expectedReference())
                    .query(Long.class).single() > 0;
            if (matched) {
                throw new DomainException.RuleViolation("provider_transaction_matched",
                        "The transaction matches an expected payment; resolve it through that payment, not suspense");
            }
            boolean credited = jdbc.sql("""
                    SELECT count(*) FROM mobilemoney_collection
                    WHERE provider = :provider AND provider_receipt = :receipt AND status = 'COMPLETED'
                    """).param("provider", p.provider()).param("receipt", p.providerReference())
                    .query(Long.class).single() > 0;
            if (credited) {
                throw new DomainException.Conflict("already_credited",
                        "A completed collection already credited this provider receipt");
            }

            Money amount = Money.of(p.amountMinor(), p.currency());
            UUID journalId = wallets.postJournal(new JournalRequest("mobilemoney:suspense:open:" + p.id(), KIND_OPEN,
                    List.of(Leg.debit(SystemAccounts.FLOAT, amount), Leg.credit(SystemAccounts.SUSPENSE, amount))))
                    .journalId();
            UUID id = Ids.newId();
            jdbc.sql("""
                    INSERT INTO mobilemoney_suspense_item (id, provider_transaction_id, amount_minor, currency, status,
                      reason, suspense_journal_id, opened_at, opened_by)
                    VALUES (:id, :tx, :amount, :currency, 'OPEN', :reason, :journal, :now, :actor)
                    """).param("id", id).param("tx", p.id()).param("amount", p.amountMinor())
                    .param("currency", p.currency()).param("reason", reason).param("journal", journalId)
                    .param("now", Timestamp.from(Instant.now(clock))).param("actor", actor).update();
            return byId(id, false);
        });
    }

    /** Clears an open item to the identified customer's wallet. Replays to the same owner are safe. */
    Item allocate(UUID itemId, String ownerId, String note, String actor) {
        if (ownerId.startsWith("system:")) {
            throw new DomainException.RuleViolation("suspense_allocation_target",
                    "Suspense can only be allocated to a customer wallet");
        }
        return tx.execute(s -> {
            Item item = byId(itemId, true);
            if (item.status() == Status.ALLOCATED) {
                if (item.allocatedTo().equals(ownerId)) {
                    return item.replay();
                }
                throw new DomainException.Conflict("suspense_already_allocated",
                        "Suspense item was already allocated to another wallet");
            }
            if (!wallets.exists(ownerId, item.currency())) {
                throw new WalletApi.WalletNotFoundException(ownerId, item.currency());
            }
            Money amount = Money.of(item.amountMinor(), item.currency());
            UUID journalId = wallets.postJournal(new JournalRequest("mobilemoney:suspense:allocate:" + itemId, KIND_ALLOCATE,
                    List.of(Leg.debit(SystemAccounts.SUSPENSE, amount), Leg.credit(ownerId, amount))))
                    .journalId();
            jdbc.sql("""
                    UPDATE mobilemoney_suspense_item SET status = 'ALLOCATED', allocated_to = :owner,
                      allocation_note = :note, allocation_journal_id = :journal, allocated_at = :now, allocated_by = :actor
                    WHERE id = :id AND status = 'OPEN'
                    """).param("owner", ownerId).param("note", note).param("journal", journalId)
                    .param("now", Timestamp.from(Instant.now(clock))).param("actor", actor).param("id", itemId).update();
            return byId(itemId, false);
        });
    }

    /** Oldest first, so the items that most need attention come first. */
    List<Item> list(Status status) {
        String where = status == null ? "" : " WHERE s.status = :status";
        var query = jdbc.sql(SELECT + where + " ORDER BY s.opened_at LIMIT 500");
        if (status != null) {
            query = query.param("status", status.name());
        }
        return query.query(SuspenseService::map).list();
    }

    private Item findByProviderTransaction(UUID providerTransactionId) {
        return jdbc.sql(SELECT + " WHERE s.provider_transaction_id = :tx").param("tx", providerTransactionId)
                .query(SuspenseService::map).optional().orElse(null);
    }

    private Item byId(UUID id, boolean lock) {
        return jdbc.sql(SELECT + " WHERE s.id = :id" + (lock ? " FOR UPDATE OF s" : "")).param("id", id)
                .query(SuspenseService::map).optional()
                .orElseThrow(() -> new DomainException.NotFound("suspense_item_not_found", "No suspense item " + id));
    }

    private static final String SELECT = """
            SELECT s.*, p.provider, p.provider_reference
            FROM mobilemoney_suspense_item s JOIN mobilemoney_provider_transaction p ON p.id = s.provider_transaction_id
            """;

    private static Item map(ResultSet r, int n) throws SQLException {
        Timestamp allocatedAt = r.getTimestamp("allocated_at");
        return new Item(r.getObject("id", UUID.class), r.getObject("provider_transaction_id", UUID.class),
                r.getString("provider"), r.getString("provider_reference"), r.getLong("amount_minor"),
                r.getString("currency").trim(), Status.valueOf(r.getString("status")), r.getString("reason"),
                r.getObject("suspense_journal_id", UUID.class), r.getTimestamp("opened_at").toInstant(),
                r.getString("opened_by"), r.getString("allocated_to"), r.getString("allocation_note"),
                r.getObject("allocation_journal_id", UUID.class), allocatedAt == null ? null : allocatedAt.toInstant(),
                r.getString("allocated_by"), false);
    }
}

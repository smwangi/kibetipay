package com.samwan.kibetipay.wallet.internal;

import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.wallet.WalletApi;
import io.micrometer.observation.annotation.Observed;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ledger engine. Write side uses JPA aggregates + pessimistic locks; the statement read side
 * uses plain SQL (JdbcClient): a small CQRS split inside one module.
 */
@Service
class LedgerService implements WalletApi {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final WalletAccountRepository wallets;
    private final JournalRepository journals;
    private final JdbcClient jdbc;
    private final LedgerProperties props;

    LedgerService(WalletAccountRepository wallets, JournalRepository journals, JdbcClient jdbc,
                  LedgerProperties props) {
        this.wallets = wallets;
        this.journals = journals;
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public String defaultCurrency() {
        return props.defaultCurrency();
    }

    @Override
    @Transactional
    public WalletView open(String ownerId, String currency) {
        if (wallets.existsByOwnerIdAndCurrency(ownerId, currency)) {
            throw new DomainException.Conflict("wallet_exists", ownerId + " already has a " + currency + " wallet");
        }
        return toView(wallets.save(WalletAccount.openUser(ownerId, currency)));
    }

    @Override
    @Transactional(readOnly = true)
    public WalletView get(String ownerId, String currency) {
        return wallets.findByOwnerIdAndCurrency(ownerId, currency)
                .map(LedgerService::toView)
                .orElseThrow(() -> new WalletNotFoundException(ownerId, currency));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(String ownerId, String currency) {
        return wallets.existsByOwnerIdAndCurrency(ownerId, currency);
    }

    @Override
    @Observed(name = "ledger.deposit")
    @Transactional
    public PostingReceipt deposit(String ownerId, Money amount, String reference) {
        return postInternal(reference, "DEPOSIT", props.floatOwnerId(), ownerId, amount);
    }

    @Override
    @Observed(name = "ledger.post")
    @Transactional
    public PostingReceipt post(Posting posting) {
        if (posting.fromOwnerId().equals(posting.toOwnerId())) {
            throw new DomainException.RuleViolation("self_transfer", "Cannot transfer to the same wallet");
        }
        return postInternal(posting.reference(), posting.kind().name(),
                posting.fromOwnerId(), posting.toOwnerId(), posting.amount());
    }

    private PostingReceipt postInternal(String reference, String kind, String from, String to, Money amount) {
        return postJournal(new JournalRequest(reference, kind, List.of(Leg.debit(from, amount), Leg.credit(to, amount))));
    }

    @Override
    @Observed(name = "ledger.journal")
    @Transactional
    public PostingReceipt postJournal(JournalRequest request) {
        var legs = request.legs();
        if (legs.size() < 2) {
            throw new IllegalArgumentException("A journal needs at least two legs");
        }
        String currency = legs.getFirst().amount().currencyCode();
        if (legs.stream().anyMatch(l -> l.amount().isNegative()
                || !currency.equals(l.amount().currencyCode()))) {
            throw new IllegalArgumentException("Journal amounts must be non-negative and use one currency");
        }
        String firstDebitOwner = legs.stream().filter(l -> l.side() == Leg.Side.DEBIT && l.amount().isPositive())
                .map(Leg::ownerId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("A journal needs a debit leg"));

        // 1. Idempotency: a replayed event or client retry must not move money twice.
        var existing = journals.findByReference(request.reference());
        if (existing.isPresent()) {
            log.info("Replay of posting {} ignored", request.reference());
            return replayReceipt(existing.get(), request, firstDebitOwner, currency);
        }

        // 2. Lock every wallet involved in id order (deadlock-free), then apply the domain rules.
        var owners = legs.stream().map(Leg::ownerId).distinct().toList();
        Map<String, WalletAccount> locked = wallets.lockAllByOwners(owners, currency)
                .stream().collect(Collectors.toMap(WalletAccount::ownerId, Function.identity()));

        // 3. Build a balanced journal; entity methods enforce no-overdraft + frozen checks.
        var journal = new Journal(request.reference(), request.kind());
        for (Leg leg : legs) {
            if (!leg.amount().isPositive()) {
                continue;   // zero legs (e.g. no penalty due) are simply skipped
            }
            WalletAccount account = require(locked, leg.ownerId(), currency);
            if (leg.side() == Leg.Side.DEBIT) {
                journal.debit(account, leg.amount());
            } else {
                journal.credit(account, leg.amount());
            }
        }
        journal.assertBalanced();

        try {
            journals.saveAndFlush(journal);   // unique(reference) guards concurrent duplicates
        } catch (DataIntegrityViolationException race) {
            throw new DomainException.Conflict("duplicate_posting", "Posting " + request.reference() + " already exists");
        }
        return new PostingReceipt(journal.id(), request.reference(), locked.get(firstDebitOwner).balance(), false);
    }

    private record SavedLeg(String owner, String direction, long amount, String currency) {}

    private PostingReceipt replayReceipt(Journal journal, JournalRequest request, String fromOwner, String currency) {
        var saved = jdbc.sql("""
                SELECT w.owner_id, e.direction, e.amount_minor, e.currency
                FROM wallet_ledger_entry e JOIN wallet_account w ON w.id = e.wallet_id
                WHERE e.journal_id = :journalId
                """)
                .param("journalId", journal.id())
                .query((rs, i) -> new SavedLeg(rs.getString("owner_id"), rs.getString("direction"),
                        rs.getLong("amount_minor"), rs.getString("currency").trim()))
                .list();
        var requested = request.legs().stream().filter(l -> l.amount().isPositive())
                .map(l -> new SavedLeg(l.ownerId(), l.side().name(), l.amount().minor(), l.amount().currencyCode()))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        var recorded = saved.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        if (!journal.kind().equals(request.kind()) || !recorded.equals(requested)) {
            throw new DomainException.Conflict("duplicate_posting", "Posting " + request.reference() + " has different details");
        }
        long balance = jdbc.sql("""
                SELECT e.balance_after FROM wallet_ledger_entry e
                JOIN wallet_account w ON w.id = e.wallet_id
                WHERE e.journal_id = :journalId AND w.owner_id = :owner AND e.currency = :currency
                ORDER BY e.created_at DESC LIMIT 1
                """)
                .param("journalId", journal.id()).param("owner", fromOwner).param("currency", currency)
                .query(Long.class).single();
        return new PostingReceipt(journal.id(), journal.reference(), Money.of(balance, currency), true);
    }

    private static WalletAccount require(Map<String, WalletAccount> locked, String owner, String currency) {
        var w = locked.get(owner);
        if (w == null) {
            throw new WalletNotFoundException(owner, currency);
        }
        return w;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StatementLine> statement(String ownerId, String currency, int limit) {
        var wallet = get(ownerId, currency);
        return jdbc.sql("""
                        SELECT j.id AS journal_id, j.reference, e.direction, e.amount_minor,
                               e.balance_after, e.currency, e.created_at
                        FROM wallet_ledger_entry e
                        JOIN wallet_journal j ON j.id = e.journal_id
                        WHERE e.wallet_id = :walletId
                        ORDER BY e.created_at DESC
                        LIMIT :limit
                        """)
                .param("walletId", wallet.id())
                .param("limit", Math.min(Math.max(limit, 1), 200))
                .query((rs, i) -> new StatementLine(
                        rs.getObject("journal_id", java.util.UUID.class),
                        rs.getString("reference"),
                        Leg.Side.valueOf(rs.getString("direction")),
                        Money.of(rs.getLong("amount_minor"), rs.getString("currency")),
                        Money.of(rs.getLong("balance_after"), rs.getString("currency")),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private static WalletView toView(WalletAccount w) {
        return new WalletView(w.id(), w.ownerId(), w.balance(), WalletStatus.valueOf(w.status().name()), w.createdAt());
    }
}

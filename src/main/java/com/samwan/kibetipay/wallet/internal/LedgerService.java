package com.samwan.kibetipay.wallet.internal;

import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.wallet.WalletApi;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import com.samwan.kibetipay.shared.paging.Cursor;
import com.samwan.kibetipay.shared.paging.Page;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
    /** Advisory-lock namespace ("KP") so ledger reference locks never collide with other users of advisory locks. */
    private static final int REFERENCE_LOCK_NAMESPACE = 0x4B50;

    private final WalletAccountRepository wallets;
    private final JournalRepository journals;
    private final JdbcClient jdbc;
    private final LedgerProperties props;
    private final MeterRegistry metrics;

    LedgerService(WalletAccountRepository wallets, JournalRepository journals, JdbcClient jdbc,
                  LedgerProperties props, MeterRegistry metrics) {
        this.wallets = wallets;
        this.journals = journals;
        this.jdbc = jdbc;
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    public String defaultCurrency() {
        return props.defaultCurrency();
    }

    @Override
    @Transactional
    public WalletView open(String ownerId, String currency) {
        requirePlatformCurrency(currency);
        if (wallets.existsByOwnerIdAndCurrency(ownerId, currency)) {
            throw new DomainException.Conflict("wallet_exists", ownerId + " already has a " + currency + " wallet");
        }
        return toView(wallets.save(WalletAccount.openUser(ownerId, currency)));
    }

    @Override
    @Transactional(readOnly = true)
    public WalletView get(String ownerId, String currency) {
        requirePlatformCurrency(currency);
        return wallets.findByOwnerIdAndCurrency(ownerId, currency)
                .map(LedgerService::toView)
                .orElseThrow(() -> new WalletNotFoundException(ownerId, currency));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(String ownerId, String currency) {
        requirePlatformCurrency(currency);
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
        requirePlatformCurrency(currency);
        if (legs.stream().anyMatch(l -> l.amount().isNegative()
                || !currency.equals(l.amount().currencyCode()))) {
            throw new IllegalArgumentException("Journal amounts must be non-negative and use one currency");
        }
        String firstDebitOwner = legs.stream().filter(l -> l.side() == Leg.Side.DEBIT && l.amount().isPositive())
                .map(Leg::ownerId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("A journal needs a debit leg"));

        // 1. Idempotency: a replayed event or client retry must not move money twice.
        //    Concurrent postings of the same reference queue on a transaction-scoped advisory lock,
        //    so the losers see the winner's committed journal and get a replay instead of colliding
        //    on the unique constraint. Different references never contend (barring a hash clash,
        //    which only serialises two postings briefly). Taken before the wallet locks, always.
        lockReference(request.reference());
        var existing = journals.findByReference(request.reference());
        if (existing.isPresent()) {
            // Expected after an outbox republish or client retry; money must not move twice.
            log.atInfo()
                    .addKeyValue("event", "ledger.posting.replayed")
                    .addKeyValue("reference", request.reference())
                    .addKeyValue("kind", request.kind())
                    .log("Replay of posting {} ignored", request.reference());
            metrics.counter("kibetipay.idempotency.replays", "scope", "ledger").increment();
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
            journals.saveAndFlush(journal);   // unique(reference): last line of defence behind the reference lock
        } catch (DataIntegrityViolationException race) {
            log.atWarn()
                    .addKeyValue("event", "ledger.posting.duplicate")
                    .addKeyValue("reference", request.reference())
                    .addKeyValue("kind", request.kind())
                    .log("Concurrent duplicate posting {} rejected by unique constraint", request.reference());
            metrics.counter("kibetipay.ledger.posting.failures", "kind", request.kind(),
                    "reason", "duplicate_posting").increment();
            throw new DomainException.Conflict("duplicate_posting", "Posting " + request.reference() + " already exists");
        }
        return new PostingReceipt(journal.id(), request.reference(), locked.get(firstDebitOwner).balance(), false);
    }

    private void lockReference(String reference) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:ns, hashtext(:ref))")
                .param("ns", REFERENCE_LOCK_NAMESPACE).param("ref", reference)
                .query((rs, n) -> Boolean.TRUE).single();
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

    private void requirePlatformCurrency(String currency) {
        if (!props.defaultCurrency().equals(currency)) {
            throw new DomainException.RuleViolation("currency_mismatch", "Currency must match the configured platform currency");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StatementLine> statement(String ownerId, String currency, Page.Query q) {
        var wallet = get(ownerId, currency);
        // Counterparty: the other wallet in a person-to-person journal. Loan, deposit and fee
        // journals touch system accounts, which are not shown to customers.
        var sql = new StringBuilder("""
                SELECT e.id AS entry_id, j.id AS journal_id, j.reference, j.kind, e.direction, e.amount_minor,
                       e.balance_after, e.currency, e.created_at,
                       CASE WHEN j.kind IN ('TRANSFER', 'REVERSAL') THEN (
                           SELECT a.owner_id FROM wallet_ledger_entry o
                           JOIN wallet_account a ON a.id = o.wallet_id
                           WHERE o.journal_id = j.id AND o.wallet_id <> e.wallet_id
                           LIMIT 1) END AS counterparty
                FROM wallet_ledger_entry e
                JOIN wallet_journal j ON j.id = e.journal_id
                WHERE e.wallet_id = :walletId
                """);
        if (q.from() != null) {
            sql.append(" AND e.created_at >= :from");
        }
        if (q.toExclusive() != null) {
            sql.append(" AND e.created_at < :to");
        }
        if (q.before() != null) {
            sql.append(" AND (e.created_at, e.id) < (:beforeAt, :beforeId)");
        }
        sql.append(" ORDER BY e.created_at DESC, e.id DESC LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("walletId", wallet.id()).param("limit", q.limit() + 1);
        if (q.from() != null) {
            spec = spec.param("from", Timestamp.from(q.from()));
        }
        if (q.toExclusive() != null) {
            spec = spec.param("to", Timestamp.from(q.toExclusive()));
        }
        if (q.before() != null) {
            spec = spec.param("beforeAt", Timestamp.from(q.before().at())).param("beforeId", q.before().id());
        }
        var rows = spec.query((rs, i) -> new StatementLine(
                        rs.getObject("entry_id", UUID.class),
                        rs.getObject("journal_id", UUID.class),
                        rs.getString("reference"),
                        rs.getString("kind"),
                        Leg.Side.valueOf(rs.getString("direction")),
                        Money.of(rs.getLong("amount_minor"), rs.getString("currency")),
                        Money.of(rs.getLong("balance_after"), rs.getString("currency")),
                        rs.getString("counterparty"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        return Page.fromOverfetch(rows, q.limit(), line -> new Cursor(line.at(), line.entryId()));
    }

    @Override
    @Transactional(readOnly = true)
    public WalletSummary summary(String ownerId, String currency, int days, ZoneId zone) {
        var wallet = get(ownerId, currency);
        int span = Math.min(Math.max(days, 7), 366);
        LocalDate to = LocalDate.now(zone);
        LocalDate from = to.minusDays(span - 1L);
        var fromTs = Timestamp.from(from.atStartOfDay(zone).toInstant());
        var toTs = Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant());

        long opening = jdbc.sql("""
                        SELECT balance_after FROM wallet_ledger_entry
                        WHERE wallet_id = :walletId AND created_at < :from
                        ORDER BY created_at DESC, id DESC LIMIT 1
                        """)
                .param("walletId", wallet.id()).param("from", fromTs)
                .query(Long.class).optional().orElse(0L);

        List<KindTotal> byKind = jdbc.sql("""
                        SELECT j.kind,
                               COALESCE(SUM(e.amount_minor) FILTER (WHERE e.direction = 'CREDIT'), 0) AS in_minor,
                               COALESCE(SUM(e.amount_minor) FILTER (WHERE e.direction = 'DEBIT'), 0) AS out_minor,
                               COUNT(*) AS n
                        FROM wallet_ledger_entry e
                        JOIN wallet_journal j ON j.id = e.journal_id
                        WHERE e.wallet_id = :walletId AND e.created_at >= :from AND e.created_at < :to
                        GROUP BY j.kind
                        ORDER BY j.kind
                        """)
                .param("walletId", wallet.id()).param("from", fromTs).param("to", toTs)
                .query((rs, i) -> new KindTotal(rs.getString("kind"), Money.of(rs.getLong("in_minor"), currency),
                        Money.of(rs.getLong("out_minor"), currency), rs.getLong("n")))
                .list();

        record Day(LocalDate date, long in, long out, long closing) {}
        Map<LocalDate, Day> activeDays = jdbc.sql("""
                        WITH entries AS (
                            SELECT (created_at AT TIME ZONE :zone)::date AS d, direction, amount_minor,
                                   balance_after, created_at, id
                            FROM wallet_ledger_entry
                            WHERE wallet_id = :walletId AND created_at >= :from AND created_at < :to),
                        sums AS (
                            SELECT d,
                                   COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0) AS in_minor,
                                   COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0) AS out_minor
                            FROM entries GROUP BY d),
                        closing AS (
                            SELECT DISTINCT ON (d) d, balance_after FROM entries ORDER BY d, created_at DESC, id DESC)
                        SELECT s.d, s.in_minor, s.out_minor, c.balance_after
                        FROM sums s JOIN closing c USING (d)
                        """)
                .param("zone", zone.getId()).param("walletId", wallet.id()).param("from", fromTs).param("to", toTs)
                .query((rs, i) -> new Day(rs.getObject("d", LocalDate.class), rs.getLong("in_minor"),
                        rs.getLong("out_minor"), rs.getLong("balance_after")))
                .list().stream()
                .collect(Collectors.toMap(Day::date, Function.identity()));

        // One point per calendar day; quiet days carry the previous closing balance forward.
        List<DailyBalance> daily = new ArrayList<>(span);
        long balance = opening;
        long totalIn = 0;
        long totalOut = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Day day = activeDays.get(d);
            long in = day == null ? 0 : day.in();
            long out = day == null ? 0 : day.out();
            if (day != null) {
                balance = day.closing();
            }
            totalIn += in;
            totalOut += out;
            daily.add(new DailyBalance(d, Money.of(in, currency), Money.of(out, currency), Money.of(balance, currency)));
        }
        return new WalletSummary(from, to, Money.of(opening, currency), wallet.balance(),
                Money.of(totalIn, currency), Money.of(totalOut, currency), byKind, daily);
    }

    private static WalletView toView(WalletAccount w) {
        return new WalletView(w.id(), w.ownerId(), w.balance(), WalletStatus.valueOf(w.status().name()), w.createdAt());
    }
}

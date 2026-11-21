package com.samwan.kibetipay.wallet.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Independent check that the ledger is internally consistent. It recomputes everything from
 * the stored journals and entries instead of trusting the code that wrote them, so it catches
 * bugs the posting path cannot see in itself (a bad migration, a manual SQL fix, a missed lock).
 *
 * <ol>
 *   <li><b>unbalanced_journal</b>: every journal has at least two entries, all in one currency,
 *       and its debits equal its credits.</li>
 *   <li><b>balance_drift</b>: every account's stored balance equals the sum of its entries
 *       (credits minus debits, starting from zero).</li>
 *   <li><b>entry_currency</b>: every entry is in its account's currency.</li>
 *   <li><b>trial_balance</b>: all accounts in a currency sum to zero. Money only enters through
 *       journals, and every journal balances, so anything else means money was created or lost.</li>
 * </ol>
 *
 * Read-only. Each check is one SQL statement, so it sees a consistent snapshot even while
 * postings continue. It never repairs anything: a mismatch needs a person and a correcting journal.
 */
@Component
class LedgerReconciliation {

    private static final Logger log = LoggerFactory.getLogger("kibetipay.ledger.reconciliation");
    private static final int SAMPLE = 10;
    static final List<String> CHECKS = List.of("unbalanced_journal", "balance_drift", "entry_currency", "trial_balance");

    record Finding(String check, String subject, String detail) {}

    record Report(Instant checkedAt, Map<String, Long> mismatches, List<Finding> samples) {
        boolean clean() {
            return mismatches.values().stream().allMatch(n -> n == 0);
        }
    }

    private final JdbcClient jdbc;
    private final MeterRegistry metrics;
    private final Clock clock;
    /** -1 until the first run, exported as NaN so "never checked" is not mistaken for "clean". */
    private final Map<String, AtomicLong> gauges = new LinkedHashMap<>();
    private final AtomicLong lastRunEpochSeconds = new AtomicLong(-1);

    LedgerReconciliation(JdbcClient jdbc, MeterRegistry metrics, Clock clock) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.clock = clock;
        for (String check : CHECKS) {
            var value = new AtomicLong(-1);
            gauges.put(check, value);
            Gauge.builder("kibetipay.reconciliation.mismatches", value, LedgerReconciliation::orNaN)
                    .tag("scope", "ledger").tag("check", check)
                    .description("Ledger records failing an independent consistency check at the last run")
                    .register(metrics);
        }
        Gauge.builder("kibetipay.reconciliation.last.run", lastRunEpochSeconds, LedgerReconciliation::orNaN)
                .tag("scope", "ledger")
                .description("Epoch seconds of the last completed ledger reconciliation")
                .baseUnit("seconds")
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${app.reconciliation.interval:PT5M}", initialDelayString = "PT30S")
    void scheduled() {
        try {
            run();
        } catch (RuntimeException e) {
            // Gauges keep their last values; the last.run gauge going stale is what alerts.
            metrics.counter("kibetipay.reconciliation.runs", "scope", "ledger", "result", "error").increment();
            log.atError().addKeyValue("event", "ledger.reconciliation.error").setCause(e)
                    .log("Ledger reconciliation could not run");
        }
    }

    @Observed(name = "ledger.reconciliation")
    Report run() {
        Map<String, Long> counts = new LinkedHashMap<>();
        List<Finding> samples = new java.util.ArrayList<>();

        collect("unbalanced_journal", """
                WITH bad AS (
                    SELECT j.id, j.reference,
                           count(e.id) AS entries,
                           count(DISTINCT e.currency) AS currencies,
                           coalesce(sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END), 0) AS net
                    FROM wallet_journal j LEFT JOIN wallet_ledger_entry e ON e.journal_id = j.id
                    GROUP BY j.id, j.reference
                    HAVING count(e.id) < 2 OR count(DISTINCT e.currency) > 1
                        OR coalesce(sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END), 0) <> 0)
                SELECT count(*) OVER () AS total, id::text AS subject,
                       'reference=' || reference || ' entries=' || entries || ' currencies=' || currencies
                           || ' debitMinusCredit=' || net AS detail
                FROM bad ORDER BY id LIMIT :sample
                """, counts, samples);

        collect("balance_drift", """
                WITH bad AS (
                    SELECT a.id, a.currency, a.balance_minor,
                           coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END), 0) AS from_entries
                    FROM wallet_account a LEFT JOIN wallet_ledger_entry e ON e.wallet_id = a.id
                    GROUP BY a.id, a.currency, a.balance_minor
                    HAVING a.balance_minor
                        <> coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END), 0))
                SELECT count(*) OVER () AS total, id::text AS subject,
                       'currency=' || currency || ' stored=' || balance_minor || ' fromEntries=' || from_entries AS detail
                FROM bad ORDER BY id LIMIT :sample
                """, counts, samples);

        collect("entry_currency", """
                SELECT count(*) OVER () AS total, e.id::text AS subject,
                       'wallet=' || a.id || ' account=' || a.currency || ' entry=' || e.currency AS detail
                FROM wallet_ledger_entry e JOIN wallet_account a ON a.id = e.wallet_id
                WHERE e.currency <> a.currency
                ORDER BY e.id LIMIT :sample
                """, counts, samples);

        collect("trial_balance", """
                SELECT count(*) OVER () AS total, currency AS subject, 'net=' || sum(balance_minor) AS detail
                FROM wallet_account GROUP BY currency HAVING sum(balance_minor) <> 0
                ORDER BY currency LIMIT :sample
                """, counts, samples);

        var report = new Report(Instant.now(clock), Map.copyOf(counts), List.copyOf(samples));
        counts.forEach((check, n) -> gauges.get(check).set(n));
        lastRunEpochSeconds.set(report.checkedAt().getEpochSecond());
        metrics.counter("kibetipay.reconciliation.runs", "scope", "ledger",
                "result", report.clean() ? "clean" : "mismatch").increment();

        if (report.clean()) {
            log.atInfo().addKeyValue("event", "ledger.reconciliation.clean").log("Ledger reconciliation clean");
        } else {
            for (Finding f : report.samples()) {
                log.atError()
                        .addKeyValue("event", "ledger.reconciliation.mismatch")
                        .addKeyValue("check", f.check())
                        .addKeyValue("subject", f.subject())
                        .addKeyValue("detail", f.detail())
                        .log("Ledger reconciliation mismatch ({}): {} {}", f.check(), f.subject(), f.detail());
            }
        }
        return report;
    }

    private void collect(String check, String sql, Map<String, Long> counts, List<Finding> samples) {
        var rows = jdbc.sql(sql).param("sample", SAMPLE)
                .query((rs, n) -> new Object[] {rs.getLong("total"), rs.getString("subject"), rs.getString("detail")})
                .list();
        counts.put(check, rows.isEmpty() ? 0L : (Long) rows.getFirst()[0]);
        for (Object[] r : rows) {
            samples.add(new Finding(check, (String) r[1], (String) r[2]));
        }
    }

    private static double orNaN(AtomicLong value) {
        long v = value.get();
        return v < 0 ? Double.NaN : v;
    }
}

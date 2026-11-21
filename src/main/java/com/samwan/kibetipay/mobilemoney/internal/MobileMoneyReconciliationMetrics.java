package com.samwan.kibetipay.mobilemoney.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically counts provider-side items that need a person, and exports them as gauges.
 * Counting on a schedule (not on every Prometheus scrape) keeps database load fixed.
 *
 * <ul>
 *   <li>{@code kibetipay.mobilemoney.collections.unresolved{state=unknown}}: the provider call's
 *       outcome is unknown. The customer may have been charged; never resubmit automatically.</li>
 *   <li>{@code ...{state=stuck}}: submitted or pending longer than {@link #STUCK_AFTER} with no
 *       provider result.</li>
 *   <li>{@code kibetipay.reconciliation.mismatches{scope=mobilemoney,check=...}}: statement lines
 *       that disagree with an expected payment, or match none and are not yet in suspense.</li>
 *   <li>{@code kibetipay.mobilemoney.suspense.open}: unattributed money awaiting allocation.</li>
 * </ul>
 */
@Component
class MobileMoneyReconciliationMetrics {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyReconciliationMetrics.class);
    static final Duration STUCK_AFTER = Duration.ofMinutes(30);

    private final JdbcClient jdbc;
    private final Clock clock;
    /** -1 until the first run, exported as NaN. */
    private final Map<String, AtomicLong> values = new LinkedHashMap<>();

    MobileMoneyReconciliationMetrics(JdbcClient jdbc, MeterRegistry metrics, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        gauge(metrics, "unknown", "kibetipay.mobilemoney.collections.unresolved", "state", "unknown");
        gauge(metrics, "stuck", "kibetipay.mobilemoney.collections.unresolved", "state", "stuck");
        gauge(metrics, "suspense_open", "kibetipay.mobilemoney.suspense.open");
        for (String check : new String[] {"amount_mismatch", "currency_mismatch", "direction_mismatch",
                "unmatched_provider", "multiple_provider_transactions"}) {
            gauge(metrics, check, "kibetipay.reconciliation.mismatches", "scope", "mobilemoney", "check", check);
        }
    }

    @Scheduled(fixedDelayString = "${app.reconciliation.interval:PT5M}", initialDelayString = "PT40S")
    void refresh() {
        try {
            var stuckBefore = java.sql.Timestamp.from(Instant.now(clock).minus(STUCK_AFTER));
            set("unknown", jdbc.sql("SELECT count(*) FROM mobilemoney_collection WHERE status = 'UNKNOWN'")
                    .query(Long.class).single());
            set("stuck", jdbc.sql("""
                    SELECT count(*) FROM mobilemoney_collection
                    WHERE status IN ('SUBMITTING', 'PENDING') AND updated_at < :before
                    """).param("before", stuckBefore).query(Long.class).single());
            set("suspense_open", jdbc.sql("SELECT count(*) FROM mobilemoney_suspense_item WHERE status = 'OPEN'")
                    .query(Long.class).single());

            // Same matching rule as the admin reconciliation report.
            jdbc.sql("""
                    SELECT
                      count(*) FILTER (WHERE e.id IS NOT NULL AND p.id IS NOT NULL AND m.matches = 1
                                         AND e.direction = p.direction AND e.currency = p.currency
                                         AND e.amount_minor <> p.amount_minor) AS amount_mismatch,
                      count(*) FILTER (WHERE e.id IS NOT NULL AND p.id IS NOT NULL AND m.matches = 1
                                         AND e.direction = p.direction AND e.currency <> p.currency) AS currency_mismatch,
                      count(*) FILTER (WHERE e.id IS NOT NULL AND p.id IS NOT NULL AND m.matches = 1
                                         AND e.direction <> p.direction) AS direction_mismatch,
                      count(*) FILTER (WHERE e.id IS NULL AND s.id IS NULL) AS unmatched_provider,
                      count(DISTINCT e.id) FILTER (WHERE m.matches > 1) AS multiple_provider_transactions
                    FROM mobilemoney_expected_payment e
                    FULL JOIN mobilemoney_provider_transaction p
                           ON e.provider = p.provider AND e.reference = p.expected_reference
                    LEFT JOIN LATERAL (SELECT count(*) AS matches FROM mobilemoney_provider_transaction p2
                                       WHERE p2.provider = e.provider AND p2.expected_reference = e.reference) m ON TRUE
                    LEFT JOIN mobilemoney_suspense_item s ON s.provider_transaction_id = p.id
                    """).query((rs, n) -> {
                        for (String check : new String[] {"amount_mismatch", "currency_mismatch", "direction_mismatch",
                                "unmatched_provider", "multiple_provider_transactions"}) {
                            set(check, rs.getLong(check));
                        }
                        return null;
                    }).list();
        } catch (RuntimeException e) {
            log.atError().addKeyValue("event", "mobilemoney.reconciliation.error").setCause(e)
                    .log("Mobile money reconciliation metrics could not be refreshed");
        }
    }

    private void gauge(MeterRegistry metrics, String key, String name, String... tags) {
        var value = new AtomicLong(-1);
        values.put(key, value);
        Gauge.builder(name, value, v -> v.get() < 0 ? Double.NaN : v.get()).tags(tags).register(metrics);
    }

    private void set(String key, long value) {
        values.get(key).set(value);
    }
}

package com.samwan.kibetipay.anomaly.internal;

import com.samwan.kibetipay.anomaly.AnomalyEvents.AnomalyDetected;
import com.samwan.kibetipay.transfer.TransferEvents.TransferCompleted;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Streaming anomaly detection over completed transfers.
 * 1. Maintain a per-user daily outflow projection (its own table: no reads into other modules).
 * 2. Score today's running total against the user's personal baseline (z-score).
 * 3. Raise at most one alert per user per day.
 *
 * <p>Phase 5 swaps/augments the z-score with an Isolation Forest trained offline on features
 * (hour of day, counterparty novelty, amount, velocity), served behind the same interface.
 */
@Component
class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);
    private static final ZoneId ZONE = ZoneId.of("Africa/Nairobi");

    private final JdbcClient jdbc;
    private final AnomalyProperties props;
    private final ApplicationEventPublisher events;

    AnomalyDetector(JdbcClient jdbc, AnomalyProperties props, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.props = props;
        this.events = events;
    }

    /** Async, own transaction, tracked in the event publication registry (retried if it fails). */
    @ApplicationModuleListener
    void on(TransferCompleted e) {
        // Events are delivered at least once. Without this guard a redelivery would double-count outflow.
        int firstTime = jdbc.sql("INSERT INTO anomaly_processed_transfer (transfer_id) VALUES (:id) ON CONFLICT DO NOTHING")
                .param("id", e.transferId())
                .update();
        if (firstTime == 0) {
            return;
        }

        LocalDate day = e.completedAt().atZone(ZONE).toLocalDate();

        long todayTotal = jdbc.sql("""
                        INSERT INTO anomaly_daily_outflow (owner_id, day, outflow_minor, tx_count)
                        VALUES (:owner, :day, :amount, 1)
                        ON CONFLICT (owner_id, day) DO UPDATE
                           SET outflow_minor = anomaly_daily_outflow.outflow_minor + EXCLUDED.outflow_minor,
                               tx_count = anomaly_daily_outflow.tx_count + 1
                        RETURNING outflow_minor
                        """)
                .params(Map.of("owner", e.initiatorId(), "day", day, "amount", e.amountMinor()))
                .query(Long.class)
                .single();

        List<Long> history = jdbc.sql("""
                        SELECT outflow_minor FROM anomaly_daily_outflow
                        WHERE owner_id = :owner AND day < :day AND day >= :from
                        """)
                .params(Map.of("owner", e.initiatorId(), "day", day, "from", day.minusDays(90)))
                .query(Long.class)
                .list();

        if (history.size() < props.minHistoryDays()) {
            return;   // cold start: not enough data to know what "normal" is yet
        }

        var baseline = Baseline.of(history);
        double z = baseline.zScore(todayTotal);
        log.debug("owner={} today={} baseline={} z={}", e.initiatorId(), todayTotal, baseline, z);
        if (z < props.zScoreThreshold() || alreadyAlertedToday(e.initiatorId(), day)) {
            return;
        }

        var alertId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO anomaly_alert (id, owner_id, transfer_id, kind, z_score, details, created_at)
                        VALUES (:id, :owner, :transfer, 'DAILY_OUTFLOW_SPIKE', :z, :details, :at)
                        """)
                .param("id", alertId)
                .param("owner", e.initiatorId())
                .param("transfer", e.transferId())
                .param("z", Math.round(z * 1000) / 1000.0)
                .param("details", "Daily outflow %d vs baseline mean %.0f (sd %.0f, %d days)"
                        .formatted(todayTotal, baseline.mean(), baseline.stdDev(), baseline.days()))
                .param("at", java.sql.Timestamp.from(Instant.now()))
                .update();
        events.publishEvent(new AnomalyDetected(alertId, e.initiatorId(), e.transferId(),
                "DAILY_OUTFLOW_SPIKE", z, Instant.now()));
    }

    private boolean alreadyAlertedToday(String owner, LocalDate day) {
        return jdbc.sql("""
                        SELECT count(*) FROM anomaly_alert
                        WHERE owner_id = :owner AND created_at >= :start
                        """)
                .param("owner", owner)
                .param("start", java.sql.Timestamp.from(day.atStartOfDay(ZONE).toInstant()))
                .query(Long.class)
                .single() > 0;
    }
}

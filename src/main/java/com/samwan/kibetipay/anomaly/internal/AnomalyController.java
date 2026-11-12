package com.samwan.kibetipay.anomaly.internal;

import com.samwan.kibetipay.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AnomalyController {

    private final JdbcClient jdbc;

    AnomalyController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/anomalies/me")
    List<Alert> mine() {
        return jdbc.sql("SELECT * FROM anomaly_alert WHERE owner_id = :o ORDER BY created_at DESC LIMIT 50")
                .param("o", CurrentUser.id())
                .query(Alert.class)
                .list();
    }

    @GetMapping("/api/admin/anomalies")
    List<Alert> all() {
        return jdbc.sql("SELECT * FROM anomaly_alert WHERE acknowledged = false ORDER BY created_at DESC LIMIT 100")
                .query(Alert.class)
                .list();
    }

    /** Mapped by column name (owner_id -> ownerId) via JdbcClient's record mapping. */
    record Alert(UUID id, String ownerId, UUID transferId, String kind, BigDecimal zScore, String details,
                 boolean acknowledged, OffsetDateTime createdAt) {}
}

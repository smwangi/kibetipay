package com.samwan.kibetipay.anomaly;

import java.time.Instant;
import java.util.UUID;

public final class AnomalyEvents {

    private AnomalyEvents() {
    }

    /** Published when spending deviates sharply from a user's own baseline. A notifications module can react. */
    public record AnomalyDetected(UUID alertId, String ownerId, UUID transferId, String kind,
                                  double zScore, Instant at) {}
}

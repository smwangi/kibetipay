package com.samwan.kibetipay.anomaly.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BaselineTest {

    @Test
    void normalDayScoresLow() {
        var b = Baseline.of(List.of(100_000L, 120_000L, 90_000L, 110_000L, 95_000L, 105_000L, 100_000L));
        assertThat(b.zScore(110_000)).isLessThan(1.5);
    }

    @Test
    void spikeScoresHigh() {
        var b = Baseline.of(List.of(100_000L, 120_000L, 90_000L, 110_000L, 95_000L, 105_000L, 100_000L));
        assertThat(b.zScore(1_000_000)).isGreaterThan(3.0);
    }

    @Test
    void flatHistoryDoesNotExplode() {
        var b = Baseline.of(List.of(50_000L, 50_000L, 50_000L, 50_000L, 50_000L, 50_000L, 50_000L));
        assertThat(b.stdDev()).isZero();
        assertThat(b.zScore(60_000)).isFinite().isLessThan(3.0);
    }
}

package com.samwan.kibetipay.anomaly.internal;

import java.util.List;

/**
 * Pure statistics, no Spring: trivially unit-testable.
 * Uses mean / sample standard deviation of a user's historical daily outflow, so the
 * baseline is personal: KES 50,000 is normal for a trader and alarming for a student.
 */
record Baseline(int days, double mean, double stdDev) {

    static Baseline of(List<Long> dailyTotals) {
        int n = dailyTotals.size();
        if (n == 0) {
            return new Baseline(0, 0, 0);
        }
        double mean = dailyTotals.stream().mapToLong(Long::longValue).average().orElse(0);
        double variance = n < 2 ? 0 : dailyTotals.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .sum() / (n - 1);
        return new Baseline(n, mean, Math.sqrt(variance));
    }

    /**
     * Standard score of today's value. A floor on the std-dev stops users with perfectly flat
     * history (stdDev = 0) from producing infinite scores on their first different day.
     */
    double zScore(long value) {
        double floor = Math.max(stdDev, Math.max(mean * 0.1, 100));   // >= KES 1.00
        return (value - mean) / floor;
    }
}

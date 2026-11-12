package com.samwan.kibetipay.lending.internal.calc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Splits a commission pool across an agent lineage by percentage weights WITHOUT losing or
 * inventing a single cent, using the largest-remainder (Hamilton) method:
 * floor every share, then hand the leftover cents to the largest fractional remainders.
 *
 * <p>Example: KES 0.10 split 60/40 = 6 and 4 cents. KES 0.07 split 60/40 = 4.2 and 2.8
 * → floors 4 + 2 = 6, one cent left → goes to the 0.8 remainder → 4 and 3.
 *
 * <p>If the lineage is shorter than the weights (a top agent with no parent), the unused
 * weights are renormalised over the agents that exist, so the full pool is always paid.
 */
public final class CommissionSplitter {

    private CommissionSplitter() {
    }

    public static List<Long> split(long pool, List<Integer> weights, int recipients) {
        if (pool < 0 || recipients < 1) {
            throw new IllegalArgumentException("pool >= 0 and recipients >= 1 required");
        }
        List<Integer> w = weights.subList(0, Math.min(weights.size(), recipients));
        long weightSum = w.stream().mapToLong(Integer::longValue).sum();
        if (weightSum <= 0) {
            throw new IllegalArgumentException("weights must be positive");
        }

        long[] shares = new long[w.size()];
        long[] remainders = new long[w.size()];
        long allocated = 0;
        for (int i = 0; i < w.size(); i++) {
            long numerator = Math.multiplyExact(pool, w.get(i));
            shares[i] = numerator / weightSum;
            remainders[i] = numerator % weightSum;
            allocated += shares[i];
        }
        long leftover = pool - allocated;
        List<Integer> byRemainder = IntStream.range(0, w.size()).boxed()
                .sorted(Comparator.comparingLong((Integer i) -> remainders[i]).reversed()
                        .thenComparing(i -> i))          // tie-break: closer to the originator
                .toList();
        for (int k = 0; k < leftover; k++) {
            shares[byRemainder.get(k)]++;
        }

        List<Long> result = new ArrayList<>(w.size());
        for (long s : shares) {
            result.add(s);
        }
        return result;
    }
}

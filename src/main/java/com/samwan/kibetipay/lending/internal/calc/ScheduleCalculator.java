package com.samwan.kibetipay.lending.internal.calc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a repayment schedule with FLAT interest (common in Kenyan micro-lending: interest is a
 * fixed % of the principal for the whole tenor). Every component is split evenly across
 * installments and the rounding remainder goes to the LAST installment, so the parts always
 * sum exactly to the totals. Pure function: no Spring, no clock, trivially testable.
 *
 * <p>Learning extension: add a reducing-balance (amortising) variant and compare the effective
 * APR of both; that difference is exactly what consumer-protection disclosure rules are about.
 */
public final class ScheduleCalculator {

    private ScheduleCalculator() {
    }

    public record Terms(long principal, int interestBps, int feeBps, int tenorDays, int installments) {}

    public record Line(int seq, LocalDate dueDate, long principal, long interest, long fee) {
        public long total() {
            return principal + interest + fee;
        }
    }

    public static long interestFor(Terms t) {
        return Math.multiplyExact(t.principal(), t.interestBps()) / 10_000;
    }

    public static long feeFor(Terms t) {
        return Math.multiplyExact(t.principal(), t.feeBps()) / 10_000;
    }

    public static List<Line> schedule(Terms t, LocalDate disbursedOn) {
        if (t.installments() < 1 || t.principal() <= 0) {
            throw new IllegalArgumentException("Invalid terms " + t);
        }
        int n = t.installments();
        long interest = interestFor(t);
        long fee = feeFor(t);
        int interval = Math.max(1, t.tenorDays() / n);

        List<Line> lines = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            boolean last = i == n;
            LocalDate due = last ? disbursedOn.plusDays(t.tenorDays()) : disbursedOn.plusDays((long) interval * i);
            lines.add(new Line(i, due,
                    share(t.principal(), n, last),
                    share(interest, n, last),
                    share(fee, n, last)));
        }
        return lines;
    }

    /** Even share; the last installment absorbs the remainder. 100 / 3 = 33, 33, 34. */
    static long share(long total, int n, boolean last) {
        long base = total / n;
        return last ? total - base * (n - 1) : base;
    }
}

package com.samwan.kibetipay.lending.internal.calc;

import java.util.ArrayList;
import java.util.List;

/**
 * The repayment "waterfall". A payment is applied to the OLDEST installment first, and within an
 * installment in this order: penalty → fee → interest → principal. This order is a business
 * decision (lenders recover the costliest-to-carry items first) and is often regulated; keeping
 * it in one pure class makes it easy to test, explain and change.
 */
public final class RepaymentAllocator {

    private RepaymentAllocator() {
    }

    /** Outstanding amounts of one installment, in minor units. */
    public record Outstanding(int seq, long penalty, long fee, long interest, long principal) {
        public long total() {
            return penalty + fee + interest + principal;
        }
    }

    public record Part(int seq, long penalty, long fee, long interest, long principal) {
        public long total() {
            return penalty + fee + interest + principal;
        }
    }

    public record Allocation(List<Part> parts, long penalty, long fee, long interest, long principal) {
        public long total() {
            return penalty + fee + interest + principal;
        }
    }

    /**
     * @param oldestFirst outstanding installments ordered by due date
     * @param amount      payment in minor units; must not exceed the total outstanding
     */
    public static Allocation allocate(List<Outstanding> oldestFirst, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        long outstanding = oldestFirst.stream().mapToLong(Outstanding::total).sum();
        if (amount > outstanding) {
            throw new OverpaymentException(amount, outstanding);
        }

        long left = amount;
        long tp = 0, tf = 0, ti = 0, tpr = 0;
        List<Part> parts = new ArrayList<>();
        for (Outstanding o : oldestFirst) {
            if (left == 0) {
                break;
            }
            long p = Math.min(left, o.penalty());   left -= p;
            long f = Math.min(left, o.fee());       left -= f;
            long i = Math.min(left, o.interest());  left -= i;
            long pr = Math.min(left, o.principal()); left -= pr;
            if (p + f + i + pr > 0) {
                parts.add(new Part(o.seq(), p, f, i, pr));
                tp += p; tf += f; ti += i; tpr += pr;
            }
        }
        return new Allocation(List.copyOf(parts), tp, tf, ti, tpr);
    }

    public static final class OverpaymentException extends RuntimeException {
        public final long amount;
        public final long outstanding;

        OverpaymentException(long amount, long outstanding) {
            super("Payment " + amount + " exceeds outstanding " + outstanding);
            this.amount = amount;
            this.outstanding = outstanding;
        }
    }
}

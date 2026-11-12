package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.lending.internal.calc.ScheduleCalculator;

/** Loan product read from lending_product. Products are data, so new ones need no deploy. */
record Product(String code, String name, long minPrincipalMinor, long maxPrincipalMinor, long firstLoanMaxMinor,
               int interestBps, int feeBps, int tenorDays, int installments, int penaltyBpsPerDay, boolean active) {

    ScheduleCalculator.Terms terms(long principal) {
        return new ScheduleCalculator.Terms(principal, interestBps, feeBps, tenorDays, installments);
    }
}

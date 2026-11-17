package com.samwan.kibetipay.mobilemoney.internal;

import java.util.Objects;

/** Compares evidence; it never authorizes a payment or changes the ledger. */
final class Reconciliation {
    enum Result {
        MATCHED,
        AMOUNT_MISMATCH,
        CURRENCY_MISMATCH,
        DIRECTION_MISMATCH
    }

    private Reconciliation() {}

    static Result compare(String expectedDirection, long expectedAmount, String expectedCurrency,
                          String actualDirection, long actualAmount, String actualCurrency) {
        if (!Objects.equals(expectedDirection, actualDirection)) {
            return Result.DIRECTION_MISMATCH;
        }
        if (!Objects.equals(expectedCurrency, actualCurrency)) {
            return Result.CURRENCY_MISMATCH;
        }
        if (expectedAmount != actualAmount) {
            return Result.AMOUNT_MISMATCH;
        }
        return Result.MATCHED;
    }
}

package com.samwan.kibetipay.mobilemoney.internal;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ReconciliationTest {
    @Test
    void matchesOnlyWhenDirectionCurrencyAndAmountAgree() {
        assertThat(Reconciliation.compare("COLLECTION", 1000, "KES", "COLLECTION", 1000, "KES"))
                .isEqualTo(Reconciliation.Result.MATCHED);
        assertThat(Reconciliation.compare("COLLECTION", 1000, "KES", "PAYOUT", 1000, "KES"))
                .isEqualTo(Reconciliation.Result.DIRECTION_MISMATCH);
        assertThat(Reconciliation.compare("COLLECTION", 1000, "KES", "COLLECTION", 1000, "USD"))
                .isEqualTo(Reconciliation.Result.CURRENCY_MISMATCH);
        assertThat(Reconciliation.compare("COLLECTION", 1000, "KES", "COLLECTION", 999, "KES"))
                .isEqualTo(Reconciliation.Result.AMOUNT_MISMATCH);
    }
}

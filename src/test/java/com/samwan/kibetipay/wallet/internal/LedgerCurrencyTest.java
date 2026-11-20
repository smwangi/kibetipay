package com.samwan.kibetipay.wallet.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.samwan.kibetipay.shared.error.DomainException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class LedgerCurrencyTest {
    @Test
    void rejectsWalletOperationsInAnotherCurrency() {
        var ledger = new LedgerService(null, null, null, new LedgerProperties("USD", null),
                new SimpleMeterRegistry());
        assertThatThrownBy(() -> ledger.open("alice", "TZS"))
                .isInstanceOf(DomainException.RuleViolation.class);
        assertThatThrownBy(() -> ledger.get("alice", "KES"))
                .isInstanceOf(DomainException.RuleViolation.class);
        assertThatThrownBy(() -> ledger.exists("alice", "TZS"))
                .isInstanceOf(DomainException.RuleViolation.class);
    }
}

package com.samwan.kibetipay.transfer.internal.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferStateMachineTest {

    private Transfer newTransfer() {
        return Transfer.initiate("alice", "bob", Money.of(10_000, "KES"), "lunch", "key-12345678");
    }

    @Test
    void approvedPathCompletes() {
        var t = newTransfer();
        t.complete(UUID.randomUUID(), 10);
        assertThat(t.status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(t.riskScore()).isEqualTo(10);
    }

    @Test
    void reviewThenApprove() {
        var t = newTransfer();
        t.holdForReview(55);
        t.complete(UUID.randomUUID(), null);
        assertThat(t.status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(t.riskScore()).isEqualTo(55);
    }

    @Test
    void cannotCompleteARejectedTransfer() {
        var t = newTransfer();
        t.reject(90, "risk");
        assertThatThrownBy(() -> t.complete(UUID.randomUUID(), null))
                .isInstanceOf(DomainException.Conflict.class);
    }
}

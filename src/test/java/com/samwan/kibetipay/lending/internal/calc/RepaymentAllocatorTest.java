package com.samwan.kibetipay.lending.internal.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samwan.kibetipay.lending.internal.calc.RepaymentAllocator.Outstanding;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepaymentAllocatorTest {

    private final List<Outstanding> twoLate = List.of(
            new Outstanding(1, 50, 750, 2_500, 25_000),
            new Outstanding(2, 0, 750, 2_500, 25_000));

    @Test
    void penaltyThenFeeThenInterestThenPrincipal() {
        var a = RepaymentAllocator.allocate(twoLate, 1_000);
        assertThat(a.penalty()).isEqualTo(50);
        assertThat(a.fee()).isEqualTo(750);
        assertThat(a.interest()).isEqualTo(200);
        assertThat(a.principal()).isZero();
    }

    @Test
    void oldestInstallmentIsClearedFirst() {
        var a = RepaymentAllocator.allocate(twoLate, 28_300 + 1_000);
        assertThat(a.parts()).hasSize(2);
        assertThat(a.parts().get(0).total()).isEqualTo(28_300);   // installment 1 fully paid
        assertThat(a.parts().get(1).fee()).isEqualTo(750);
        assertThat(a.total()).isEqualTo(29_300);
    }

    @Test
    void refusesOverpayment() {
        assertThatThrownBy(() -> RepaymentAllocator.allocate(twoLate, 1_000_000))
                .isInstanceOf(RepaymentAllocator.OverpaymentException.class);
    }
}

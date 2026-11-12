package com.samwan.kibetipay.lending.internal.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ScheduleCalculatorTest {

    private final ScheduleCalculator.Terms soko = new ScheduleCalculator.Terms(1_000_00, 1000, 300, 28, 4);

    @Test
    void flatInterestAndFee() {
        assertThat(ScheduleCalculator.interestFor(soko)).isEqualTo(100_00);   // 10% of 1,000
        assertThat(ScheduleCalculator.feeFor(soko)).isEqualTo(30_00);         // 3%
    }

    @Test
    void weeklyInstallmentsSumExactlyToTotals() {
        var lines = ScheduleCalculator.schedule(soko, LocalDate.of(2026, 11, 7));
        assertThat(lines).hasSize(4);
        assertThat(lines.getFirst().dueDate()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(lines.getLast().dueDate()).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(lines.stream().mapToLong(ScheduleCalculator.Line::principal).sum()).isEqualTo(1_000_00);
        assertThat(lines.stream().mapToLong(ScheduleCalculator.Line::total).sum()).isEqualTo(1_130_00);
    }

    @Test
    void remainderGoesToLastInstallment() {
        var terms = new ScheduleCalculator.Terms(100, 0, 0, 30, 3);
        var lines = ScheduleCalculator.schedule(terms, LocalDate.of(2026, 1, 1));
        assertThat(lines).extracting(ScheduleCalculator.Line::principal).containsExactly(33L, 33L, 34L);
    }
}

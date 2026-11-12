package com.samwan.kibetipay.lending.internal.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommissionSplitterTest {

    @Test
    void exactSplit() {
        assertThat(CommissionSplitter.split(10, List.of(60, 40), 2)).containsExactly(6L, 4L);
    }

    @Test
    void leftoverCentGoesToLargestRemainder() {
        // 7 * 0.6 = 4.2, 7 * 0.4 = 2.8 -> floors 4 + 2, leftover cent to the 0.8 remainder
        assertThat(CommissionSplitter.split(7, List.of(60, 40), 2)).containsExactly(4L, 3L);
    }

    @Test
    void topAgentWithoutParentGetsTheWholePool() {
        assertThat(CommissionSplitter.split(7, List.of(60, 40), 1)).containsExactly(7L);
    }

    @Test
    void neverLosesACent() {
        for (long pool = 0; pool < 500; pool++) {
            long sum = CommissionSplitter.split(pool, List.of(50, 30, 20), 3).stream().mapToLong(Long::longValue).sum();
            assertThat(sum).isEqualTo(pool);
        }
    }
}

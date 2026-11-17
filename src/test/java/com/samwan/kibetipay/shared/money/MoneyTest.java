package com.samwan.kibetipay.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void convertsMajorToMinorUnitsExactly() {
        assertThat(Money.ofMajor(new BigDecimal("1500.50"), "KES").minor()).isEqualTo(150050);
    }

    @Test
    void rejectsSubCentPrecisionInsteadOfRounding() {
        assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("10.005"), "KES"))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.of(100, "KES").plus(Money.of(100, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatsForHumans() {
        assertThat(Money.of(150050, "KES")).hasToString("KES 1500.50");
    }

    @Test
    void respectsZeroThreeAndFourFractionalDigits() {
        assertThat(Money.ofMajor(new BigDecimal("123"), "JPY").minor()).isEqualTo(123);
        assertThat(Money.ofMajor(new BigDecimal("1.234"), "KWD").minor()).isEqualTo(1234);
        assertThat(Money.ofMajor(new BigDecimal("1.2345"), "CLF").minor()).isEqualTo(12345);
        assertThat(Money.of(1234, "KWD").toMajor()).isEqualByComparingTo("1.234");
        assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("1.1"), "JPY")).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("1.2345"), "KWD")).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.of(1, "XAU")).isInstanceOf(IllegalArgumentException.class);
    }
}

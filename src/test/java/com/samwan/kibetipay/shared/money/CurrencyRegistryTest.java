package com.samwan.kibetipay.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CurrencyRegistryTest {
    @Test
    void exposesConfiguredIsoCurrenciesAndTheirPrecision() {
        var registry = new CurrencyRegistry(List.of("JPY", "USD", "KWD", "CLF"));
        assertThat(registry.fractionalDigits("JPY")).isZero();
        assertThat(registry.fractionalDigits("USD")).isEqualTo(2);
        assertThat(registry.fractionalDigits("KWD")).isEqualTo(3);
        assertThat(registry.fractionalDigits("CLF")).isEqualTo(4);
        assertThatThrownBy(() -> registry.fractionalDigits("KES")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidDuplicateOrIndeterminateCurrencyDefinitions() {
        assertThatThrownBy(() -> new CurrencyRegistry(List.of("USD", "USD"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CurrencyRegistry(List.of("ZZZ"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CurrencyRegistry(List.of("XAU"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CurrencyRegistry(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsTheOffendingCodeInValidationErrors() {
        assertThatThrownBy(() -> new CurrencyRegistry(List.of("USD", "KES", "USD")))
                .hasMessage("Duplicate currency: USD");
        assertThatThrownBy(() -> new CurrencyRegistry(List.of("ZZZ")))
                .hasMessage("Unknown ISO 4217 currency code: ZZZ");
        assertThatThrownBy(() -> new CurrencyRegistry(List.of("usd")))
                .hasMessage("Unknown ISO 4217 currency code: usd");
        assertThatThrownBy(() -> new CurrencyRegistry(List.of(" ")))
                .hasMessage("Currency code must not be blank");
    }

    @Test
    void supportsIsNullSafe() {
        var registry = new CurrencyRegistry(List.of("USD"));
        assertThat(registry.supports("USD")).isTrue();
        assertThat(registry.supports("KES")).isFalse();
        assertThat(registry.supports(null)).isFalse();
    }

    @Test
    void bindsEnabledCurrenciesFromConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(RegistryConfiguration.class)
                .withPropertyValues("app.currencies.enabled=JPY,USD,KWD")
                .run(context -> assertThat(context.getBean(CurrencyRegistry.class).enabled())
                        .containsExactly("JPY", "USD", "KWD"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CurrencyRegistry.class)
    static class RegistryConfiguration {}
}

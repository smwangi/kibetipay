package com.samwan.kibetipay.wallet.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.samwan.kibetipay.wallet.WalletApi.SystemAccounts;
import com.samwan.kibetipay.shared.money.CurrencyRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerPropertiesTest {
    @Test
    void supportsConfiguredCurrenciesWithTwoFractionalDigits() {
        assertThat(new LedgerProperties("USD", null).defaultCurrency()).isEqualTo("USD");
        assertThat(new LedgerProperties("TZS", null).defaultCurrency()).isEqualTo("TZS");
        assertThat(new LedgerProperties("KES", null).floatOwnerId()).isEqualTo(SystemAccounts.FLOAT);
    }

    @Test
    void rejectsUnknownUnsupportedOrInconsistentConfiguration() {
        assertThatThrownBy(() -> new LedgerProperties("ZZZ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerProperties("JPY", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerProperties("USD", "other:float")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void operatingCurrencyMustBePresentInRegistry() {
        var initializer = new PlatformCurrencyInitializer(null, new LedgerProperties("USD", null), null,
                new CurrencyRegistry(List.of("KES")));
        assertThatThrownBy(initializer::initialize).isInstanceOf(IllegalStateException.class);
    }
}

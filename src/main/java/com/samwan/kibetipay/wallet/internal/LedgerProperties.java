package com.samwan.kibetipay.wallet.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import com.samwan.kibetipay.wallet.WalletApi.SystemAccounts;
import java.util.Currency;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.ledger")
public record LedgerProperties(
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String defaultCurrency,
        String floatOwnerId) {

    public LedgerProperties {
        if (defaultCurrency != null) {
            Currency currency = Currency.getInstance(defaultCurrency);
            if (currency.getDefaultFractionDigits() != 2) {
                throw new IllegalArgumentException("Platform currency must have two fractional digits");
            }
        }
        if (floatOwnerId == null || floatOwnerId.isBlank()) {
            floatOwnerId = SystemAccounts.FLOAT;
        }
        if (!SystemAccounts.FLOAT.equals(floatOwnerId)) {
            throw new IllegalArgumentException("Configured float account must match the platform system float");
        }
    }
}

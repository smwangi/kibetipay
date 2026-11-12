package com.samwan.kibetipay.wallet.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.ledger")
public record LedgerProperties(
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String defaultCurrency,
        String floatOwnerId) {

    public LedgerProperties {
        if (floatOwnerId == null || floatOwnerId.isBlank()) {
            floatOwnerId = "system:float";
        }
    }
}

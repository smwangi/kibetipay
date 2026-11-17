package com.samwan.kibetipay.shared.money;

import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment configuration for currencies; provider and product support are separate decisions. */
@ConfigurationProperties(prefix = "app.currencies")
public record CurrencyRegistry(List<String> enabled) {

    public CurrencyRegistry {
        if (enabled == null || enabled.isEmpty()) {
            throw new IllegalArgumentException("At least one currency must be configured");
        }
        Map<String, String> unique = new LinkedHashMap<>();
        for (String code : enabled) {
            Currency currency = requireCurrency(code);
            if (unique.putIfAbsent(currency.getCurrencyCode(), currency.getCurrencyCode()) != null) {
                throw new IllegalArgumentException("Duplicate currency: " + code);
            }
        }
        enabled = List.copyOf(unique.keySet());
    }

    public boolean supports(String code) {
        return enabled.contains(code);
    }

    public int fractionalDigits(String code) {
        if (!supports(code)) {
            throw new IllegalArgumentException("Currency is not enabled: " + code);
        }
        return requireCurrency(code).getDefaultFractionDigits();
    }

    static Currency requireCurrency(String code) {
        Currency currency = Currency.getInstance(code);
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Currency has no defined minor-unit precision: " + code);
        }
        return currency;
    }
}

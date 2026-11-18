package com.samwan.kibetipay.shared.money;

import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment configuration for currencies; provider and product support are separate decisions. */
@ConfigurationProperties(prefix = "app.currencies")
public record CurrencyRegistry(List<String> enabled) {

    public CurrencyRegistry {
        if (enabled == null || enabled.isEmpty()) {
            throw new IllegalArgumentException("At least one currency must be configured");
        }
        // Insertion-ordered so enabled() keeps the configured order; add() reports duplicates.
        Set<String> unique = new LinkedHashSet<>();
        for (String code : enabled) {
            requireCurrency(code);
            if (!unique.add(code)) {
                throw new IllegalArgumentException("Duplicate currency: " + code);
            }
        }
        enabled = List.copyOf(unique);
    }

    /** Null-safe: an unset or unknown code is simply not supported. */
    public boolean supports(String code) {
        return code != null && enabled.contains(code);
    }

    public int fractionalDigits(String code) {
        if (!supports(code)) {
            throw new IllegalArgumentException("Currency is not enabled: " + code);
        }
        return requireCurrency(code).getDefaultFractionDigits();
    }

    /**
     * Resolves an exact ISO 4217 code (case-sensitive, so the code is already canonical)
     * that has a defined minor-unit precision.
     */
    static Currency requireCurrency(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Currency code must not be blank");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown ISO 4217 currency code: " + code, e);
        }
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Currency has no defined minor-unit precision: " + code);
        }
        return currency;
    }
}

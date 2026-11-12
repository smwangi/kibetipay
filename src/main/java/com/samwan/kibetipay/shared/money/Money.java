package com.samwan.kibetipay.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable money in minor units (cents). Never use double for money.
 * Arithmetic only between equal currencies.
 */
public record Money(long minor, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(long minor, String currencyCode) {
        return new Money(minor, Currency.getInstance(currencyCode));
    }

    public static Money ofMajor(BigDecimal major, String currencyCode) {
        Currency c = Currency.getInstance(currencyCode);
        long minor = major.setScale(c.getDefaultFractionDigits(), RoundingMode.UNNECESSARY)
                .movePointRight(c.getDefaultFractionDigits())
                .longValueExact();
        return new Money(minor, c);
    }

    public static Money zero(String currencyCode) {
        return of(0, currencyCode);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public boolean isPositive() {
        return minor > 0;
    }

    public boolean isNegative() {
        return minor < 0;
    }

    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minor, currency.getDefaultFractionDigits());
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minor, other.minor);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return currencyCode() + " " + toMajor().toPlainString();
    }
}

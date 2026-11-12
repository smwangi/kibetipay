package com.samwan.kibetipay.lending.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param commissionRateBps share of collected interest + fees paid out as agent commission
 * @param commissionSplit   percentage per lineage level, originating agent first (e.g. [60, 40])
 * @param defaultAfterDays  days past due after which a loan is classified as defaulted
 * @param approveScore      minimum credit score to auto-approve
 * @param reviewScore       scores between review and approve go to a human
 */
@Validated
@ConfigurationProperties(prefix = "app.lending")
public record LendingProperties(
        @Min(0) @Max(10_000) int commissionRateBps,
        @NotEmpty List<@Min(1) @Max(100) Integer> commissionSplit,
        @Min(1) int defaultAfterDays,
        @Min(0) @Max(100) int approveScore,
        @Min(0) @Max(100) int reviewScore) {

    public LendingProperties {
        int sum = commissionSplit.stream().mapToInt(Integer::intValue).sum();
        if (sum != 100) {
            throw new IllegalArgumentException("app.lending.commission-split must sum to 100, was " + sum);
        }
        if (reviewScore > approveScore) {
            throw new IllegalArgumentException("app.lending.review-score must be <= approve-score");
        }
        commissionSplit = List.copyOf(commissionSplit);
    }
}

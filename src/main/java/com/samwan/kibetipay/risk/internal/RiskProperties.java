package com.samwan.kibetipay.risk.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.risk")
public record RiskProperties(
        @Min(0) @Max(100) int approveBelow,
        @Min(0) @Max(100) int rejectAtOrAbove,
        @NotNull Duration velocityWindow,
        @Positive int velocityMaxTransfers,
        @Positive long largeAmountMinor) {

    public RiskProperties {
        if (approveBelow > rejectAtOrAbove) {
            // Cross-field validation: fail at startup, not at 3 PM on a Tuesday.
            throw new IllegalArgumentException("app.risk.approve-below must be <= app.risk.reject-at-or-above");
        }
    }
}

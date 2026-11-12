package com.samwan.kibetipay.anomaly.internal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.anomaly")
public record AnomalyProperties(
        @DecimalMin("1.0") double zScoreThreshold,
        @Min(3) int minHistoryDays) {}

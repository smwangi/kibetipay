package com.samwan.kibetipay.assistant.internal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.assistant")
public record AssistantProperties(
        @Min(1) @Max(20) int topK,
        @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold,
        boolean ingestOnStartup) {}

package com.samwan.kibetipay.transfer.internal.command;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.transfer")
public record TransferProperties(@Positive long maxAmountMinor) {}

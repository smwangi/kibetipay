package com.samwan.kibetipay.agent.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.agent")
public record AgentProperties(@Min(1) @Max(5) int maxDepth) {}

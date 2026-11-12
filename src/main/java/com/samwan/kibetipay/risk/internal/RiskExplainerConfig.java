package com.samwan.kibetipay.risk.internal;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;

/**
 * Two candidates for one injection point, and no if/else anywhere:
 * <ul>
 *   <li>{@code app.ai.enabled=true} (default): both beans exist, the non-fallback LLM explainer wins;</li>
 *   <li>{@code app.ai.enabled=false}: only the no-op exists, so it is injected.</li>
 * </ul>
 * Without {@code @Fallback} the first case would fail at startup with
 * NoUniqueBeanDefinitionException ("expected single matching bean but found 2").
 */
@Configuration(proxyBeanMethods = false)
class RiskExplainerConfig {

    @Bean
    @Fallback
    RiskExplainer noOpRiskExplainer() {
        return new NoOpRiskExplainer();
    }

    @Bean
    @ConditionalOnBooleanProperty(name = "app.ai.enabled", matchIfMissing = true)
    RiskExplainer llmRiskExplainer(ChatClient.Builder builder) {
        return new LlmRiskExplainer(builder);
    }
}

package com.samwan.kibetipay.risk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RiskExplainerConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RiskExplainerConfig.class)
            .withBean(ChatClient.Builder.class, () -> mock(ChatClient.Builder.class, Answers.RETURNS_SELF));

    @Test
    void llmExplainerWinsWhenAiIsEnabled() {
        runner.run(ctx -> assertThat(ctx.getBean(RiskExplainer.class)).isInstanceOf(LlmRiskExplainer.class));
    }

    @Test
    void noOpFallbackIsUsedWhenAiIsDisabled() {
        runner.withPropertyValues("app.ai.enabled=false")
                .run(ctx -> assertThat(ctx.getBean(RiskExplainer.class)).isInstanceOf(NoOpRiskExplainer.class));
    }
}

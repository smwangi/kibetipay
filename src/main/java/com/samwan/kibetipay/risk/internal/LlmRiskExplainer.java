package com.samwan.kibetipay.risk.internal;

import com.samwan.kibetipay.risk.RiskApi.RiskRequest;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.resilience.annotation.ConcurrencyLimit;

/**
 * Turns rule hits into a 2-3 sentence note for a fraud analyst. Bounded concurrency so a slow
 * model can't exhaust threads. Only ids and amounts are sent to the model: no names, phone
 * numbers or free-text notes (PII).
 *
 * <p>Two kinds of fallback protect payments from the LLM, and they are NOT the same thing:
 * <ul>
 *   <li>startup: if AI is switched off, this bean is never created and the {@code @Fallback}
 *       {@link NoOpRiskExplainer} is injected instead (see {@link RiskExplainerConfig});</li>
 *   <li>runtime: if this bean exists but the model call fails at 3 PM, the try/catch below
 *       returns empty. {@code @Fallback} cannot help here: bean wiring happened long ago.</li>
 * </ul>
 */
class LlmRiskExplainer implements RiskExplainer {

    private static final Logger log = LoggerFactory.getLogger(LlmRiskExplainer.class);

    private final ChatClient chat;

    LlmRiskExplainer(ChatClient.Builder builder) {
        this.chat = builder
                .defaultSystem("""
                        You are a payments fraud analyst assistant. Given rule hits for a transfer,
                        write at most 3 short sentences explaining the risk to a human reviewer and
                        what they should check. Do not invent facts. Do not decide the outcome.
                        """)
                .build();
    }

    @Override
    @ConcurrencyLimit(4)
    public Optional<String> explain(RiskRequest r, int score, List<String> reasons) {
        try {
            String text = chat.prompt()
                    .user(u -> u.text("""
                                    Score: {score}/100
                                    Rule hits: {reasons}
                                    Amount: {amount}
                                    Sender balance before transfer: {balance}
                                    """)
                            .param("score", score)
                            .param("reasons", String.join(", ", reasons))
                            .param("amount", r.amount().toString())
                            .param("balance", r.subjectBalance().toString()))
                    .call()
                    .content();
            return Optional.ofNullable(text);
        } catch (RuntimeException e) {
            log.warn("Risk explanation unavailable for {}: {}", r.transactionId(), e.getMessage());
            return Optional.empty();
        }
    }
}

package com.samwan.kibetipay.insights.internal;

import com.samwan.kibetipay.insights.InsightsApi.Category;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Batch LLM classification: every 30 s take up to 20 uncategorised outgoing transactions,
 * classify them in ONE model call using structured output, and write the categories back.
 * Batching cuts cost/latency; running outside the event listener keeps DB transactions short.
 */
@Component
@ConditionalOnBooleanProperty(name = "app.insights.enabled", matchIfMissing = true)
class TransactionCategoriser {

    private static final Logger log = LoggerFactory.getLogger(TransactionCategoriser.class);

    private final JdbcClient jdbc;
    private final ChatClient chat;

    TransactionCategoriser(JdbcClient jdbc, ChatClient.Builder builder) {
        this.jdbc = jdbc;
        String allowed = Arrays.stream(Category.values())
                .filter(c -> c != Category.UNCATEGORISED)
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        this.chat = builder
                .defaultSystem("""
                        You categorise personal payments in Kenya (M-Pesa style P2P transfers).
                        Use only these categories: %s.
                        Base the choice on the note and counterparty. If unsure, use OTHER.
                        """.formatted(allowed))
                .build();
    }

    record Pending(UUID transferId, String counterparty, String amount, String note) {}

    record Classification(UUID transferId, Category category) {}

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT20S")
    void categoriseBatch() {
        List<Pending> batch = jdbc.sql("""
                        SELECT transfer_id, counterparty, (amount_minor / 100.0)::text AS amount, coalesce(note, '') AS note
                        FROM insight_transaction
                        WHERE direction = 'OUT' AND category IS NULL
                        ORDER BY occurred_at
                        LIMIT 20
                        """)
                .query(Pending.class)
                .list();
        if (batch.isEmpty()) {
            return;
        }
        try {
            List<Classification> result = chat.prompt()
                    .user(u -> u.text("Categorise each transaction. Return one entry per transferId.\n{items}")
                            .param("items", batch.stream().map(Pending::toString).collect(Collectors.joining("\n"))))
                    .call()
                    .entity(new ParameterizedTypeReference<List<Classification>>() {});
            if (result == null) {
                return;
            }
            result.stream()
                    .filter(c -> c.category() != null && c.category() != Category.UNCATEGORISED)
                    .forEach(c -> jdbc.sql("""
                                    UPDATE insight_transaction SET category = :cat
                                    WHERE transfer_id = :id AND direction = 'OUT' AND category IS NULL
                                    """)
                            .param("cat", c.category().name())
                            .param("id", c.transferId())
                            .update());
            log.debug("Categorised {} of {} transactions", result.size(), batch.size());
        } catch (RuntimeException e) {
            // Rows stay NULL and are retried next run. Insights are best-effort, never critical-path.
            log.warn("Categorisation batch failed: {}", e.getMessage());
        }
    }
}

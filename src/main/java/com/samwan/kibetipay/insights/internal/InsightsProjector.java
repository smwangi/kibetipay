package com.samwan.kibetipay.insights.internal;

import com.samwan.kibetipay.transfer.TransferEvents.TransferCompleted;
import com.samwan.kibetipay.insights.InsightsApi.Category;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * CQRS projection: builds this module's own read model from transfer events.
 * One completed transfer becomes two rows (sender sees OUT, recipient sees IN).
 * ON CONFLICT DO NOTHING makes it idempotent under redelivery.
 * Categorisation happens later in a batch job, so no LLM call runs inside this transaction.
 */
@Component
class InsightsProjector {

    private static final String INSERT = """
            INSERT INTO insight_transaction
                (transfer_id, owner_id, direction, counterparty, amount_minor, currency, note, category, occurred_at)
            VALUES (:id, :owner, :dir, :cp, :amount, :currency, :note, :category, :at)
            ON CONFLICT (transfer_id, owner_id) DO NOTHING
            """;

    private final JdbcClient jdbc;

    InsightsProjector(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @ApplicationModuleListener
    void on(TransferCompleted e) {
        insert(e, e.initiatorId(), InsightDirection.OUT, e.recipientId(), null);
        // Incoming money is not "spending"; mark it so the categoriser skips it.
        insert(e, e.recipientId(), InsightDirection.IN, e.initiatorId(), Category.OTHER.name());
    }

    private void insert(TransferCompleted e, String owner, InsightDirection dir, String counterparty, String category) {
        jdbc.sql(INSERT)
                .param("id", e.transferId())
                .param("owner", owner)
                .param("dir", dir.name())
                .param("cp", counterparty)
                .param("amount", e.amountMinor())
                .param("currency", e.currency())
                .param("note", e.note())
                .param("category", category)
                .param("at", Timestamp.from(e.completedAt()))
                .update();
    }
}

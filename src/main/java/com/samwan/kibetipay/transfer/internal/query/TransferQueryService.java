package com.samwan.kibetipay.transfer.internal.query;

import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.transfer.TransferQueries;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Query side: plain SQL straight into DTOs, no aggregates, no dirty checking.
 * Phase 6 can move this onto a read replica or a denormalised table without touching commands.
 */
@Service
@Transactional(readOnly = true)
class TransferQueryService implements TransferQueries {

    private static final String SELECT = """
            SELECT id, initiator_id, recipient_id, amount_minor, currency, note, status,
                   risk_score, failure_reason, created_at, updated_at
            FROM transfer
            """;

    private final JdbcClient jdbc;

    TransferQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TransferView> byId(UUID id, String viewerId) {
        // Authorisation in the query itself: you only see transfers you are part of.
        return jdbc.sql(SELECT + " WHERE id = :id AND (initiator_id = :viewer OR recipient_id = :viewer)")
                .param("id", id)
                .param("viewer", viewerId)
                .query(TransferQueryService::map)
                .optional();
    }

    @Override
    public List<TransferView> recentFor(String ownerId, int limit) {
        return jdbc.sql(SELECT + " WHERE initiator_id = :owner OR recipient_id = :owner ORDER BY created_at DESC LIMIT :limit")
                .param("owner", ownerId)
                .param("limit", clamp(limit))
                .query(TransferQueryService::map)
                .list();
    }

    @Override
    public List<TransferView> inReview(int limit) {
        return jdbc.sql(SELECT + " WHERE status = 'IN_REVIEW' ORDER BY created_at LIMIT :limit")
                .param("limit", clamp(limit))
                .query(TransferQueryService::map)
                .list();
    }

    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), 100);
    }

    private static TransferView map(ResultSet rs, int row) throws SQLException {
        var amount = Money.of(rs.getLong("amount_minor"), rs.getString("currency"));
        return new TransferView(
                rs.getObject("id", UUID.class),
                rs.getString("initiator_id"),
                rs.getString("recipient_id"),
                amount.toMajor().toPlainString(),
                amount.currencyCode(),
                rs.getString("note"),
                rs.getString("status"),
                (Integer) rs.getObject("risk_score"),
                rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}

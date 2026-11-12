package com.samwan.kibetipay.agent.internal;

import com.samwan.kibetipay.agent.AgentApi;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.wallet.WalletApi;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plain SQL on purpose: tree queries (WITH RECURSIVE) are where SQL shines and JPA struggles.
 * An interviewer favourite: "total portfolio under agent X, including all sub-agents".
 */
@Service
class AgentDirectory implements AgentApi {

    private static final String COLUMNS = """
            id, user_id, parent_id, code, display_name, region, depth, status, lending_limit_minor, created_at
            """;

    private final JdbcClient jdbc;
    private final WalletApi wallets;
    private final AgentProperties props;

    AgentDirectory(JdbcClient jdbc, WalletApi wallets, AgentProperties props) {
        this.jdbc = jdbc;
        this.wallets = wallets;
        this.props = props;
    }

    @Override
    @Transactional
    public AgentView registerTopAgent(NewAgent request) {
        return insert(request, null, 1);
    }

    @Override
    @Transactional
    public AgentView registerSubAgent(String parentUserId, NewAgent request) {
        var parent = requireAgent(parentUserId);
        if (!parent.isActive()) {
            throw new DomainException.RuleViolation("agent_suspended", "Suspended agents cannot recruit");
        }
        if (parent.depth() + 1 > props.maxDepth()) {
            throw new DomainException.RuleViolation("max_depth", "Network depth is limited to " + props.maxDepth());
        }
        // A sub-agent can never be allowed to lend more than the agent above it.
        if (request.lendingLimit().compareTo(parent.lendingLimit()) > 0) {
            throw new DomainException.RuleViolation("limit_above_parent", "Sub-agent limit exceeds parent limit");
        }
        return insert(request, parent.id(), parent.depth() + 1);
    }

    private AgentView insert(NewAgent r, UUID parentId, int depth) {
        if (!wallets.exists(r.userId(), wallets.defaultCurrency())) {
            wallets.open(r.userId(), wallets.defaultCurrency());   // the agent's float wallet
        }
        var id = UUID.randomUUID();
        try {
            jdbc.sql("""
                            INSERT INTO agent (id, user_id, parent_id, code, display_name, region, depth, status,
                                               lending_limit_minor, created_at)
                            VALUES (:id, :user, :parent, :code, :name, :region, :depth, 'ACTIVE', :limit, :at)
                            """)
                    .param("id", id).param("user", r.userId()).param("parent", parentId)
                    .param("code", newCode()).param("name", r.displayName()).param("region", r.region())
                    .param("depth", depth).param("limit", r.lendingLimit().minor())
                    .param("at", Timestamp.from(Instant.now()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DomainException.Conflict("agent_exists", r.userId() + " is already an agent");
        }
        return get(id);
    }

    @Override
    public Optional<AgentView> byUser(String userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent WHERE user_id = :u")
                .param("u", userId).query(this::map).optional();
    }

    @Override
    public AgentView requireAgent(String userId) {
        return byUser(userId).orElseThrow(() -> new NotAnAgentException(userId));
    }

    @Override
    public AgentView get(UUID agentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent WHERE id = :id")
                .param("id", agentId).query(this::map).optional()
                .orElseThrow(() -> new DomainException.NotFound("agent_not_found", "No agent " + agentId));
    }

    @Override
    public List<AgentView> lineage(UUID agentId) {
        return jdbc.sql("""
                        WITH RECURSIVE up AS (
                            SELECT a.*, 0 AS hop FROM agent a WHERE a.id = :id
                            UNION ALL
                            SELECT p.*, up.hop + 1 FROM agent p JOIN up ON p.id = up.parent_id
                        )
                        SELECT %s FROM up ORDER BY hop
                        """.formatted(COLUMNS))
                .param("id", agentId).query(this::map).list();
    }

    @Override
    public List<UUID> subtree(UUID agentId) {
        return jdbc.sql("""
                        WITH RECURSIVE down AS (
                            SELECT id FROM agent WHERE id = :id
                            UNION ALL
                            SELECT c.id FROM agent c JOIN down ON c.parent_id = down.id
                        )
                        SELECT id FROM down
                        """)
                .param("id", agentId).query(UUID.class).list();
    }

    /** Whole network under an agent, for the "my network" screen. */
    List<AgentView> descendants(UUID agentId) {
        return jdbc.sql("""
                        WITH RECURSIVE down AS (
                            SELECT a.* FROM agent a WHERE a.parent_id = :id
                            UNION ALL
                            SELECT c.* FROM agent c JOIN down ON c.parent_id = down.id
                        )
                        SELECT %s FROM down ORDER BY depth, display_name
                        """.formatted(COLUMNS))
                .param("id", agentId).query(this::map).list();
    }

    private static String newCode() {
        return "AG-" + HexFormat.of().withUpperCase().toHexDigits(ThreadLocalRandom.current().nextInt()).substring(0, 6);
    }

    private AgentView map(ResultSet rs, int row) throws SQLException {
        return new AgentView(
                rs.getObject("id", UUID.class),
                rs.getString("user_id"),
                rs.getObject("parent_id", UUID.class),
                rs.getString("code"),
                rs.getString("display_name"),
                rs.getString("region"),
                rs.getInt("depth"),
                rs.getString("status"),
                Money.of(rs.getLong("lending_limit_minor"), wallets.defaultCurrency()),
                rs.getTimestamp("created_at").toInstant());
    }
}

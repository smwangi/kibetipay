package com.samwan.kibetipay.agent;

import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent network: a tree of agents (depth 1) and sub-agents (depth 2+). Agents originate loans,
 * collect cash repayments against their float wallet, and earn commission up their lineage.
 */
public interface AgentApi {

    AgentView registerTopAgent(NewAgent request);

    AgentView registerSubAgent(String parentUserId, NewAgent request);

    Optional<AgentView> byUser(String userId);

    /** The agent behind the given user, or 403-style failure if the caller is not an agent. */
    AgentView requireAgent(String userId);

    AgentView get(UUID agentId);

    /** Self first, then parent, grandparent ... up to the top agent. Used for commission splits. */
    List<AgentView> lineage(UUID agentId);

    /** Self plus every descendant. Used for portfolio roll-ups. */
    List<UUID> subtree(UUID agentId);

    record NewAgent(String userId, String displayName, String region, Money lendingLimit) {}

    record AgentView(UUID id, String userId, UUID parentId, String code, String displayName, String region,
                     int depth, String status, Money lendingLimit, Instant createdAt) {

        public boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }

    final class NotAnAgentException extends DomainException.RuleViolation {
        public NotAnAgentException(String userId) {
            super("not_an_agent", userId + " is not a registered agent");
        }
    }
}

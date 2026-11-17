# Agent network

The agent module owns the network of top agents and sub-agents. [AgentApi](AgentApi.java) exposes agent lookup, registration, lineage and subtree queries to lending. `internal/AgentDirectory` stores an adjacency list in the `agent` table and uses recursive SQL to traverse it.

## Registration and rules

An administrator creates a top agent with `POST /api/admin/agents`. An active agent creates a sub-agent with `POST /api/agents/me/sub-agents`; the current agent is the parent. Registration opens a default-currency wallet for the agent if one does not exist. The agent user ID and generated agent code are unique. Agents have ACTIVE or SUSPENDED status, a region, depth and lending limit.

`app.agent.max-depth` defaults to 2. A suspended parent cannot recruit, and a sub-agent's limit may not exceed its parent's limit. These checks govern the network; lending applies its own exposure checks. Agent registration is not full identity verification or a client-agent consent workflow.

## Queries

`GET /api/agents/me` returns the caller's agent record and `GET /api/agents/me/network` returns descendants. `lineage` includes the starting agent followed by ancestors; `subtree` includes the starting agent and descendants. Lending uses these results for commission splits, cash collection authorization and portfolio roll-ups. The table and indexes are introduced in `V3__agents_and_lending.sql`.

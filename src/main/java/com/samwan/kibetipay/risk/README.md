# Risk

The risk module provides [RiskApi](RiskApi.java), a deterministic rules-based assessment used by transfer and lending workflows. `RiskEngine` evaluates `RiskRule` beans, sums their scores and compares the result with `app.risk.approve-below` and `app.risk.reject-at-or-above`; the default thresholds are 30 and 80. Rules and reasons are defined under `internal/rules/`.

Assessments are persisted in `risk_assessment` and keyed to the request's transfer/subject identifier so redelivery can reuse a stored decision. Results are APPROVE, REVIEW or REJECT. Review decisions are handled by the calling workflow; the risk module does not itself settle money.

An optional LLM explainer supplies analyst-facing text for relevant outcomes; a no-op fallback exists. The model does not determine the decision. `app.ai.enabled`, the configured AI provider and `RiskExplainerConfig` affect which explainer is available. AI notes are best effort; investigate application logs and provider configuration if they are missing. The current rules are heuristics, not a trained fraud model or a substitute for operational review.

See `RiskExplainerConfigTest` for bean-selection coverage and the transfer tests for integration. Rule thresholds and velocity/large-amount settings are in `application.yml` under `app.risk`.

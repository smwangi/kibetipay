# Spending insights

The insights module creates a per-user read model of completed transfers and exposes [InsightsApi](InsightsApi.java). `InsightsProjector` listens to `TransferCompleted`, writing incoming and outgoing `insight_transaction` rows. Duplicate event deliveries are ignored by the projection's conflict handling. It is a reporting view, not a source of wallet balances.

The optional categoriser, enabled by `app.insights.enabled`, runs after an initial 20-second delay and then every 30 seconds. It takes up to 20 uncategorised outgoing transfers per batch and asks the configured chat model for structured categories. Counterparty and note are included in the model input. Failed batches leave rows uncategorised for a later run; model access and privacy requirements should be reviewed before enabling it with real customer data.

`GET /api/insights/me?month=YYYY-MM` returns the caller's SQL-based monthly spending summary. `GET /api/insights/me/narrative?month=YYYY-MM` also asks the chat model to narrate that summary; an empty summary returns a fixed message. The default month uses Africa/Nairobi. Model-generated narrative is not a ledger calculation, and the narrative endpoint can depend on model availability. The table is introduced in V1.

# Mobile money and reconciliation

[MobileMoneyGateway](MobileMoneyGateway.java) defines asynchronous collection and payout requests. The fallback `SimulatedMobileMoneyGateway` produces deterministic simulated provider request IDs; no actual payment is sent. `ACCEPTED` is a request acknowledgement, not proof of settlement. The project does not yet provide a real Daraja adapter, authenticated callbacks, provider status queries, or a provider-to-ledger settlement workflow.

## Manual evidence comparison

The admin-only reconciliation endpoints store separate expected-payment and provider-transaction records. They are manually entered evidence, not verified provider statements:

| Method and path | Request / result |
| --- | --- |
| `POST /api/admin/mobile-money/expected-payments` | `provider`, `reference`, `direction`, `amountMinor`, `currency`; returns `id`. |
| `POST /api/admin/mobile-money/provider-transactions` | `provider`, `providerReference`, optional `expectedReference`, `direction`, `amountMinor`, `currency`, ISO-8601 `occurredAt`; returns `id`. |
| `GET /api/admin/mobile-money/reconciliation` | Up to 500 joined evidence rows, newest first. |

Directions are `COLLECTION` and `PAYOUT`; amounts are positive integer minor units, and currency is a three-letter uppercase code. Examples are in the root README. Provider plus expected reference and provider plus provider reference are each unique; duplicate imports return a conflict. The tables record the authenticated creator/importer and timestamps.

The report matches by exact provider and expected reference. It reports `MISSING_PROVIDER`, `UNMATCHED_PROVIDER`, `MULTIPLE_PROVIDER_TRANSACTIONS`, `DIRECTION_MISMATCH`, `CURRENCY_MISMATCH`, `AMOUNT_MISMATCH`, or `MATCHED`. Direction takes precedence over currency, then amount, when several values differ. A match means only that the two entered records agree: it does not validate their origin, completeness or relationship to a ledger journal. The 500-row limit means the report is not a complete reconciliation of a large book.

`mobilemoney_expected_payment` and `mobilemoney_provider_transaction` are introduced by V5. There is no automatic provider import, suspense allocation, correction UI, transaction lifecycle or money movement in this workflow. `ReconciliationTest` tests comparison rules; PostgreSQL integration verification remains necessary.

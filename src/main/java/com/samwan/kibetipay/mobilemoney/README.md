# Mobile money and reconciliation

[MobileMoneyGateway](MobileMoneyGateway.java) defines asynchronous collection and payout requests. The fallback `SimulatedMobileMoneyGateway` produces deterministic simulated provider request IDs; no actual payment is sent. `ACCEPTED` is a request acknowledgement, not proof of settlement. The project does not yet provide a real Daraja adapter, authenticated provider callbacks or provider status queries.

## Wallet collection lifecycle

`POST /api/mobile-money/collections` accepts `{"msisdn":"254712345678","amount":100.00}` and an `Idempotency-Key` of 8–64 letters, digits or hyphens. The authenticated user must have a wallet. The endpoint returns a collection ID, provider request ID when known, and status; `GET /api/mobile-money/collections/{id}` is scoped to the owner. Reusing a key with different amount, currency or phone number produces a conflict.

The service commits `SUBMITTING` before calling the gateway. An accepted request becomes `PENDING`, a rejected request becomes `REJECTED`, and an exception or malformed response becomes `UNKNOWN`. A crash during submission may leave `SUBMITTING`. Neither uncertain state is automatically resubmitted: the provider might already have accepted the payment. Operators must investigate; automated recovery and a resolution API are not yet implemented.

A matching successful provider result must include the request ID, amount, currency and receipt. Processing locks the collection, checks the result and credits the wallet in the same database transaction as marking the collection `COMPLETED`; an identical result replay does not credit twice. A failed result is marked `FAILED`. Conflicting terminal results are rejected. The receipt is unique per provider. The service entry point is internal: any future real adapter must authenticate the callback and translate provider outcomes before invoking it. No public provider callback is included.

For local development only, `POST /api/admin/mobile-money/simulator/collections/{id}/result` simulates a result. It requires the `dev` profile, the simulator gateway and an authenticated administrator. Its body is `{"providerRequestId":"...","amountMinor":10000,"currency":"KES","success":true,"receipt":"test-receipt"}`. Never treat this endpoint or the simulator as proof that funds were received. The existing direct wallet deposit endpoint is also a development shortcut.

`V7__mobilemoney_collections.sql` creates the collection table. This initial lifecycle covers wallet collections only; payout reservations, real callback verification, provider status queries, automatic reconciliation and suspense resolution remain outstanding.

## Manual evidence comparison

The admin-only reconciliation endpoints store separate expected-payment and provider-transaction records. They are manually entered evidence, not verified provider statements:

| Method and path | Request / result |
| --- | --- |
| `POST /api/admin/mobile-money/expected-payments` | `provider`, `reference`, `direction`, `amountMinor`, `currency`; returns `id`. |
| `POST /api/admin/mobile-money/provider-transactions` | `provider`, `providerReference`, optional `expectedReference`, `direction`, `amountMinor`, `currency`, ISO-8601 `occurredAt`; returns `id`. |
| `GET /api/admin/mobile-money/reconciliation` | Up to 500 joined evidence rows, newest first. |

Directions are `COLLECTION` and `PAYOUT`; amounts are positive integer minor units, and currency is a three-letter uppercase code. Examples are in the root README. Provider plus expected reference and provider plus provider reference are each unique; duplicate imports return a conflict. The tables record the authenticated creator/importer and timestamps.

The report matches by exact provider and expected reference. It reports `MISSING_PROVIDER`, `UNMATCHED_PROVIDER`, `MULTIPLE_PROVIDER_TRANSACTIONS`, `DIRECTION_MISMATCH`, `CURRENCY_MISMATCH`, `AMOUNT_MISMATCH`, or `MATCHED`. Direction takes precedence over currency, then amount, when several values differ. A match means only that the two entered records agree: it does not validate their origin, completeness or relationship to a ledger journal. The 500-row limit means the report is not a complete reconciliation of a large book.

`mobilemoney_expected_payment` and `mobilemoney_provider_transaction` are introduced by V5. There is no automatic provider import, suspense allocation, correction UI, or money movement in this manual comparison workflow. It is not yet linked automatically to the collection lifecycle. `ReconciliationTest` tests comparison rules; PostgreSQL integration verification remains necessary.

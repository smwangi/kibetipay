# Mobile money and reconciliation

[MobileMoneyGateway](MobileMoneyGateway.java) defines asynchronous collection and payout requests. The fallback `SimulatedMobileMoneyGateway` produces deterministic simulated provider request IDs; no actual payment is sent. `ACCEPTED` is a request acknowledgement, not proof of settlement. The project does not yet provide a real Daraja adapter, authenticated provider callbacks or provider status queries.

## Wallet collection lifecycle

`POST /api/mobile-money/collections` accepts `{"msisdn":"254712345678","amount":100.00}` and an `Idempotency-Key` of 8–64 letters, digits or hyphens. The number is 8–15 international digits without the leading plus; individual provider adapters must impose any narrower country or network rules. The simulator does not establish that a payment provider is available in a country. The authenticated user must have a wallet. The endpoint returns a collection ID, provider request ID when known, and status; `GET /api/mobile-money/collections/{id}` is scoped to the owner, and `GET /api/mobile-money/collections` pages through the owner's top-ups (same `limit`/`cursor`/`from`/`to` parameters as the statement) with the phone number masked. A final provider result publishes `MobileMoneyEvents.TopUpSettled` in the same transaction; the notification module turns it into an inbox message, which matters for failures because they leave no ledger entry. Reusing a key with different amount, currency or phone number produces a conflict.

The service commits `SUBMITTING` before calling the gateway. An accepted request becomes `PENDING`, a rejected request becomes `REJECTED`, and an exception or malformed response becomes `UNKNOWN`. A crash during submission may leave `SUBMITTING`. Neither uncertain state is automatically resubmitted: the provider might already have accepted the payment. Operators must investigate; automated recovery and a resolution API are not yet implemented.

A matching successful provider result must include the request ID, amount, currency and receipt. Processing locks the collection, checks the result and credits the wallet in the same database transaction as marking the collection `COMPLETED`; an identical result replay does not credit twice. A failed result is marked `FAILED`. Conflicting terminal results are rejected. The receipt is unique per provider. The service entry point is internal: any future real adapter must authenticate the callback and translate provider outcomes before invoking it. No public provider callback is included.

For local development only, `POST /api/admin/mobile-money/simulator/collections/{id}/result` simulates a result. It requires the `dev` profile, the simulator gateway and an authenticated administrator. Its body is `{"providerRequestId":"...","amountMinor":10000,"currency":"KES","success":true,"receipt":"test-receipt"}`. Never treat this endpoint or the simulator as proof that funds were received. The existing direct wallet deposit endpoint is also a development shortcut.

`V7__mobilemoney_collections.sql` creates the collection table. This initial lifecycle covers wallet collections only; payout reservations, real callback verification, provider status queries and automatic reconciliation remain outstanding. Collections left `UNKNOWN` or `SUBMITTING` have not posted to the ledger, so they need investigation, not suspense.

## Manual evidence comparison

The admin-only reconciliation endpoints store separate expected-payment and provider-transaction records. They are manually entered evidence, not verified provider statements:

| Method and path | Request / result |
| --- | --- |
| `POST /api/admin/mobile-money/expected-payments` | `provider`, `reference`, `direction`, `amountMinor`, `currency`; returns `id`. |
| `POST /api/admin/mobile-money/provider-transactions` | `provider`, `providerReference`, optional `expectedReference`, `direction`, `amountMinor`, `currency`, ISO-8601 `occurredAt`; returns `id`. |
| `GET /api/admin/mobile-money/reconciliation` | Up to 500 joined evidence rows, newest first, with `suspenseItemId` and `suspenseStatus` when the provider transaction has been booked into suspense. |

Directions are `COLLECTION` and `PAYOUT`; amounts are positive integer minor units, and currency is a three-letter uppercase code. Examples are in the root README. Provider plus expected reference and provider plus provider reference are each unique; duplicate imports return a conflict. The tables record the authenticated creator/importer and timestamps.

The report matches by exact provider and expected reference. It reports `MISSING_PROVIDER`, `UNMATCHED_PROVIDER`, `MULTIPLE_PROVIDER_TRANSACTIONS`, `DIRECTION_MISMATCH`, `CURRENCY_MISMATCH`, `AMOUNT_MISMATCH`, or `MATCHED`. Direction takes precedence over currency, then amount, when several values differ. A match means only that the two entered records agree: it does not validate their origin, completeness or relationship to a ledger journal. The 500-row limit means the report is not a complete reconciliation of a large book.

`mobilemoney_expected_payment` and `mobilemoney_provider_transaction` are introduced by V5. Importing and comparing evidence never moves money; the only ledger postings come from the suspense workflow below, and only when an administrator requests them. There is no automatic provider import or correction UI, and the comparison is not yet linked automatically to the collection lifecycle. `ReconciliationTest` tests comparison rules; PostgreSQL integration verification remains necessary.

## Suspense

Suspense holds money that has reached the platform's provider account but cannot yet be attributed to a customer, for example a statement line with a wrong or missing account reference. It keeps the ledger complete without guessing the owner. Money is held in the `system:suspense` wallet (`WalletApi.SystemAccounts.SUSPENSE`), which `PlatformCurrencyInitializer` creates for the operating currency and which cannot go negative. Each item is tracked in `mobilemoney_suspense_item` (V10).

| Method and path | Request / result | Journal |
| --- | --- | --- |
| `POST /api/admin/mobile-money/provider-transactions/{id}/suspense` | `{"reason":"..."}`; opens an item for an imported provider transaction. | `SUSPENSE_OPEN`: Dr `system:float`, Cr `system:suspense` |
| `POST /api/admin/mobile-money/suspense/{id}/allocation` | `{"ownerId":"...","note":"..."}`; credits the identified customer's wallet. | `SUSPENSE_ALLOCATION`: Dr `system:suspense`, Cr customer wallet |
| `GET /api/admin/mobile-money/suspense[?status=OPEN\|ALLOCATED]` | Up to 500 items, oldest first, with provider reference and audit fields. | None |

Rules enforced by [SuspenseService](internal/SuspenseService.java):

- Only `COLLECTION` provider transactions can enter suspense. A transaction whose expected reference matches an expected payment is rejected; resolve it through that payment. A transaction whose provider reference is the receipt of a `COMPLETED` collection is rejected because the customer was already credited.
- One item per provider transaction. Opening again returns the existing item with `replayed: true`. Journal references are `mobilemoney:suspense:open:{providerTransactionId}` and `mobilemoney:suspense:allocate:{itemId}`, so retries never post twice.
- Allocation targets must be existing customer wallets; `system:` accounts are rejected. Allocating again to the same owner is a replay; a different owner is a conflict. The opener, allocator, reason and note are recorded for audit.

The `system:suspense` balance should equal the sum of `OPEN` items and trend towards zero; review the oldest open items regularly. Not yet implemented: refunding unidentified money to the payer (this needs payouts), write-off or unclaimed-funds handling, suspense for amount differences on matched payments, payout reservations, and ageing alerts. Provider evidence is still entered manually, so opening an item relies on the administrator's verification of the statement line. `SuspenseServiceIntegrationTest` covers postings, replays and rejections against PostgreSQL.

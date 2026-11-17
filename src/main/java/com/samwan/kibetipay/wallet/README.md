# Wallet and ledger

The wallet module is the source of truth for wallet balances and monetary journals. Other modules call [WalletApi](WalletApi.java); persistence and controllers are in `internal/`. Amounts use `Money` and integer minor units. The configured default currency is `app.ledger.default-currency` (KES by default).

## Interface

| Endpoint | Purpose |
| --- | --- |
| `POST /api/wallets` | Open the caller's wallet. |
| `GET /api/wallets/me` | Get the caller's balance. |
| `POST /api/wallets/me/deposits` | Credit a wallet using a required `Idempotency-Key`. |
| `GET /api/wallets/me/statement` | List the caller's ledger entries. |

The deposit endpoint directly posts a journal; it is a development/simulation flow, not evidence that M-Pesa collected funds. `WalletApi.post` handles two-account transfers; `postJournal` accepts balanced multi-leg postings. System account identifiers are defined in `WalletApi.SystemAccounts`.

## Invariants and storage

`wallet_journal.reference` is unique. An identical replay returns the original posting receipt; a reference reused with different legs is rejected. Each journal must balance, and entries are append-only. Accounts are locked in deterministic order when posting, and account balances are updated in the same transaction as entries. User accounts cannot become negative; the database has a check as a final safeguard. `wallet_account`, `wallet_journal` and `wallet_ledger_entry` are created by `V1__core_schema.sql`; lending system accounts are added in V3.

For a basic ledger integrity check, the sum of all credits minus debits across `wallet_ledger_entry` should be zero. This checks internal balance only; it does not reconcile with bank or mobile-money statements. The ledger has reversal-related building blocks but no complete external dispute or reversal workflow.

## Verification

Run `mvn test`; the transfer integration test exercises posting and replay using PostgreSQL via Testcontainers. See [architecture](../../../../../../../docs/ARCHITECTURE.md) for the wider transaction flow.

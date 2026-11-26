# Wallet and ledger

The wallet module is the source of truth for wallet balances and monetary journals. Other modules call [WalletApi](WalletApi.java); persistence and controllers are in `internal/`. Amounts use `Money` and integer minor units. `app.ledger.default-currency` is supplied by `KIBETIPAY_CURRENCY` and defaults to KES. Set it to a two-fraction-digit ISO currency before onboarding, e.g. USD or TZS.

The application currently operates a single currency per deployment; it does not choose currency per client or perform FX. V8 identifies the currency of existing financial records without changing them. At startup, `PlatformCurrencyInitializer` records the operating currency and creates missing system wallets for it. A later change to that currency is rejected rather than converting existing money. If a legacy database already contains multiple currencies, V8 fails and requires a deliberate migration. System wallets seeded in KES by earlier migrations remain untouched and unused in a fresh deployment configured for another currency. Review currency-denominated product amounts and risk limits when configuring a new market.

## Interface

| Endpoint | Purpose |
| --- | --- |
| `POST /api/wallets` | Open the caller's wallet. |
| `GET /api/wallets/me` | Get the caller's balance. |
| `POST /api/wallets/me/deposits` | Credit a wallet using a required `Idempotency-Key`. |
| `GET /api/wallets/me/statement` | Page through the caller's ledger entries, newest first (`limit`, `cursor`, inclusive `from`/`to`). Transfer and reversal lines name the counterparty; system accounts are never named. |
| `GET /api/wallets/me/summary` | Money in and out by journal kind and an end-of-day balance series for the last `days` (7–366) days, from the ledger. |

The deposit endpoint directly posts a journal; it is a development/simulation flow, not evidence that M-Pesa collected funds. `WalletApi.post` handles two-account transfers; `postJournal` accepts balanced multi-leg postings. System account identifiers are defined in `WalletApi.SystemAccounts`: float, lending pool, interest/fee/penalty income, commission expense and suspense. `system:suspense` holds received money that is not yet attributed to a customer; the [mobile money module](../mobilemoney/README.md#suspense) posts to it.

## Invariants and storage

`wallet_journal.reference` is unique. An identical replay returns the original posting receipt; a reference reused with different legs is rejected. Each journal must balance, and entries are append-only. Accounts are locked in deterministic order when posting, and account balances are updated in the same transaction as entries. User accounts cannot become negative; the database has a check as a final safeguard. `wallet_account`, `wallet_journal` and `wallet_ledger_entry` are created by `V1__core_schema.sql`; lending system accounts are added in V3. Since V8, `PlatformCurrencyInitializer` creates any missing system accounts, including `system:suspense`, for the operating currency at startup.

For a basic ledger integrity check, the sum of all credits minus debits across `wallet_ledger_entry` should be zero. This checks internal balance only; it does not reconcile with bank or mobile-money statements. The ledger has reversal-related building blocks but no complete external dispute or reversal workflow.

## Verification

Run `mvn test`; the transfer integration test exercises posting and replay using PostgreSQL via Testcontainers. See [architecture](../../../../../../../docs/ARCHITECTURE.md) for the wider transaction flow.

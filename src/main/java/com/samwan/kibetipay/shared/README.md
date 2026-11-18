# Shared kernel

The shared module contains types and infrastructure intentionally visible to all modules. It is marked as an open application module in `package-info.java`; domain modules should otherwise interact through public module APIs and events.

## Components

- `money/Money`: immutable currency and integer minor-unit representation. Use it for monetary operations rather than floating-point arithmetic.
- `money/CurrencyRegistry`: the configured list of ISO currency codes and their JDK-provided minor-unit precision. Set `KIBETIPAY_ENABLED_CURRENCIES` to a comma-separated list; it defaults to the configured operating currency, which must be present in the list. Invalid codes, duplicate codes and codes without defined minor-unit precision are rejected. Registry membership alone does not enable wallet operations, lending products or payment providers for that currency: those flows still use the single operating currency until the multi-currency work is completed.
- `id/Ids`: the one place identifiers are minted. `Ids.newId()` returns a UUIDv7 (time-ordered, RFC 9562) for every primary key; do not call `UUID.randomUUID()` directly. `Ids.newTxnRef()` returns the customer-facing transaction reference (`KP` + 10 Crockford Base32 characters, e.g. `KP7H3QX9M2AB`) stored in `transfer.txn_ref`; it is independent of the primary key and of idempotency keys. Name-based ids that must be reproducible from an external key still use `UUID.nameUUIDFromBytes`.
- `error/DomainException` and `web/GlobalExceptionHandler`: stable business error codes mapped to HTTP Problem Details for not-found, conflict and rule-violation cases.
- `security/`: HTTP Basic authentication, configured users, request-scoped current user and path authorization. `/api/admin/**` requires ADMIN; other `/api/**` requests require authentication. Health, info and Prometheus paths have explicit public rules. This is a development-oriented security setup, not a complete identity or onboarding system.
- `time/TimeConfig`: supplies the application's clock for components that inject it.

Configuration is under `app.security` and `app.ledger`, with development users in `application-dev.yml`. Do not use the seeded credentials outside a local development environment. Changes to common types affect many modules: run `MoneyTest`, `ModularityTests` and the relevant affected module tests.

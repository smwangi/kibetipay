# Transfers

This module accepts wallet-to-wallet transfers, evaluates risk asynchronously and exposes caller-scoped transfer history. Its public integration points are [TransferQueries](TransferQueries.java) and [TransferEvents](TransferEvents.java); implementation code is in `internal/`.

## Flow

1. `POST /api/transfers` validates the recipient, amount, transfer limit and required `Idempotency-Key`. The command saves a `PENDING_RISK` transfer and publishes `TransferRequested` in the same transaction. The response is `202 Accepted` with a location to poll.
2. The asynchronous processor calls `RiskApi`. APPROVE posts the wallet journal using a transfer-specific reference, REVIEW holds the transfer, and REJECT records the rejection.
3. A successful settlement emits `TransferCompleted`, consumed by anomaly, insights and search. Held and rejected events feed search. Modulith's publication registry records listener delivery and can republish incomplete work on restart.

Retries with the same key and request return the same transfer; changing request details under the same key is rejected. Wallet journal idempotency protects the monetary posting on replay. `app.transfer.max-amount-minor` defaults to 10,000,000 minor units.

## HTTP and operations

`GET /api/transfers` lists the caller's transfers; `GET /api/transfers/{id}` returns a transfer visible to that caller. Administrators use `GET /api/admin/transfers/review-queue` and `POST /api/admin/transfers/{id}/approve` or `/decline` for held transfers. All `/api/admin/**` routes require the ADMIN role; other `/api/**` routes require authentication.

The `transfer` table stores lifecycle and idempotency information. Search is an eventually consistent projection, not the source of truth. Test state transitions with `TransferStateMachineTest`; `TransferFlowIntegrationTest` requires Docker for PostgreSQL.

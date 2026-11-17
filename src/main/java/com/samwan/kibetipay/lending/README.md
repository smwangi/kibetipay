# Lending

The lending module manages products, agent-originated loans, assessment, wallet disbursement, repayment schedules, collections, commissions and portfolio reporting. [LendingQueries](LendingQueries.java) and [LendingEvents](LendingEvents.java) are the public cross-module interfaces; domain logic and controllers are in `internal/`.

## Origination and decisions

An agent calls `POST /api/agents/me/loans` with an `Idempotency-Key`, borrower, product and principal. Origination checks the agent, self-lending, product bounds, borrower wallet and open loans. A partial unique index also protects the one-open-loan rule, including defaulted loans. The command records `APPLIED` and publishes `LoanApplied`.

The asynchronous decision saga scores the borrower, checks agent exposure and calls the risk module. A loan can be declined, held `IN_REVIEW`, or disbursed. Administrators inspect `/api/admin/lending/review-queue` and approve or decline under `/api/admin/lending/loans/{id}`. Disbursement locks the loan and posts a journal from the lending pool to the borrower's wallet in the same transaction as activation and the disbursement event. It is an internal wallet credit, not an M-Pesa payout. **A borrower offer-and-acceptance step is not implemented.**

## Repayment and commissions

The schedule is generated on disbursement using the product's flat interest, fee, tenor and instalment count. A borrower repays with `POST /api/loans/{id}/repayments`; an agent or upline records a cash collection with `POST /api/agents/me/collections`, debiting that agent's float wallet. Both require an idempotency key. With the loan locked, payments allocate oldest instalment first, then penalty, fee, interest and principal. Overpayments are rejected; the application does not allocate or refund excess payments. Matching retries return a receipt; changing the loan, payer, channel, amount or currency for a reused reference is rejected.

Commissions are computed on collected interest and fees, split up agent lineage, and credited to agent wallets. `GET /api/agents/me/commissions` lists up to 200 credits with loan and repayment IDs. It is not an external payout statement. Defaults: `app.lending.commission-rate-bps=2000`, split `[60,40]`; other thresholds are in `application.yml`.

## Queries and daily processing

Borrowers use `GET /api/loans/products`, `/api/loans`, and `/api/loans/{id}`. Agents use `/api/agents/me/portfolio`; administrators use `/api/admin/lending/portfolio` and can fund the pool with `/api/admin/lending/pool/fund`.

`LoanDailyJobs` runs at 00:30 Africa/Nairobi and can be invoked at `POST /api/admin/lending/jobs/eod?date=YYYY-MM-DD`. It marks overdue instalments, accrues capped daily penalties and defaults qualifying loans. A `lending_job_run` row prevents another run for the same date; the claim is written before the processing transactions, so an interrupted run can require operator investigation rather than assuming it can be replayed automatically. PAR30 reports outstanding principal associated with instalments overdue more than 30 days.

The `lending_*` tables are created in V3; V4 expands the open-loan index to cover DEFAULTED. That migration requires existing data to satisfy the new uniqueness rule. Receivables are tracked in lending tables rather than a complete chart of accounts. Waivers, provider reconciliation and full loan reversal workflows are not implemented. Calculator tests cover schedules, allocation and commission splitting; the wider integration tests need Docker.

# Implementation backlog

This backlog records the remaining work for multi-currency wallets and foreign exchange. The
order is intentional: implement and verify same-currency operations before enabling FX.

## Current baseline

- KibetiPay currently operates in one configured currency per deployment. The ledger guard
  rejects other currencies and startup rejects changing the established currency.
- The currency registry validates configured ISO codes and exposes the JDK's minor-unit
  precision. Registry membership does not yet enable a currency for transactions or providers.
- `Money` uses integer minor units, respects the currency's fraction digits and rejects mixed
  currency arithmetic. Several APIs, products, limits and reports still assume the operating
  currency and two fractional digits.
- FX, per-client currency choice, cross-currency settlement and provider-backed conversion are
  not implemented. Existing balances and historical records must never be relabeled as another
  currency.

## 1. Currency definitions and amount validation

**Depends on:** the existing registry and `Money` groundwork.

- Define an operational currency catalog, including enabled status, code, fraction digits and
  the policy for changes to currency definitions over time. Keep availability separate from
  provider capabilities and preserve the precision used for historical transactions.
- Replace fixed two-decimal input validation, `/ 100` calculations and KES-specific assumptions
  with currency-aware parsing and formatting. Reject fractional amounts that cannot be
  represented exactly; never silently round a customer input.
- Expose enabled currency metadata to clients without implying that all listed currencies are
  supported by every product or provider.

**Acceptance:** zero-, two-, three- and four-fraction-digit currencies round-trip exactly;
unknown, disabled and undefined-precision codes are rejected; boundary and overflow behavior is
tested. Existing two-decimal records retain their original amounts.

## 2. Per-currency wallets and same-currency transfers

**Depends on:** item 1.

- Allow an authenticated customer to open and select separate wallets by currency. Provision
  system float, pool, income and expense accounts for each enabled currency without modifying
  existing balances.
- Update wallet, deposit, statement and transfer APIs to identify the currency or wallet
  explicitly. Provide a documented compatibility path for existing clients that omit it.
- Enforce ownership, currency and available balance on the server. Transfers between different
  currencies must fail without invoking an FX flow; each journal must balance in one currency.
- Replace the single-currency startup and ledger guards only when the new invariants are enforced
  across the dependent paths. Make retries idempotent with currency included in request identity.

**Acceptance:** one customer can hold distinct currencies; a same-currency transfer changes only
the correct wallets; a currency mismatch and insufficient funds fail without ledger changes;
concurrent and repeated requests cannot overspend or double post.

## 3. Currency-aware lending and agent limits

**Depends on:** items 1–2.

- Assign currency to each product, offer, loan, schedule, fee, penalty, commission and lending
  pool. Verify that borrower and pool wallets match the loan currency before any posting.
- Store agent limits and exposure per currency; do not compare or sum unlike currencies. Ensure
  repayments and commission postings retain their loan currency.
- Preserve accepted offer terms and currency through product changes and disbursement.

**Acceptance:** lending, repayments, penalties and commissions work independently per currency;
a mismatch is rejected before funds move; exposure and limits cannot be bypassed by using a
different currency. Existing historical loans preserve their original currency and terms.

## 4. Currency-safe reports, search, insights and assistant

**Depends on:** items 1–3 for the corresponding data sources.

- Include currency with every monetary result and group or filter aggregates by currency. Review
  portfolio, PAR, commissions, insights, anomaly baselines, search filters and facets, and
  assistant tools and prompts.
- Never produce a combined total by summing minor units from different currencies. If a later
  reporting conversion is offered, show the reporting currency, source amounts, rate source and
  valuation time; preserve the original amounts.
- Version and rebuild search projections as needed, and document any API response changes.

**Acceptance:** mixed-currency fixtures produce separate totals and currency-correct formatting;
no report or search facet silently combines unlike amounts.

## 5. Payment-provider capabilities and reconciliation

**Depends on:** items 1–2; item 3 for loan-specific payouts or repayments.

- Give each adapter explicit supported currencies, countries and operations; validate a request
  before initiating an external side effect. Use mock adapters for tests and keep country-specific
  phone and network rules in the appropriate adapter.
- Record currency and provider identity throughout requests, callbacks, statement import,
  reconciliation and suspense handling. Authenticate real provider callbacks before processing.
- Reserve source funds before external payout; capture only on confirmed success, release only on
  confirmed failure, and retain the reservation for an unknown outcome until it is resolved.

**Acceptance:** unsupported provider combinations are rejected without a ledger posting or
provider request; retries and duplicate callbacks are safe; timeout and unknown outcomes cannot
cause a second payout or premature release of reserved funds.

## 6. FX quotes, acceptance and settlement

**Depends on:** items 1–5, including verified same-currency ledger and reservation behavior.

- Define a rate-provider interface and mock implementation. Store an immutable quote with source
  and destination amounts and currencies, rate, fees, rounding policy, expiry and provider
  reference; bind it to the customer and intended operation.
- Require explicit customer acceptance. Validate ownership, expiry and unchanged terms again
  before settlement. Never perform implicit conversion on a currency mismatch.
- Post linked, auditable legs balanced within each currency against appropriate FX or settlement
  accounts. Track reservations, provider submission, confirmed outcomes, unknown outcomes and
  reconciliation; use idempotency keys and unique provider references throughout.
- Define recovery for expired quotes, provider failures, ambiguous timeouts and partial external
  settlement before enabling any live adapter. Do not automatically retry an ambiguous operation.

**Acceptance:** a customer with only KES cannot spend USD without accepting a valid conversion;
expired or altered quotes cannot execute; concurrent acceptance, duplicate callbacks and
recovery after interruption produce at most one financial effect. Amounts, rate, fees and both
currency legs remain auditable.

## 7. Historical migration and API compatibility

**Depends on:** designs for items 1–5; complete migration before enabling multi-currency writes.

- Inventory every historical financial record and derive its currency only from trustworthy
  recorded data or an explicitly verified deployment setting. Flag ambiguous or mixed legacy data
  for review; do not guess or rewrite monetary values.
- Backfill products, wallets, agent limits, projections and related records with appropriate
  currency references. Preserve original timestamps, amounts, journals and accepted terms.
- Document a staged API transition, migration checks, backup and rollback procedures, and the
  point at which the existing single-currency guard can safely be retired.

**Acceptance:** migration fixtures cover empty, established and ambiguous books; balances and
journal totals are unchanged; ambiguous data blocks or enters an explicit review process; old
clients behave according to the documented compatibility contract.

## 8. Verification and release gates

**Applies throughout; FX release also depends on item 6.**

- Use real PostgreSQL for migration, locking, transaction rollback, concurrency and idempotency
  tests. Mock external payment and FX providers, including failures, delays and duplicate or
  out-of-order callbacks.
- Add property-based tests for exact minor-unit arithmetic, overflow, rounding and balanced
  currency-specific journals. Exercise zero-, two-, three- and four-fraction-digit currencies.
- Verify user isolation, tampered currency and quote details, insufficient funds, simultaneous
  spending, expired quotes, crashes between steps and reconciliation discrepancies.
- Review provider sandbox results, operational controls, monitoring and relevant market-specific
  requirements before enabling real-money integrations. A mock result is not evidence of live
  provider settlement.

**Acceptance:** the test suite demonstrates these invariants and the deployment documents which
currencies, providers and FX operations are enabled. Unsupported operations fail explicitly.

# KibetiPay

A wallet + P2P transfer platform and an agent-network lending ecosystem, with AI
credit/risk scoring, anomaly detection, spending insights and a RAG support assistant. Spring Boot 4.1 modular monolith. Design: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Remaining multi-currency and FX work is tracked in the [implementation backlog](docs/BACKLOG.md).

## Prerequisites
- Java 25 (LTS), Maven 3.9+, and Docker for the local services and Testcontainers integration tests.
- The current Maven configuration includes the Google GenAI starter and requires the relevant model credentials/configuration, including `GEMINI_API_KEY`. The compose file also starts Ollama, but the current Maven dependencies do not include the Ollama model starter.

## Run
```bash
mvn spring-boot:run          # dev profile; Docker Compose starts the local services
```
Set `KIBETIPAY_CURRENCY` before first use to select the deployment's operating currency, for
example `USD` or `TZS`; it defaults to `KES`. This is currently **one currency per deployment**,
not a choice per customer or an FX service. The application supports two-fraction-digit ISO
currencies for these workflows. Startup refuses to switch an established book to a different
currency: changing a setting must not relabel existing balances. New currency deployments must
also review seeded loan products, limits, time zones, and payment-provider availability.

`KIBETIPAY_ENABLED_CURRENCIES` configures the currency registry as a comma-separated list of ISO
codes. It defaults to the operating currency. The registry records known precision for future
multi-currency work; adding a code does not yet enable transactions in that currency.

The configuration includes Ollama model settings; installing and configuring an appropriate model
starter is necessary before treating those settings as an active Ollama integration. Ensure the
selected embedding model is compatible with the configured pgvector dimensions.

### Web app
A customer web app (Next.js, React, TypeScript) lives in [web/](web/README.md). With the backend
running in the `dev` profile:
```bash
cd web
npm install
npm run dev
```
Open the `Local:` URL it prints (normally http://localhost:3000; Next.js picks the next free port
if 3000 is busy) and sign in with one of the dev users listed in `application-dev.yml`.

## Try it
Dev users: borrowers `alice / alice-dev`, `bob / bob-dev`; agents `wanjiru / wanjiru-dev` (top agent) and
`otieno / otieno-dev` (her sub-agent); `admin / admin-dev`. The dev profile seeds wallets, the agent network and
KES 1,000,000 of lending capital. See [api.http](api.http) for every call.
```bash
curl -u alice:alice-dev -X POST localhost:8080/api/wallets
curl -u bob:bob-dev     -X POST localhost:8080/api/wallets
curl -u alice:alice-dev -X POST localhost:8080/api/wallets/me/deposits \
     -H 'Idempotency-Key: dep-0001' -H 'Content-Type: application/json' -d '{"amount": 5000}'
curl -u alice:alice-dev -X POST localhost:8080/api/transfers \
     -H 'Idempotency-Key: trf-00000001' -H 'Content-Type: application/json' \
     -d '{"recipient":"bob","amount":1200,"note":"lunch at Java House"}'
curl -u alice:alice-dev localhost:8080/api/transfers
```

## Test
```bash
mvn test        # unit + modularity tests + Testcontainers integration test (needs Docker)
```
`ModularityTests` also writes module diagrams to `target/spring-modulith-docs`.

## Project layout
```
com.samwan.kibetipay
├── shared      open kernel: Money, errors (ProblemDetail), security
├── wallet      WalletApi + internal double-entry ledger
├── transfer    commands, async saga, queries, REST
├── risk        RiskApi + rules engine + LLM explainer
├── anomaly     per-user z-score detector + alerts
├── insights    projection, batch LLM categoriser, summaries
├── assistant   RAG (pgvector) + tools + chat memory
├── mobilemoney MobileMoneyGateway port + @Fallback M-Pesa simulator
├── agent       agent -> sub-agent network, float wallets
├── lending     products, scorecard, approval saga, repayments, commissions, EOD job, PAR
├── notification  durable in-app lending notifications
└── search      optional OpenSearch read model: event projections, full-text search + facets
```

Each module has a detailed README next to its code:

- [Shared](src/main/java/com/samwan/kibetipay/shared/README.md), [wallet](src/main/java/com/samwan/kibetipay/wallet/README.md), [transfers](src/main/java/com/samwan/kibetipay/transfer/README.md), and [risk](src/main/java/com/samwan/kibetipay/risk/README.md).
- [Agents](src/main/java/com/samwan/kibetipay/agent/README.md), [lending](src/main/java/com/samwan/kibetipay/lending/README.md), [mobile money](src/main/java/com/samwan/kibetipay/mobilemoney/README.md), and [notifications](src/main/java/com/samwan/kibetipay/notification/README.md).
- [Anomaly detection](src/main/java/com/samwan/kibetipay/anomaly/README.md), [insights](src/main/java/com/samwan/kibetipay/insights/README.md), [assistant](src/main/java/com/samwan/kibetipay/assistant/README.md), and [search](src/main/java/com/samwan/kibetipay/search/README.md).

Loan events also create a durable in-app notification inbox. Borrowers can list their latest 100
messages at `GET /api/notifications` and mark a message read with
`POST /api/notifications/{id}/read`. Agents can inspect their latest 200 commission credits,
including the originating loan and repayment, at `GET /api/agents/me/commissions`.
These notifications are in-app records; SMS delivery and external payout reconciliation are not
implemented. Commissions are credited directly to agent wallets and are not external payouts.

Administrators can import expected payments and provider transaction evidence with
`POST /api/admin/mobile-money/expected-payments` and `POST /api/admin/mobile-money/provider-transactions`.
`GET /api/admin/mobile-money/reconciliation` compares the latest 500 rows and flags missing,
unmatched, duplicate-reference and mismatched evidence. Amounts are in minor units. This is a
manual evidence comparison: it does not query a provider, verify imported statements, post to the
ledger, or resolve suspense items. Unique provider references prevent the same statement line from
being imported twice.

For example, an administrator can import an expected KES 100 collection and the corresponding
provider statement line (the reference values are examples):

```bash
curl -u admin:admin-dev -X POST localhost:8080/api/admin/mobile-money/expected-payments \
  -H 'Content-Type: application/json' \
  -d '{"provider":"example","reference":"payment-1","direction":"COLLECTION","amountMinor":10000,"currency":"KES"}'
curl -u admin:admin-dev -X POST localhost:8080/api/admin/mobile-money/provider-transactions \
  -H 'Content-Type: application/json' \
  -d '{"provider":"example","providerReference":"receipt-1","expectedReference":"payment-1","direction":"COLLECTION","amountMinor":10000,"currency":"KES","occurredAt":"2026-01-01T12:00:00Z"}'
curl -u admin:admin-dev localhost:8080/api/admin/mobile-money/reconciliation
```

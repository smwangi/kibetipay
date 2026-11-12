# KibetiPay

A wallet + P2P transfer platform and an agent-network lending ecosystem, with AI
credit/risk scoring, anomaly detection, spending insights and a RAG support assistant. Spring Boot 4.1 modular monolith. Design: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Prerequisites
- Java 25 (LTS), Maven 3.9+, Docker (for Postgres/pgvector, Ollama and Testcontainers)

## Run
```bash
mvn spring-boot:run          # dev profile; Docker Compose starts postgres + ollama
```
First start pulls `llama3.2` and `nomic-embed-text` into Ollama (a few GB, one time).
To use OpenAI or Anthropic instead, swap the `spring-ai-starter-model-ollama` dependency and set the API key via env var.

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
└── search      OpenSearch read model: event projections, full-text search + facets (dev profile only)
```

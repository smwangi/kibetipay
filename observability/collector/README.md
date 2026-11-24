# Production telemetry: the OpenTelemetry Collector

In development the apps export straight to the all-in-one `otel-lgtm` container (see `compose.yaml`).
In production they export to a Collector running [otel-collector.yaml](otel-collector.yaml), which:

- **authenticates every sender.** The OTLP receiver requires `Authorization: Bearer <OTEL_INGEST_TOKEN>`
  (401 otherwise). The API sends it from `OTEL_COLLECTOR_TOKEN` (`application-prod.yml`).
- **samples whole traces after they finish (tail sampling).** It keeps:
  - every trace with an error;
  - every trace slower than 2 s;
  - every trace where money did not simply move: failed, rejected, held for review or outcome unknown. The
    app marks these with the span attribute `kibetipay.outcome` (`TraceOutcome`), because many of them are
    not errors (insufficient funds completes normally);
  - `OTEL_BASELINE_SAMPLE_PERCENT` percent of everything else.

  A head sampler (`management.tracing.sampling.probability: 0.1`) decides when the first span starts and
  would drop 90% of failed-payment traces. So the API records every trace (`probability: 1.0` in the prod
  profile), and the web app should use the default `parentbased_always_on` sampler.
- **protects itself.** `memory_limiter` refuses data under memory pressure rather than being OOM-killed.
  Exports to the backend are queued and retried.

Logs and metrics are not sampled.

## Running it

Image: `otel/opentelemetry-collector-contrib:0.165.0` (tail sampling and bearer-token auth are contrib components).

| Variable | Meaning |
|---|---|
| `OTEL_INGEST_TOKEN` | Shared token the apps must send. Store it in the secret manager. |
| `OTEL_BACKEND_ENDPOINT` | OTLP/HTTP endpoint of the tracing/logging/metrics backend |
| `OTEL_BACKEND_AUTHORIZATION` | `Authorization` header value for that backend |
| `OTEL_BASELINE_SAMPLE_PERCENT` | Share of healthy traces to keep, e.g. `10` |

Point the API at it:

```
MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=https://collector.internal:4318/v1/traces
MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT=https://collector.internal:4318/v1/logs
MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true
MANAGEMENT_OTLP_METRICS_EXPORT_URL=https://collector.internal:4318/v1/metrics
OTEL_COLLECTOR_TOKEN=<same value as OTEL_INGEST_TOKEN>
```

A bearer token over plain HTTP is only acceptable on a private network (a sidecar on localhost, or
a pod network). Anywhere else, terminate TLS in front of the Collector (ingress, service mesh)
or add `tls` to its receivers.

Every replica of a tail-sampling Collector must see all spans of a trace. With more than one replica,
put a `loadbalancing` exporter tier in front that routes by trace id.

## Verified locally

The Collector ran with this config in front of `otel-lgtm`, with the API exporting to it:
- **Authentication:** no token → 401, wrong token → 401, correct token → 200.
- **Tail sampling:** 40 healthy deposits and 5 rejected transfers (an overdraw and an
  Idempotency-Key reuse) were sent. Tempo kept all 5 rejected traces, each tagged
  `kibetipay.outcome=rejected`, and 4 of the 40 deposits (the 10% baseline).

## When the Collector is down

Export runs off the payment path, batched and asynchronous with bounded queues. A down or hanging
Collector loses telemetry, never money. `TelemetryOutageIntegrationTest` (tagged `financial`, so it
runs in the required CI check) points every exporter at a server that accepts connections and never
answers. Transfers still complete well under the 10 s export timeout and the ledger reconciles.

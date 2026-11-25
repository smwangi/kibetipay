# Self-hosted deployment (Docker Compose, this machine only)

Runs the production images (`Dockerfile`, `web/Dockerfile`) with the `prod` profile. It's reachable from this
machine only. Nothing is published on the network:

| Service | Where | Published |
|---|---|---|
| Web app | http://127.0.0.1:3080 | `127.0.0.1` only |
| API (8080), actuator (8081), Postgres | internal compose network | no |
| Grafana (with the observability overlay) | http://127.0.0.1:3081 | `127.0.0.1` only |

The API and web containers run as non-root with a read-only filesystem, all Linux capabilities
dropped and `no-new-privileges`. Every service has a memory limit. The JVM and the Collector size
themselves from it; without one they size from the whole machine, and on a 2 GB VM the kernel
OOM-killed the API.

Grafana requires a login. `otel-lgtm` enables anonymous Admin access by default, so the overlay
turns it off (`GF_AUTH_ANONYMOUS_ENABLED=false`) and sets the admin password from `deploy/.env`.

## First run

```bash
cp deploy/.env.example deploy/.env              # then fill in; generate each secret with: openssl rand -hex 32
cp deploy/users.env.example deploy/users.env    # then add accounts (bcrypt hashes, see the file)
docker compose -f deploy/compose.yaml up -d --build
# with traces, logs, metrics, dashboard and alerts:
docker compose -f deploy/compose.yaml -f deploy/compose.observability.yaml up -d --build
```

Both env files are gitignored. `users.env` holds bcrypt hashes only. To add a user or change a password,
edit it and run `docker compose -f deploy/compose.yaml up -d api` (plus the overlay file if you use it).

New customers open a wallet in the web app on first use. In this setup a top-up is a direct deposit.
The M-Pesa-style top-up (`/api/mobile-money/collections`) talks to the built-in simulator, which only
settles through a dev-profile endpoint. Here those collections stay `PENDING` until a real provider is
configured.

## Operating it

```bash
docker compose -f deploy/compose.yaml ps                 # health of each service
docker compose -f deploy/compose.yaml logs -f api        # ECS JSON logs, one event per line
docker compose -f deploy/compose.yaml down               # stop; data stays in the pgdata volume
```

**Backups.** The ledger lives in the `kibetipay-deploy_pgdata` volume. Take a dump before upgrading:

```bash
docker compose -f deploy/compose.yaml exec -T postgres pg_dump -U kibetipay -Fc kibetipay > kibetipay-$(date +%F).dump
```

Restore it into an empty database with `pg_restore`. Rolling back the code doesn't roll back the
schema: Flyway migrations only move forward.

**Upgrades.** Pull, then `up -d --build`. The API runs pending Flyway migrations on start, and the web app
waits for the API's readiness check.

**Integrity.** The ledger reconciliation runs every 5 minutes. With the observability overlay its result
is on the "KibetiPay · Operations" dashboard, and the alert rules are provisioned. Without the overlay,
an admin can trigger a run from inside the network:

```bash
docker compose -f deploy/compose.yaml exec web node -e "fetch('http://api:8080/api/admin/ledger/reconciliation',{method:'POST',headers:{Authorization:'Basic '+Buffer.from(process.argv[1]).toString('base64')}}).then(r=>r.text()).then(console.log)" 'admin:<password>'
```

## Memory

Limits: Postgres 512 MB, API 768 MB, web 256 MB; with the overlay add the Collector (256 MB) and
`otel-lgtm` (1.5 GB, uses about 1 GB). The base stack runs on a 2 GB Colima VM. The overlay does not:
give the VM at least 4 GB with `colima stop && colima start --memory 4`.

## Verified

On this machine (2 GB Colima VM), base stack:
- **Health:** all services healthy.
- **Sign-in:** works for the bcrypt users, and a wrong password gets 401.
- **Money:** a wallet opened, a deposit landed, and a transfer completed (the recipient got exactly the amount).
- **Proxy:** the web proxy refuses `/admin` paths.
- **Reconciliation:** clean.
- **Exposure:** only `127.0.0.1:3080` is published. The API, actuator and Postgres aren't reachable from the host,
  and the web app isn't reachable from the network.

Overlay:
- **Grafana:** loads the KibetiPay dashboard, answers 401 without a login, and isn't reachable from the network.
- **Not run here yet:** the full overlay (API exporting through the Collector) needs the larger VM. The same
  Collector config was verified end to end in step 6 (`observability/collector/README.md`).

## Not covered

- **Public access.** Exposing this beyond the machine needs TLS, a real identity provider instead of the
  configured users, and rate limiting in front of the API.
- **Real mobile money provider credentials.** Without them, collections run against the simulator.

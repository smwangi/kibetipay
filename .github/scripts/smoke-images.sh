#!/usr/bin/env bash
# Boots the API and web images built by the "Container images" CI job and checks the
# deployment contract: prod profile boots, probes and metrics only on the internal port,
# the API requires authentication, the web app reaches the API, both run as non-root.
set -euo pipefail

fail() { echo "SMOKE TEST FAILED: $*" >&2; docker logs api 2>&1 | tail -80 >&2 || true; docker logs web 2>&1 | tail -40 >&2 || true; exit 1; }
code() { curl -s -o /dev/null -w '%{http_code}' -m 5 "$@" || true; }
wait_for() {   # url, expected status, seconds
  for _ in $(seq 1 "$3"); do [ "$(code "$1")" = "$2" ] && return 0; sleep 1; done
  fail "$1 did not return $2 within $3s (last: $(code "$1"))"
}

docker run -d --name api --network host \
  -e DB_URL=jdbc:postgresql://localhost:5432/kibetipay -e DB_USERNAME=kibetipay -e DB_PASSWORD=ci-only-password \
  -e GEMINI_API_KEY=unused-in-smoke-test -e APP_AI_ENABLED=false -e APP_INSIGHTS_ENABLED=false \
  -e APP_ASSISTANT_INGEST_ON_STARTUP=false \
  kibetipay-api:ci
wait_for http://localhost:8081/actuator/health/readiness 200 180
echo "API ready (prod profile, migrations applied)"

[ "$(code http://localhost:8081/actuator/health/liveness)" = 200 ] || fail "liveness probe"
[ "$(code http://localhost:8081/actuator/prometheus)" = 200 ] || fail "metrics on the internal port"
[ "$(code http://localhost:8080/actuator/prometheus)" != 200 ] || fail "metrics must not be served on the public API port"
[ "$(code http://localhost:8080/api/wallets/me)" = 401 ] || fail "API must require authentication"
[ "$(docker exec api id -u)" = 10001 ] || fail "API must not run as root"
echo "API contract OK"

docker run -d --name web --network host \
  -e KIBETIPAY_API_URL=http://localhost:8080 -e SESSION_SECRET="$(openssl rand -hex 32)" \
  kibetipay-web:ci
wait_for http://localhost:3000/login 200 60
# No users are configured in prod, so sign-in must be refused by the API (401), which proves the
# web server reached it; 502 would mean it could not.
status=$(code -X POST http://localhost:3000/api/session -H 'Content-Type: application/json' \
  -H 'Origin: http://localhost:3000' \
  -d '{"username":"nobody","password":"wrong","next":"/"}')
[ "$status" = 401 ] || fail "web -> API sign-in check returned $status, expected 401"
[ "$(docker exec web id -un)" = node ] || fail "web must not run as root"
echo "Web contract OK"

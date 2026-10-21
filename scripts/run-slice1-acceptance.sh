#!/usr/bin/env bash
# Run only against disposable PostgreSQL/PostGIS + event-store test databases.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${BIZ_KIT_DIR:?Point BIZ_KIT_DIR at the paired biz-kit checkout}"
export MIX_ENV=test
export DISPATCH_E2E_PORT="${DISPATCH_E2E_PORT:-4005}"
export DISPATCH_E2E_URL="http://127.0.0.1:$DISPATCH_E2E_PORT"
export DISPATCH_E2E_CONTROL_KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
backend_log="$(mktemp)"
backend_pid=""
cleanup() { if [ -n "$backend_pid" ]; then kill "$backend_pid" 2>/dev/null || true; fi; cat "$backend_log"; rm -f "$backend_log"; }
trap cleanup EXIT
cd "$BIZ_KIT_DIR/apps/coop_substrate"
mix event_store.create --quiet
mix event_store.init --quiet
cd "$BIZ_KIT_DIR/apps/dispatch"
mix ash.setup --quiet
mix run --no-halt test/acceptance/mobile_server.exs > "$backend_log" 2>&1 &
backend_pid=$!
ready=0
for _ in $(seq 1 60); do
  if grep -Fq SLICE1_ACCEPTANCE_READY "$backend_log"; then ready=1; break; fi
  if ! kill -0 "$backend_pid" 2>/dev/null; then break; fi
  sleep 1
done
[ "$ready" -eq 1 ] || { echo "Backend did not become ready" >&2; exit 1; }
cd "$repo_dir/apps/android-driver"
./gradlew :app:testDebugUnitTest --tests com.dispatch.driver.Slice1BackendAcceptanceTest --rerun-tasks

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
[[ "$(quint --version)" = 0.25.1 ]]
quint typecheck status_sync.qnt
quint typecheck negative/overlap.qnt
quint test status_sync.qnt --seed=1 --max-samples=100
quint test negative/overlap.qnt --seed=1 --max-samples=100
quint run status_sync.qnt --invariant=safety --max-samples=3000 --max-steps=25 --seed=1 --verbosity=1
expect_violation() {
  local output
  output=$(quint run "$1" --invariant="$2" --max-samples=10000 --max-steps=25 --seed=1 --verbosity=1 2>&1 || true)
  [[ "$output" == *"[violation]"* ]] || { echo "$output" >&2; exit 1; }
  echo "counterexample reached: $1 / $2"
}
expect_violation negative/overlap.qnt safety
for witness in noWaitingCancellation noDeliveredUnsettled noTwoEffects; do
  expect_violation status_sync.qnt "$witness"
done
quint verify status_sync.qnt --invariant=safety --max-steps=8 --apalache-version=0.47.2
cd ../auth_intent/lean
[[ "$(lean --version)" == *"version 4.35.0"* ]]
proof_log=$(mktemp)
trap 'rm -f "$proof_log"' EXIT
lake env lean -DwarningAsError=true OutboxPass.lean 2>&1 | tee "$proof_log"
[[ "$(cat "$proof_log")" != *sorryAx* ]]

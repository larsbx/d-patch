#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
test "$(quint --version)" = 0.25.1
quint typecheck auth_intent.qnt
quint typecheck negative/auth_intent_unfenced.qnt
quint test auth_intent.qnt --seed=1 --max-samples=100
quint test negative/auth_intent_unfenced.qnt --seed=1 --max-samples=100
quint run auth_intent.qnt --invariant=safety --max-samples=3000 --max-steps=25 --seed=1 --verbosity=1

# Expected counterexamples must be actual violations, never a tool/runtime error.
expect_violation() {
  local output
  output=$(quint run "$1" --invariant="$2" --max-samples=10000 --max-steps=25 --seed=1 --verbosity=1 2>&1 || true)
  if [[ "$output" != *"[violation]"* ]]; then
    echo "$output" >&2
    echo "Missing counterexample: $1 / $2" >&2
    exit 1
  fi
  echo "counterexample reached: $1 / $2"
}
expect_violation negative/auth_intent_unfenced.qnt safety
for witness in noDiscardedGrant noConfirmedLogout noLocalOnlyLogout; do
  expect_violation auth_intent.qnt "$witness"
done
quint verify auth_intent.qnt --invariant=safety --max-steps=6 --apalache-version=0.47.2

cd lean
[[ "$(lean --version)" == *"version 4.35.0"* ]]
# Reject proof gaps even if Lean reports them only as warnings.
proof_log=$(mktemp)
trap 'rm -f "$proof_log"' EXIT
lake build
lake env lean -DwarningAsError=true AuthIntent.lean 2>&1 | tee "$proof_log"
[[ "$(cat "$proof_log")" != *sorryAx* ]]

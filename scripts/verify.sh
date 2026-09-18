#!/usr/bin/env bash
# Every gate the `lint` and `unit` CI jobs run, in one pass.
#
# This exists because a Sobelow finding reached CI. The sweep before that push
# covered format, tests, Credo, Dialyzer, the invariants, the layout, the
# contract and the migrations — and omitted the one gate that had something to
# say. A checklist held in someone's head omits whatever they are not thinking
# about, and the gate you forget is not correlated with the gate that passes.
#
# Run from the repository root before pushing. The remaining CI jobs need
# Docker, a database, or a browser (integration-postgres, playwright,
# container-build, the Android pair); `--all` includes the database ones.
set -uo pipefail

cd "$(dirname "$0")/.."

# Without this, every gate below "fails" for the same reason and the output
# blames eight checks for one missing tool.
if ! command -v mix >/dev/null 2>&1; then
  echo "mix is not on PATH. Install Elixir ${ELIXIR_VERSION:-1.19.6} / OTP 28.3.3 (see docs/adr/0002)." >&2
  exit 127
fi

integration=0
[ "${1:-}" = "--all" ] && integration=1

failures=()

run() {
  local name="$1"
  shift
  printf '== %s\n' "$name"
  if ! (cd central && "$@"); then
    failures+=("$name")
  fi
}

run "format"      mix format --check-formatted
run "credo"       mix credo --strict
run "sobelow"     mix sobelow --config --exit
run "dialyzer"    mix dialyzer --format short
run "openapi"     mix openapi.check
run "unit"        mix test

if [ "$integration" -eq 1 ]; then
  run "migrations"  mix ash_postgres.generate_migrations --check
  run "integration" mix test --include integration
else
  echo "== migrations, integration (skipped; pass --all with a database running)"
fi

printf '== invariants\n'
bash scripts/check-invariants.sh >/dev/null || failures+=("invariants")

printf '== layout\n'
bash scripts/check-layout.sh >/dev/null || failures+=("layout")

echo
if [ "${#failures[@]}" -gt 0 ]; then
  printf 'FAILED: %s\n' "${failures[*]}" >&2
  exit 1
fi

echo "All local gates pass."

#!/usr/bin/env bash
# Prepares a web session so tests and linters can actually run.
#
# The stack needs PostgreSQL/PostGIS and compiled Elixir dependencies before any
# gate is meaningful. Without this a session reports a green `mix test` that
# only ever skipped the integration tag.
#
# Non-fatal by design: a session that cannot reach hex.pm should still start.

set -uo pipefail

cd "$(dirname "$0")/../.." || exit 0

log() { printf '[session-start] %s\n' "$1"; }

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  log "starting PostgreSQL/PostGIS"
  docker compose up -d postgres >/dev/null 2>&1 || log "could not start postgres"
else
  log "docker unavailable; integration tests will not run"
fi

if command -v mix >/dev/null 2>&1; then
  log "fetching Elixir dependencies"
  (cd central && mix local.hex --force --if-missing >/dev/null 2>&1
   mix local.rebar --force --if-missing >/dev/null 2>&1
   mix deps.get >/dev/null 2>&1 && mix compile >/dev/null 2>&1) \
    || log "dependency fetch or compile failed"
else
  log "elixir unavailable; central gates will not run"
fi

log "ready. 'make help' lists every target."
exit 0

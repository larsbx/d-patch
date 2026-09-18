#!/usr/bin/env bash
# Asserts the repository contains every path Section 20 requires.
#
# ADR-0001 records that the repository root is the monorepo root, so these are
# checked relative to it. Section 20 calls the layout exact; this is what makes
# that claim verifiable rather than aspirational.

set -uo pipefail

cd "$(dirname "$0")/.."

missing=0

require() {
  local kind="$1" path="$2"
  case "$kind" in
    file) [ -f "$path" ] || { printf '[MISSING FILE] %s\n' "$path" >&2; missing=$((missing + 1)); } ;;
    dir)  [ -d "$path" ] || { printf '[MISSING DIR]  %s\n' "$path" >&2; missing=$((missing + 1)); } ;;
  esac
}

# Top level
require file README.md
require file LICENSE
require file Makefile
require file .env.example
require file compose.yaml

# contracts/
require file contracts/openapi.json
require dir  contracts/events
require dir  contracts/fixtures

# apps/
require file apps/android-driver/settings.gradle.kts
require file apps/android-driver/build.gradle.kts
require file apps/android-driver/gradle/libs.versions.toml

# central/
require file central/mix.exs
require file central/mix.lock
require dir  central/config
for domain in accounts fleet operations communications identity audit integrations jobs; do
  require dir "central/lib/dispatch/$domain"
done
for web in controllers components datastar plugs; do
  require dir "central/lib/dispatch_web/$web"
done
require file central/lib/dispatch_web/router.ex
require file central/lib/dispatch_web/endpoint.ex
require dir  central/priv/repo/migrations
require file central/priv/repo/seeds.exs
require file central/priv/static/vendor/datastar.js
require dir  central/priv/static/css
require file central/priv/static/js/maps/participant-map.js
require file central/priv/static/js/maps/google-adapter.js
require dir  central/test

# infra/
require dir infra/containers
require dir infra/reverse-proxy

# docs/
require dir  docs/adr
require dir  docs/runbooks
require file docs/threat-model.md
require file docs/data-retention.md

# Section 20: every deployable directory carries a README with local commands,
# configuration, health checks, test commands, and failure modes.
for deployable in central apps/android-driver infra/containers infra/reverse-proxy; do
  require file "$deployable/README.md"
done

if [ "$missing" -gt 0 ]; then
  printf '\n%d required path(s) missing from the Section 20 layout.\n' "$missing" >&2
  exit 1
fi

echo "Section 20 layout is complete."

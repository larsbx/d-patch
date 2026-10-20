#!/usr/bin/env bash
# Refreshes contracts/biz-kit-dispatch.openapi.json from biz-kit.
#
# The dispatch server now lives in biz-kit (`apps/dispatch`), which generates
# and checks its OpenAPI contract. The field app's hand-written DTOs are held
# to this copy by ContractConformanceTest, so refresh it whenever the server's
# contract changes, and let that test say what the client must follow.
#
#   scripts/sync-dispatch-contract.sh          # fetch from biz-kit main
#   scripts/sync-dispatch-contract.sh --check  # fail if the copy is stale

set -euo pipefail
cd "$(dirname "$0")/.."

source_url="https://raw.githubusercontent.com/larsbx/biz-kit/${BIZ_KIT_REF:-main}/apps/dispatch/contracts/openapi.json"
target="contracts/biz-kit-dispatch.openapi.json"
fetched="$(mktemp)"
trap 'rm -f "$fetched"' EXIT

curl -fsSL "$source_url" -o "$fetched"

if [ "${1:-}" = "--check" ]; then
  cmp -s "$fetched" "$target" || { echo "$target differs from biz-kit; run $0" >&2; exit 1; }
  echo "$target is up to date."
else
  mv "$fetched" "$target"
  echo "Wrote $target"
fi

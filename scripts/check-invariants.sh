#!/usr/bin/env bash
# Textual invariants that no compiler or type checker can enforce.
#
# Several of the specification's MUST NOTs are about what must be absent from
# the source rather than about what the code does. A search is the honest tool
# for those: it fails the moment the forbidden construct appears, instead of
# waiting for a test to happen to exercise the path.
#
# Matching runs over code only, with comments and doc blocks stripped by
# scripts/code-lines.py. Documenting why a vendor type is forbidden must not
# count as using it.
#
# Run from the repository root. Exits non-zero if any invariant is violated.

set -uo pipefail

cd "$(dirname "$0")/.."

violations=0

# check <description> <extended regex> [exclusion regex] -- <path>...
check() {
  local description="$1" pattern="$2" exclude="$3"
  shift 3
  [ "${1:-}" = "--" ] && shift

  echo "== ${description}"

  local hits
  hits=$(python3 scripts/code-lines.py "$@" | grep -InE "$pattern" || true)
  if [ -n "$exclude" ]; then
    hits=$(printf '%s\n' "$hits" | grep -vE "$exclude" || true)
  fi

  if [ -n "$hits" ]; then
    printf '\n[VIOLATION] %s\n' "$description" >&2
    printf '%s\n' "$hits" >&2
    violations=$((violations + 1))
  fi
}

check "Section 21.1: authorize?: false is prohibited outside migrations, seeds, and reviewed maintenance code" \
  'authorize\?:[[:space:]]*false' \
  'priv/repo/(migrations|seeds)' \
  -- central/lib central/test

check "Section 35: no placeholder or deferred authorization and security work" \
  '(TODO|FIXME|XXX|HACK).*(auth|polic|permission|verif|secur)' \
  '' \
  -- central/lib apps

check "Section 27.1: Twilio SDK types, SIDs, TwiML, and ConversationRelay frames must not escape the Twilio adapter" \
  '(CallSid|MessageSid|AccountSid|TwiML|ConversationRelay|X-Twilio-Signature)' \
  '(integrations/comms/twilio|/twilio/)' \
  -- central/lib apps

check "Section 8.2: no generated call control may ring, bridge, transfer, or conference the driver" \
  '<(Dial|Conference)[ />]' \
  '' \
  -- central/lib apps

check "Section 27.6: all operational work shares one priority class; no domain priority, severity, or urgency field" \
  '[^a-z_](priority|severity|urgency)[[:space:]]*[:=][[:space:]]*[^=]' \
  '(oban|Oban|queues|priority_class)' \
  -- central/lib/dispatch apps

check "Section 29.0: Hermes SDK types, session handles, and wire formats must not escape the Hermes adapter" \
  'Hermes' \
  '(integrations/agent/hermes|adapter_registry|config\.ex)' \
  -- central/lib

check "Section 28.1: Google SDK types and identifiers must not escape the Google adapter" \
  '(GoogleMaps|com\.google\.android\.gms\.maps|google\.maps\.)' \
  '(integrations/geo/google|infra/googlemaps|maps/google-adapter\.js)' \
  -- central/lib apps

check "Section 26.1: the Datastar bundle must be served from this origin, not a CDN" \
  'src="https?://[^"]*datastar' \
  '' \
  -- central/lib

check "Section 21.5: Datastar event encoding must be centralized in DispatchWeb.Datastar" \
  'datastar-patch-elements' \
  'dispatch_web/datastar/' \
  -- central/lib

echo
if [ "$violations" -gt 0 ]; then
  printf '%d invariant(s) violated.\n' "$violations" >&2
  exit 1
fi

echo "All invariants hold."

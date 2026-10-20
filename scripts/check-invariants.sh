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

# Section 21.1 exempts migrations, seed scripts, and narrowly reviewed internal
# maintenance code. "Reviewed" has to mean something, so the exemption is spelled
# in the source: each production use must carry a REVIEWED-UNAUTHORIZED comment
# within the few lines above it, which makes every one of them visible in a diff
# and forces a sentence explaining why the bypass is correct.
#
# This check reads raw source rather than going through code-lines.py, because
# the marker is a comment and that script strips comments by design. It skips
# `@doc`/`@moduledoc` blocks itself, so prose explaining the rule does not
# violate it.
#
# Test files are excluded. They construct fixtures directly on purpose; what
# Section 21.1 protects is the running system, and a policy that held only
# because a test bypassed it would fail that policy's own deny test.
echo "== Section 21.1: authorize?: false outside migrations, seeds, and code marked REVIEWED-UNAUTHORIZED"
unreviewed=$(python3 - <<'PYEOF'
import pathlib, re

MARKER = "REVIEWED-UNAUTHORIZED"
# Wide enough that a real justification fits above the line it explains. A
# tighter window would fail the better-explained bypass and pass the tersely
# excused one, which inverts what this check is for.
WINDOW = 14
pattern = re.compile(r"authorize\?:\s*false")
doc_open = re.compile(r'^\s*@(module|type|typed|)doc\s+~?[A-Za-z]?"""')
offenders = []


def in_doc_block(lines):
    """Which lines sit inside a `@doc`/`@moduledoc` heredoc.

    Documenting why a bypass needs a justification must not fail the check that
    asks for one — the same reasoning that sends the other invariants through
    scripts/code-lines.py. This check cannot use that script, because the marker
    it looks for is itself a comment, so doc blocks are skipped here instead.
    """
    inside = [False] * len(lines)
    open_at = None
    for index, line in enumerate(lines):
        if open_at is None:
            if doc_open.match(line):
                open_at = index
                inside[index] = True
        else:
            inside[index] = True
            if line.strip() == '"""':
                open_at = None
    return inside


for path in sorted(pathlib.Path("central/lib").rglob("*.ex")):
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    documentation = in_doc_block(lines)
    for number, line in enumerate(lines, start=1):
        if documentation[number - 1] or not pattern.search(line):
            continue
        context = lines[max(0, number - 1 - WINDOW):number]
        if not any(MARKER in c for c in context):
            offenders.append(f"{path}:{number}:{line.strip()}")

print("\n".join(offenders))
PYEOF
)
if [ -n "$unreviewed" ]; then
  printf '\n[VIOLATION] %s\n' "Section 21.1: authorize?: false without a REVIEWED-UNAUTHORIZED justification" >&2
  printf '%s\n' "$unreviewed" >&2
  violations=$((violations + 1))
fi

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

# Section 34's Slice 1 exit criterion: "no Android feature module or shared Ash
# policy branches directly on a seeded role key". The role keys are data; a
# comparison against one in authorization or feature-selection code is the
# hard-coding the whole capability model exists to avoid. The seed manifest is
# where they are allowed to appear, because that is what defines them.
check "Section 23.2: no shared policy or feature module may branch on a seeded role key" \
  '"(DRIVER|ADMIN|DISPATCHER|SHIPPER|RECEIVER|BROKER)"' \
  '(access/seed_manifest\.ex|access/roles/|accounts/role_definition\.ex)' \
  -- central/lib/dispatch/access central/lib/dispatch_web apps

# The literal check above cannot see `document.role.key == selected`. On the
# client the role key reaches code only through the capability document, so
# a feature module that reads it at all is one step from branching on it.
# ADR-0009: features come from `features`, presentation from `role.label`.
check "Section 23.2: Android feature modules must not read the role key" \
  'role\.key\b' \
  '' \
  -- apps/android-driver/feature

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

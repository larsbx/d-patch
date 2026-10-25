#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in driver|courier) person="$1" ;; *) exit 2 ;; esac
repo="$(cd "$(dirname "$0")/.." && pwd)"
trace="${RUNNER_TEMP:-/tmp}/acceptance-trace.log"
export PGPASSWORD="${PGPASSWORD:-postgres}"
adb logcat -c
python3 "$repo/scripts/observe-android-acceptance.py" > "$trace" 2>&1 &
observer=$!
trap 'kill "$observer" 2>/dev/null || true; wait "$observer" 2>/dev/null || true' EXIT
cd "$repo/apps/android-driver"
./gradlew :app:connectedDebugAndroidTest -Pdispatch.authProvider="$person" \
  -Pandroid.testInstrumentationRunnerArguments.serverObserver=true --no-daemon

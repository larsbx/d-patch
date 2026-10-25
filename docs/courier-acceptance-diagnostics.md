# Courier acceptance diagnostics — 2026-10-25

Tracked in larsbx/biz-kit#125; client repair PR #30, paired server PR larsbx/biz-kit#126.

## Evidence and limits

Reported client: `4b8d6fcd8ebc8a4c53eef2aa03bb1cad14696a0a`.
Diagnostic client: `6abd92bd02b4a0ac3aff7f2207ebd93765f2c4b8`.
Server source: `38c7efc237943a5600d3a0e3083c97f8ecef5758`; workflow-only diagnostic commit `18db5a45c4c2b5f30c9f1584001416bd8a6c00b3`.

[Diagnostic run 40232736121](https://github.com/larsbx/biz-kit/actions/runs/40232736121) passed three independent courier jobs, three driver jobs and browser acceptance. The original RetryAt and 401 expectations were preserved. Neither historical failure reproduced in those six mobile jobs: their original root causes remain unestablished.

All three courier traces show the old helper returning from network restoration with `active=false`, followed by ConnectException on the first online sync. Their logout calls attempted a reused connection, then a new connection, received 204 and were followed by 401. This demonstrates a missing readiness barrier and connection recovery; it does not prove that reused connections caused either historical assertion failure. No server revocation defect was demonstrated.

## Changes and preserved guarantees

- The test waits for the requested Android default-network state **and** actual read-only HTTP outcomes at both stack services through existing pools and a fresh pool. There is no pool eviction, status-response substitution or logout retry. A readiness timeout fails acceptance and still attempts network restoration.
- The original offline RetryAt expectation remains. Both local rows must stay PENDING; after restoration both must be ACKNOWLEDGED, with LOADING current.
- Hosted acceptance uses `scripts/run-android-stack-acceptance.sh driver|courier`. Its host observer reads only aggregate counts in the isolated fixture database. Acknowledgements travel over adb while the emulator keeps its network state unchanged: zero server effects during the offline interval, exactly two effects after successful delivery and replay of the identical signed events. Never point this observer at a shared or production database.
- The test retains production background work and logs its states. It does not disable background synchronization to obtain a pass.
- `AuthSession.signOut()` still clears local credentials immediately, then makes one best-effort remote attempt. Its nullable AuthResult reports that attempt; null means no local session. Unreachable or a refusal never confirms remote revocation, never restores the local session and does not retain the token for a hidden retry.
- Acceptance requires confirmed logout, immediate local removal, then exact HTTP 401 with the previous token. The server's synchronous durable-deletion boundary remains unchanged.
- HTTP diagnostics contain monotonic times, call sequence, fixed operation categories, connection reuse, status codes and exception **classes**. No URLs, bearer tokens, cookies, headers, bodies, principal values or exception messages are logged. The random observer nonce is only a test-handshake identifier.

## Deterministic coverage and environment

AuthSession regressions gate a delayed remote response to prove local removal precedes it, distinguish refused/unreachable delivery and forbid implicit retained-token retries. Four host-observer unit checks reject offline acceptance, duplicate business effects and invalid interval ordering.

Local Python checks and shell syntax validation are runnable without Android. This work container has no Android SDK or KVM; a local Gradle attempt also failed at distribution download with Network is unreachable. Emulator acceptance is therefore a hosted-run result, not a local result.

The initial d-patch CI run also exposed an independent pre-existing central dependency-audit failure (Ash/AshPostgres advisories). It is separate from mobile acceptance and must not be waived by this repair.

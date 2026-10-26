# Status synchronization ownership

The status outbox implementation is byte-identical between original client
`4b8d6fcd8ebc8a4c53eef2aa03bb1cad14696a0a` and investigation baseline
`a4d46a284ae19b0cdc8e80e74e57989f1fb5f742` (StatusOutbox.kt SHA-256
`67efc35cd544457b8ab39fd2f8d6a6931a525c7bd1adb7deb254bd7894f3fb9c`).
It allowed concurrent passes to snapshot and submit the same pending rows.

## Demonstrated interleaving

The gated Kotlin baseline in commit `a5911a7009f5ab79d17bb1791d11a9d707a9022f`
failed with ComparisonFailure in [run 40281812181](https://github.com/larsbx/d-patch/actions/runs/40281812181),
job 121436728323. Pass A generated an AT_PICKUP response and waited. A new
LOADING event was declared. Pass B submitted both pending rows and cached
LOADING. Releasing A then overwrote that newer observed answer with AT_PICKUP.
Both rows were acknowledged: server idempotency alone cannot protect this cache.

The first diagnostic attempt used runTest's virtual clock around Room background
work and failed with TimeoutCancellationException instead. That attempt is not
causal evidence. The corrected baseline uses runBlocking with bounded response
gates. No arbitrary sleeps establish the interleaving.

## Repair and implementation checks

A coroutine Mutex belongs to the shared DispatchDatabase, rather than to a facade.
A sync pass acquires it before reading due rows and holds it through its final
pending-outcome read. Waiting callers reread rows after acquiring ownership.
Declarations retain their short Room transaction and remain writable during HTTP.
There is no database transaction held open across a network call.

The final regression uses an undispatched waiting caller and phase observations
(WAITING, STARTED, FINISHED) to require that it has not entered the pass while the
first response is held. After release it must submit only the new row, retain
LOADING and leave both rows acknowledged. Separate gates cover cancellation of a
waiting caller without releasing another owner, and cancellation of the owner
followed by an exact-byte retry of the still-pending declaration. Mutex cleanup
uses structured coroutine cancellation; no hidden logout or transport retry is added.

The scope is one process and one DispatchDatabase instance. Independently opened
Room instances or separate processes are not coordinated by this mutex. Production
Hilt provides a singleton database. Transport cancellation may take time to return;
the mutex is released when its enclosing coroutine exits, not when an OS request
is guaranteed to have stopped. Server idempotency remains necessary for uncertain
or cancelled delivery. Per-event backoff and all existing assertions are preserved.

## Formal checks and limits

Run `bash spec/status_sync/check.sh` with the existing pinned toolchains:
Quint 0.25.1, Apalache 0.47.2, Java 17 and Lean 4.35.0.

* Quint: two callers and two immutable event IDs; three deterministic positive
  traces, one explicit overlapping-pass counterexample, 3,000 seeded samples of
  up to 25 transitions and exhaustive bounded safety through eight transitions.
  Required witnesses cover waiting-caller cancellation, delivered-but-unsettled
  rows and both server effects. The negative control must report an actual
  violation; a tool error cannot satisfy it.
* Lean: `../auth_intent/lean/OutboxPass.lean` proves single-owner acquisition,
  non-owner cancellation preserving ownership, and an invariant over arbitrary
  finite histories: the cached abstract version equals the greatest observed
  response version and does not exceed the server version. Printed dependencies
  are only standard propext and Quot.sound; source is checked with warnings as
  errors and without proof gaps.

These are separate abstract encodings, not a mechanically verified Kotlin/Room
refinement. The model summarizes a pass's ordered responses with a causal version;
production status strings and device sequences are not server version numbers.
It assumes nondecreasing authoritative server versions and structured ownership
(no detached response handler can settle rows after leaving the mutex). Quint's
server-effect set assumes server idempotency; it does not prove that implementation.
Neither model proves HTTP reachability, global cache freshness, liveness, fairness,
cryptography, disk durability or the cause of the historical courier failures.

The historical artifact showed status requests across a supposed offline boundary
but lacked the actual SyncOutcome and overlap trace. This newly demonstrated race
is relevant to background/manual synchronization; it does not establish that either
historical failure exercised it. Keep biz-kit#125 open. Existing independent server
observer checks, signed replay checks and exact post-logout 401 remain required.

Local Lean still needs the runtime-only own-PID readlink workaround documented in
`../auth_intent/README.md`; hosted CI uses the official digest-checked release
without it. Android validation uses hosted emulators because this container has
no Android SDK/KVM. No assertion or gate is waived for an environment limitation.

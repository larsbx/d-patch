# Authentication intent fencing

This is a small proof-driven repair of a demonstrated credential race. It does
not establish the root cause of the historical courier failures in biz-kit#125.

On baseline `b44d02011e5248d2286f3e41a6d4cc5abe6ad26f`, `complete` could
consume a redirect, await `/auth/me`, and install its response after `signOut`
had cleared credentials. The gated Kotlin regression failed on the unchanged
implementation in [run 40239191865](https://github.com/larsbx/d-patch/actions/runs/40239191865),
job 121307138367 (47 tests, one failure). No emulator timing is needed to expose it.

## Obligations and implementation bridge

| Abstract transition or claim | Kotlin boundary and regression |
| --- | --- |
| `begin` creates a fresh ticket | Successful persisted `begin`, intent rotation and record snapshot share the session monitor. A failed write does not supersede an existing intent. |
| `issue` may occur after cancellation | The token exchange runs outside the monitor. The late-grant test gates its response, signs out, then requires skipped principal lookup and exactly one cleanup request. |
| `signOut` advances the barrier and clears local state | `endLocal` rotates identity, removes credentials and updates `signInRequired` in one synchronized section, before remote I/O. |
| `finish` admits only the current ticket | `admit` compares opaque identity and persists credentials under the same monitor. The delayed-principal test reproduces the original red trace. The two-sign-in test requires the newer token to survive and only the older token to be logged out. |
| Discarded grants request cleanup | `Superseded(remoteLogout)` reports one best-effort delivery attempt, including refusal. No token is retained for an implicit retry. `SignInFlowTest` requires zero capability refreshes and zero sync requests. |
| `deliver` and `confirm` are distinct | Server invalidation precedes acknowledgement in the model. A local empty vault is not evidence of either. Existing logout tests and cross-repository exact-401 acceptance stay enforced. |

The bridge is reviewable correspondence and deterministic implementation tests,
not a mechanically verified refinement from Kotlin to either model. The models
collapse pending creation/redirect consumption into one ticket and assume fresh,
distinct server grants, one process-local `AuthSession` per vault, and serialized
local transitions. Production uses object identity, so no numeric counter can wrap.
Process death ends its coroutines; a restarted pending sign-in captures the new
session instance's intent. Storage refusal semantics remain covered by existing
tests. Models do not prove persistence, HTTP behavior, token uniqueness, replay
cryptography, offline synchronization, or coroutine cancellation cleanup.

## Checks and exact scope

Run `bash spec/auth_intent/check.sh` with Quint **0.25.1**, Java 17 and Lean
**4.35.0** on PATH. CI downloads the official Lean archive and verifies its
published SHA-256; the Lean project has no third-party dependencies.

* Quint: deterministic traces, 3,000 seeded samples of up to 25 transitions,
  and exhaustive bounded safety through **six transitions** using Apalache
  **0.47.2**, over three possible ticket indices. An unfenced negative control
  must produce an actual safety violation. Reachability witnesses require
  discarded grants, confirmed logout and issued-but-unrevoked remote grants.
  A tool error cannot satisfy a negative control.
* Lean: kernel-checked induction over arbitrary finite histories proves local
  admission records the matching intent and confirmed revocation implies server
  invalidation. Epoch monotonicity proves a ticket captured before sign-out
  cannot replace local credentials after **any finite intervening history**,
  including newer successful sign-ins. Concrete examples prove that local
  removal does not imply revocation and revocation does not imply acknowledgement.
  `#print axioms` exposes theorem dependencies; there are no proof gaps or
  additional axioms (only standard `propext` and `Quot.sound`).

The Lean and Quint definitions are separate encodings; their equivalence is not
proved. Neither finite Quint exploration nor these abstract Lean theorems prove
the whole Android program safe. Gated Kotlin regressions and hosted acceptance
are required implementation evidence.

## Local runtime limitations

This container lacks an Android SDK/KVM. Its filesystem rejects numeric
`/proc/<own-pid>/exe` lookups used by Lean startup while allowing the equivalent
`/proc/self/exe`. Local Lean runs use a runtime-only readlink shim mapping exactly
that path; it changes no proof checking. Quint's automatic archive extraction
also hits an ownership error here, so the unchanged Apalache release was extracted
with `tar --no-same-owner`. Neither workaround is committed or used in hosted CI;
normal hosted proof and Android checks must pass before merge.

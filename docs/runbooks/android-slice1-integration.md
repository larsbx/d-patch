# Android Slice 1 identity, enrolment and acceptance

The backend is biz-kit `apps/dispatch`; no server code is added to d-patch's
legacy central mirror. Use the paired backend PR and its
`docs/architecture/2026-10-21-android-dispatch-identity.md` for the exact provider,
principal/account mapping and tenant/ledger scope configuration.

## Use

1. Open Android and choose **Sign in**. The system browser authenticates through
   the configured BizKit.Identity provider. The app checks callback state and
   exchanges a single-use PKCE code for a short-lived revocable local session.
2. Choose one active role. Capabilities are still obtained from the canonical
   signed document pinned by ADR-0009; selecting a role never grants authority.
3. Choose **Request device access**. Give the displayed code to the existing
   administrator enrolment workflow (`mix coop.enrol CODE` in biz-kit).
4. Choose **Check device approval**. Only an active approved key can register an
   installation and enable the status screen. A pending request supplies no key
   authority. No provider secret, grant approval key or server signing key is
   present in Android.
5. Declare a status online or offline. Room stores the subject, device, original
   role, sequence and signature with the event in the same transaction. On
   reconnection, the worker sends the same body and idempotency key. A newer
   sequence never overtakes an older deferred event.

**Sign in again** renews an expired session through the browser. Signing out
removes credentials and the selected role; it retains each account's keys and
signed outbox history. Other accounts cannot view or deliver that history.
Revoked roles or device grants return explicit rejection, including on duplicate
redelivery; lost-device revocation uses the existing biz-kit device tools.
A new installation/key requires new enrolment. Existing queued events are never
re-signed under a replacement key. Keystore loss or app reinstallation requires
re-enrolment; backup/transfer is already disabled by the application.

Ed25519 seeds and session credentials use the project's pinned AndroidX encrypted
preferences and a Keystore-backed master key. This provides encrypted custody on
API 29+, not hardware-backed Ed25519 attestation. The shared canonical test vector
is produced by the backend Rust encoder and checked in Kotlin.

## Actual backend acceptance lane

Use disposable PostgreSQL/PostGIS and event-store test databases. The runner
resets ledger fixtures and creates test accounts; never point it at production.
Both repositories must have their normal Elixir/Android dependencies installed.

```bash
BIZ_KIT_DIR=/absolute/path/to/biz-kit scripts/run-slice1-acceptance.sh
```

The lane starts `apps/dispatch/test/acceptance/mobile_server.exs` in `MIX_ENV=test`
and runs `Slice1BackendAcceptanceTest` against its HTTP endpoint. The provider is
a deterministic test provider. Identity sessions, enrolment ledger, administrator
approval, role resolution, signed event admission, PostgreSQL and Room are real.
Control endpoints exist only in that test script, require an ephemeral key and
are never compiled into a production release.

Covered: sign-in and registration; offline queue; synchronization; lost response
and duplicate redelivery without a second event; invalid signature; revoked key
including duplicate replay; revoked role; logout; persistent canonical payload.
The test is skipped in ordinary unit runs unless `DISPATCH_E2E_URL` is supplied;
the dedicated workflow supplies the backend and must pass before claiming
cross-repository acceptance.

Run Android unit checks with `./gradlew testDebugUnitTest` and lint with
`./gradlew lintDebug` in `apps/android-driver`. Backend identity/unit/acceptance
checks and exact environment limitations are recorded in the paired PRs.
A real configured provider, browser callback and handset/emulator acceptance run
must also be performed in the deployment environment. This automated lane does
not claim to have exercised WorkOS or Android's external browser UI.

The installation identifier is stable across account and role selection. The
existing Dispatch registry allows one active participant/tenant binding for that
installation; switching roles never generates another installation identifier
to evade that constraint. A different participant/tenant requires the existing
retirement/re-enrolment process for a fresh installation.

Before signing an enrolment challenge, Android applies the browser client's
existing own-enrolment guard: a closed `CoopDeviceEnrolmentV1` statement, its
complete public key, authenticated platform principal and operator role. Command
fields and other roles are refused before any proof signature is submitted.

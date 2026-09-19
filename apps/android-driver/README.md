# Field application

The role-aware Android client. `DRIVER` is the initial role profile, not a
hard-coded identity type (§1): features are enabled from a signed server
capability document, never from an `if role == DRIVER` check (§23.2).

## Local commands

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest    # the android-unit gate
./gradlew lintDebug            # the android-lint gate
```

Requires an Android SDK. Point at it with `local.properties`
(`sdk.dir=/path/to/android-sdk`) or `ANDROID_HOME`.

## Configuration

| Setting | Where | Notes |
| --- | --- | --- |
| Application ID | `app/build.gradle.kts` | `com.dispatch.driver` is a placeholder; see ADR-0003 |
| `minSdk` | `gradle/libs.versions.toml` | 29, fixed by §19.1 |
| API base URL | `DISPATCH_API_BASE_URL` or `-PdispatchApiBaseUrl=...` | Debug defaults to `http://10.0.2.2:4000/` for the emulator. Release startup requires an explicitly configured HTTPS URL. |
| Debug session bootstrap | `DISPATCH_DEV_BEARER_TOKEN`, `DISPATCH_DEV_ROLE_ASSIGNMENT_ID`, `DISPATCH_DEV_CAPABILITIES` | Debug builds only. These values exercise the slice; they grant no server authority. Release builds compile these fields empty. |
| Maps key | not provisioned | §28.3 restricts it by application ID and signing certificate, which ADR-0003 blocks until the ID is decided |

Authenticated session state is stored through `core:auth` in
Keystore-backed encrypted preferences. The debug bootstrap is only a local
handoff until the OIDC/PKCE UI writes the same store. The queued status row
captures the role-assignment ID at declaration time; switching the active role
later does not rewrite the upload context. The central service revalidates
token ownership, role-assignment validity, scope, and capability on mutation.

## Health checks

The app has no health endpoint. Its equivalents are observable states:

- The location foreground-service notification is present whenever background
  tracking is running, and exposes `Stop sharing` (§25.3).
- `ELD notifications disconnected` appears when notification access is revoked,
  and revoking it never changes driver status (§25.4).
- Unsent events remain in the Room outbox and survive process death (§25.2).

## Test commands

```sh
./gradlew testDebugUnitTest                        # everything
./gradlew :core:model:testDebugUnitTest            # one module
```

## Failure modes

| Symptom | Cause |
| --- | --- |
| Location stops unexpectedly | Consent revoked, or the assignment ended; §25.3 cancels callbacks synchronously |
| Status submitted offline does not appear | Expected: it is queued and syncs once, with a monotonic device sequence (§25.2) |
| Approval control unavailable | Driving mode is active; §25.5 forbids completing an approval while driving |
| A rejected event shows a corrective action | Server rejection marks the local item `REJECTED` and states the exact fix (§25.2) |

## Module layout (§25.1)

```
app                    the installable application
core:model             canonical types; no Android or vendor SDK
core:network           generated transport DTOs over Retrofit/OkHttp
core:database          Room offline outbox (§25.2)
core:auth              OIDC + PKCE, installation key, BiometricPrompt (§§23.1, 25.6)
core:designsystem      Compose theme; WCAG 2.2 AA (§26.7)
feature:*              home, status, map, inbox, approvals, settings
service:location       foreground location service (§25.3)
service:notifications  ELD NotificationListenerService (§25.4)
infra:googlemaps       the only module that may reference Google Maps types (§28.4)
infra:face             containment boundary for a future FaceVerifier (§25.6)
```

`infra:*` modules are the vendor-containment boundary. A Google or face-provider
type that escapes one fails the `lint` gate's invariant check.

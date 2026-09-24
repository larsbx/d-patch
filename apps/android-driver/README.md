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
| API base URL | `BuildConfig.API_BASE_URL` | Debug: `http://10.0.2.2:4000/` (the host's `make up`, cleartext to that host only). Release: `-Pdispatch.apiBaseUrl=https://…`, required. No provider credential ships in the client (§3.1) |
| Capability key | `BuildConfig.CAPABILITY_PUBLIC_KEY_PEM` | ADR-0009. Debug pins `infra/capability-signing/development.pub.pem`. Release: `-Pdispatch.capabilityPublicKeyFile=…`, required; the development key is refused |
| Maps key | not provisioned | §28.3 restricts it by application ID and signing certificate, which ADR-0003 blocks until the ID is decided |

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
| "Sign in to declare your status" | No session. `core:auth` (§23.1) is not built yet, so this is currently always the case |
| Status controls absent for a role | The capability document does not list `status`, or lists it without `status.declare.self`; the screen never decides from the role |
| "Offline: showing the choices your role had…" | The stored capability document is past its expiry and could not be refreshed; it is still verified (ADR-0009) |
| Release build fails in `checkReleaseConfiguration` | `dispatch.apiBaseUrl` or `dispatch.capabilityPublicKeyFile` is unset, not HTTPS, or names the development key |

## Offline status (§25.2)

`pending_events` carries §25.2's columns plus `role_assignment_id` (a queued
event is sent under the authority it was declared under, not whichever role is
selected later) and `rejection_code` / `corrective_action`. Rows are kept after
acknowledgement because the next device sequence is derived from the highest
one; any future pruning must keep that row. `cached_driver_state` holds only
what the server confirmed — what the screen shows as current is derived from it
and the outbox together, so an optimistic value is never mistaken for a
confirmed one. `cached_capability_document` holds the signed token and is
verified again on every read.

## Module layout (§25.1)

```
app                    the installable application
core:model             canonical types; no Android or vendor SDK
core:network           /v1 client over Retrofit/OkHttp; DTOs held to contracts/openapi.json by ContractConformanceTest
core:database          Room offline outbox (§25.2); schemas exported under core/database/schemas
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

# Threat model

Scope: the central Ash/Phoenix service, the Datastar portal, the Android field
application, and the provider adapters for communications, geospatial, and agent
runtimes.

Section 16 closes this document in Slice 8. What follows is the standing frame
and the trust boundaries that already exist; each slice adds the threats it
introduces and the control that answers them.

## Assets

| Asset | Why it matters |
| --- | --- |
| Participant-declared status | An AI inference must never overwrite or impersonate it (§1) |
| Precise location | Field- and time-scoped; consent is revocable (§6.3) |
| Message bodies and call turns | Encrypted at the application layer (§22.4) |
| Negotiated rate and load commercial terms | Excluded from broker, shipper, and receiver surfaces (§4.2) |
| Biometric templates and raw face media | Never leave the device; never reach the server (§7.2) |
| Provider credentials | Never on a client (§3.1) |
| The audit chain | Hash-linked and append-only (§22.3) |

## Trust boundaries

1. **Android ↔ central service.** Android owns consent, capture, and local
   biometric UI. The server owns durable state and every authorization decision.
   A device may assert what it observed; it may never assert what it is allowed
   to do.
2. **Central service ↔ agent runtime.** The runtime receives a per-turn tool
   allowlist and no database credential (§29.0). Prompt text cannot grant
   authority (§9).
3. **Central service ↔ communications provider.** Ingress is authenticated by
   provider signature against the exact public URL before the body is parsed
   (§27.2).
4. **Central service ↔ geospatial provider.** Vendor identifiers live in
   `geo_provider_refs`, never as domain identifiers (§28.2).
5. **Browser ↔ central service.** SSE content is field-authorized before it is
   encoded; the browser never filters (§24.5).

## Threats addressed by controls already in place

| Threat | Control | Where |
| --- | --- | --- |
| A caller uses urgency language to reach the driver's handset | `AnswerPlan` carries no destination number; the invariant check rejects `<Dial>` and `<Conference>` | `Dispatch.Comms.AnswerPlan`, `scripts/check-invariants.sh` |
| An operator ships a production deployment with a dead or fake provider | Startup rejects development-only adapters and missing secrets | `Dispatch.Config` |
| A misconfigured TTL silently weakens break-glass or a face challenge | Bounds checked at startup and covered at both ends by tests | `Dispatch.Config`, `Dispatch.ConfigTest` |
| Generic `BiometricPrompt` success is presented as a face match | The two are distinct enum constants and no other label may mention a face | `VerificationMethod`, `VerificationTest` |
| A vendor type leaks out of its adapter and couples the domain to a provider | Textual invariant checks over code with comments stripped | `scripts/check-invariants.sh` |
| A log call adds a sensitive field | The formatter emits only allowlisted metadata keys | `Dispatch.LogFormatter` |
| Coordinates reach the DOM, analytics, or a client log | The map element holds them in memory only; asserted by test | `participant-map.js`, `web/test/` |
| A biometric template survives a device backup | Cloud backup and device transfer are excluded wholesale | `data_extraction_rules.xml` |
| A forged or edited capability document enables a surface the role lacks | Ed25519 over the received canonical bytes, key pinned at build time, verified again on every read from storage; features exceed no capability server-side | ADR-0009, `CapabilityDocumentVerifier`, `CapabilityStore`; biz-kit `Dispatch.Access.CapabilityDocument` |
| A capability document is replayed under another login or role | Bound to `sub` and `role_assignment_id`; the client rejects a mismatch | `CapabilityDocumentVerifierTest` |
| Bytes the platform signed for another purpose pass as a capability document | Claims carry `schema: DispatchCapabilityDocumentV1` and no `type`; the client checks the schema after the signature | ADR-0009, `CapabilityDocumentVerifierTest` |
| Production signs with the development key every checkout holds | biz-kit refuses the published seed (by full public key) and a missing one at startup; a release app build refuses the development key | biz-kit `Dispatch.Config`; `checkReleaseConfiguration` |
| Another app on the device catches the sign-in redirect and redeems the code | PKCE S256: the code is useless without the verifier, which never leaves the app; the redirect is honoured only with the state of the sign-in in progress | ADR-0011, `SignInTest`, `AuthSessionTest`; biz-kit `BizKit.Identity.NativeLogins` |
| A forged redirect signs the device into the attacker's account | A redirect without the in-progress state changes nothing, and each is used once | ADR-0011, `AuthSessionTest` |
| The session is read from storage or a backup | Sealed with AES-256-GCM under an Android Keystore key; backup is excluded; a record that no longer opens reads as signed out | ADR-0011, `VaultTest`, `data_extraction_rules.xml` |
| After a hand-over, the previous person's queued events go out as the new person's | Events are sent under the assignment they were declared under; dispatch refuses another principal's assignment | ADR-0011; biz-kit `DispatchWeb.Plugs.ResolveActor` |
| A declaration is forged or altered in transit, or replayed as another device's | Each is signed by the registered device's Ed25519 key over the canonical statement of its own fields; dispatch checks it against that participant's active device | ADR-0012, `DeviceSigningTest`; biz-kit `Dispatch.Accounts.DeviceKeys` |
| Someone registers a key they do not hold, or for someone else | Registration needs a proof by the key over a statement bound to the caller's tenant and participant | ADR-0012, `DeviceRegistrationTest`; biz-kit `DeviceControllerTest` |
| A device key is read from storage or a backup | Sealed with AES-256-GCM under an Android Keystore key, backup excluded. Not hardware-bound: a rooted device can unseal it, and revocation is the answer | ADR-0012, `VaultTest` |
| After a hand-over, the new person's declarations are signed with the previous person's key | One key per person per installation; a key signs only for its owner | ADR-0012, `DeviceKeysTest` |
| A queued declaration is sent under a role selected after it was made | The outbox row records its assignment and is sent under it | `StatusOutbox`, `StatusOutboxTest` |

## Open, owned by later slices

| Threat | Slice |
| --- | --- |
| Provider webhook forgery, replay, and duplicate delivery | 4 |
| Cross-tenant read through a stale or wrong-scope role assignment | 1 |
| Location consent revocation racing an in-flight batch upload | 2 |
| Agent tool call with extra properties, wrong scope, or a replayed approval | 5 |
| Break-glass scope escalation, self-approval, session extension | 8 |
| Presentation attack, injected video, device integrity failure | 7 |
| Notification-derived ELD content treated as a certified HOS record | 3 |

## Explicit non-goals

Section 2.2 places these out of scope, and they are not mitigations to be added
later: editing or interpreting legal ELD records, continuous facial
surveillance, emotion or demographic inference, identifying unknown people,
hidden location collection, accessibility-service automation of third-party
apps, and autonomous acceptance of binding commitments.

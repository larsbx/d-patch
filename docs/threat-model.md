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
| A forged or edited capability document enables a surface the role lacks | ES256 signature over the received bytes, key pinned at build time, verified again on every read from storage; features exceed no capability server-side | ADR-0009, `CapabilityDocumentVerifier`, `CapabilityStore`, `CapabilityDocument` |
| A capability document is replayed under another login or role | Bound to `sub` and `role_assignment_id`; the client rejects a mismatch | `CapabilityDocumentVerifierTest` |
| Production signs with the development key every checkout holds | Startup refuses that key by identifier, and refuses no key | `Dispatch.Config`, `Dispatch.ConfigTest` |
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

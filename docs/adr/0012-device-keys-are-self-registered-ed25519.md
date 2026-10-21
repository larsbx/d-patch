# ADR-0012: Device keys are self-registered Ed25519, sealed by the Keystore

- **Status:** accepted — the registration model and key type were chosen by the product owner on 2026-10-21; it replaces §13's "platform keystores for device keys" with a Keystore-sealed key, and amends ADR-0010's enrolment line
- **Date:** 2026-10-21
- **Deciders:** product owner, platform engineering
- **Specification sections:** §7, §13, §22.3, §23.1, §25.2

## Context

§23.1: "Android registers an installation public key after interactive login.
Offline events are signed over canonical JSON with that device key." §13 asks
for platform keystores for device keys, and §7 for a hardware-bound key at
face enrolment. The platform's one signature scheme is Ed25519 over
`CoopEventCanonicalV1` (ADR-0009), and the Android Keystore has no Ed25519 at
`minSdk` 29.

ADR-0010 pointed device keys at biz-kit's `CoopSubstrate.Enrolments`. That
service grants task-ledger roles (operator, reviewer, governance) inside a
`{chapter, tenant, project}` scope, each approved by an administrator with
the governance key. A field device is none of those roles, dispatch tenants
have no coop scope, and an approval step per driver's phone is not in §23.1.

## Decision

1. **Self-registration.** After sign-in, once a verified capability document
   names the tenant and participant, the app registers its key at biz-kit's
   `POST /v1/me/devices` with a proof: its signature over the canonical
   `DispatchDeviceRegistrationV1` statement (`tenant_id`, `participant_id`,
   `installation_id`, `public_key`). No one approves it
   (biz-kit `docs/architecture/2026-10-21-device-keys.md`).
2. **Ed25519, sealed.** The key is made in software (BouncyCastle) and its
   seed kept with the session: sealed by AES-256-GCM under an Android Keystore
   key, in app-private preferences excluded from backup. It is decrypted into
   memory to sign. Hardware binding is given up for the platform's single
   scheme.
3. **One key per person per installation.** `DeviceKeys` holds the key of the
   person signed in. When someone else signs in, they get a new installation
   ID and key; the previous person's key is not used for them. Nothing signs
   until the key is registered, and a key that cannot be kept on disk is never
   registered.
4. **Signed when queued.** `StatusOutbox.declare` signs each declaration with
   the registered device, offline or not, over the canonical
   `DispatchStatusEventV1` statement of the body's own fields (§23.1's
   "canonical JSON" is the platform's canonical CBOR, as for capability
   documents). The signature is part of the queued body, so every retry is
   byte-identical. Dispatch then records it `DEVICE_AUTHENTICATED`.
   Declarations made before registration go unsigned and are recorded `NONE`.
5. **Registration retries** whenever a verified document arrives and when the
   app returns to the foreground. A `409` (the installation is taken) gives up
   that installation for a fresh one.

## Consequences

### Accepted

- The key is not hardware-bound. Malware with the app's privileges, or a
  rooted device, can use the Keystore key to unseal the seed. Revocation
  (`Device.revoke`) is the response to a lost or compromised device.
- §7's face-enrolment key is not this key. When face verification arrives
  (Slice 7) it needs its own decision; it may well be hardware-bound.
- A device signature attributes a declaration to a handset, not to a person
  being present.
- Section 4.3's role switcher is not built; registration acts under the
  document's assignment.

### Rejected alternatives

| Alternative | Why not |
| --- | --- |
| `CoopSubstrate.Enrolments` (ADR-0010) | Its roles, scope and per-device governance approval do not fit a field device |
| ECDSA P-256 in the Keystore | Hardware-bound and non-extractable, but a second signature scheme beside the platform's Ed25519 |
| Sign the raw request body | The server would have to keep raw bodies; a field-wise canonical form survives re-encoding |
| A server-issued challenge for registration | Replaying a proof registers the same key for the same person again, which changes nothing; a challenge would add server state for no gain |

## Verification

- `DeviceSigningTest`: both statements and their signatures reproduce, byte
  for byte, vectors biz-kit generated (`device-signing-vectors.json`); a
  declaration signed by the device is exactly the body biz-kit verifies;
  canonical heads at every boundary, key order, types, and refusals.
- `StatusOutboxTest`: declarations queue unsigned before registration and
  signed after, and the queued signature verifies.
- `DeviceKeysTest`: a key per person, surviving restart; nothing signs until
  registered, or for anyone else; stale registrations and unkept keys are not
  used; a taken installation is replaced.
- `DeviceRegistrationTest`: a verified document registers the key with a
  proof that verifies under biz-kit's statement; no re-registration; offline
  retries with the same key; a `409` starts afresh; an answer for another
  installation is not recorded.
- `DispatchClientTest`, `ContractConformanceTest`: the request on the wire,
  and the contract's names, required fields and header.
- Mutations: removing the outbox's signing, the owner check, the per-person
  key, the stale-registration check, the kept-key rule, the installation
  match, the `409` discard, canonical key order, shortest heads, or the event
  schema each fails a test.
- Not on the JVM: the Keystore key itself (Robolectric has none). It is now
  resolved on first use, so building the app touches no Keystore.

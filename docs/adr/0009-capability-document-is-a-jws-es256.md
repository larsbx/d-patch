# ADR-0009: The capability document is a compact JWS signed with ES256

- **Status:** accepted
- **Date:** 2026-09-24
- **Deciders:** platform engineering
- **Specification sections:** §23.2, §24.6, §25.2, §31, §34 (Slice 1)

## Context

Section 23.2: "Android features are enabled from a signed server capability
document, not from `if role == DRIVER` checks." The specification fixes that
the document is signed, and that `GET /v1/role-capabilities` serves it
(§24.6). It fixes neither the signature format, the key, nor what the client
does with a document it cannot verify.

Three constraints narrow the choice:

- `minSdk 29` (§19.1). Ed25519 reaches `java.security` only at API 33, so an
  Ed25519 signature needs a bundled crypto library on every supported device
  below that.
- The document is JSON. Any scheme that signs a *re-serialization* makes the two
  implementations agree on key order, number format, and escaping; a
  disagreement there presents as a forged document.
- Section 31 loads secrets at runtime and fails production startup closed, and
  §19 forbids a silent fallback. `make up` must still work on a clean checkout
  (Slice 0), which needs *some* key without provisioning one.

## Decision

The document is a **JWS compact serialization, `alg` ES256** (P-256, RFC 7518
§3.4), header `typ: capability+jws`, `kid` the first 16 base64url characters of
the SHA-256 of the key's DER SubjectPublicKeyInfo. The signature covers the
bytes received, so neither side canonicalizes anything.

The server key is `CAPABILITY_SIGNING_KEY`, a PKCS#8 PEM. A **published
development key** lives in `infra/capability-signing/`; `config/dev.exs` and
`config/test.exs` read it, the debug Android build pins its public half, and
`Dispatch.Config` fails production startup on an absent, unreadable, or
development key. Outside production an absent key fails only the endpoint, with
`503 CAPABILITY_SIGNING_UNAVAILABLE` — never an unsigned document.

The payload binds the document to `sub`, `tenant_id`, `participant_id`, and
`role_assignment_id`, and carries `iat`/`exp` twelve hours apart. A feature is
published only when the assignment holds the capability that feature exercises,
because a profile module may not grant (§23.2).

The client uses a document only after checking, in order: header `alg`, `typ`,
and `kid` against the pinned key; the signature; `schema_version`; and that
`sub` and `role_assignment_id` are the session's own. A document failing any of
these enables nothing. An **expired** document that passes every check remains
in use while the device cannot refresh it, and the UI says it is stale: the
document governs what is *offered*, every request is authorized again by the
server, and §25.2's offline status is the case the field application exists for.

## Consequences

### Accepted

Every checkout contains a private key. That is the same trade `infra/oidc/dex.yaml`
already makes for development passwords, and it is safe only because production
refuses the key by identifier — `Dispatch.Config` pins the development `kid`, and
a test pins that constant to the committed file.

ES256 signatures are non-deterministic, so two documents with identical claims
differ. Nothing compares documents; only claims are compared.

A release build needs the production public key at build time. Rotation is a new
app release, or a key list in a later schema version.

### Rejected alternatives

| Alternative | Why not |
| --- | --- |
| Ed25519 | Not in `java.security` below API 33; would bundle a crypto provider into the field app to sign a document the platform can verify natively |
| Canonical JSON with a detached signature | Two canonicalizers must agree byte for byte; the failure mode is a legitimate document rejected as forged |
| HMAC with a shared secret | The secret ships in the APK, so any holder can mint documents |
| No development key; generate one per boot | The debug app could not pin it, so it would have to fetch it — a verification key obtained from the party it verifies |
| Treat an expired document as enabling nothing | A driver offline past twelve hours loses the status control §25.2 exists to keep working, while the server re-authorizes every queued event anyway |

## Verification

- `Dispatch.Access.CapabilitySignerTest` checks the wire shape the client
  depends on — fixed-width R || S, the header fields, the `kid` derivation
  against `openssl` — and that any altered segment or foreign key fails.
- `Dispatch.ConfigTest` fails if production accepts the development key or no key.
- `DispatchWeb.RoleCapabilityControllerTest` verifies each served document with
  the public key before asserting on its claims, and covers the deny paths:
  unauthenticated, ambiguous selection, another principal's assignment, revoked
  assignment, and an unusable key.
- The Android `CapabilityDocumentVerifierTest` verifies documents signed by the
  development key and rejects tampered, foreign, mis-bound, and malformed ones.

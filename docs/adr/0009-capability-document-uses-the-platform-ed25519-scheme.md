# ADR-0009: The capability document uses the platform's Ed25519 scheme

- **Status:** accepted
- **Date:** 2026-10-20
- **Deciders:** platform engineering
- **Specification sections:** §19.1, §23.2, §24.6, §25.2, §31, §34 (Slice 1)

## Context

Section 23.2: "Android features are enabled from a signed server capability
document, not from `if role == DRIVER` checks." The specification fixes that
the document is signed and that `GET /v1/role-capabilities` serves it (§24.6).
It fixes neither the signature scheme nor what the client does with a document
it cannot verify.

The server is now biz-kit's `apps/dispatch` (ADR-0010). biz-kit already has one
signature scheme for everything it signs: Ed25519 over `CoopEventCanonicalV1`
(deterministic CBOR) bytes, with `k-` key identifiers. A first draft of this
ADR chose a JWS with ES256 and its own key scheme; that would have been a
third convention beside the ledger's, and was dropped before merge.

`minSdk 29` (§19.1) has no Ed25519 in `java.security`; it arrives at API 33.

## Decision

The document is **canonical CBOR claims, signed with Ed25519, served as
`{document, signature, key_id}`** (each base64url; `key_id` is `k-` and the
key's first four bytes in hex). The signature covers the bytes received, so
neither side re-encodes anything. The claims carry
`schema: "DispatchCapabilityDocumentV1"` and no `type`, so they are never read
as a ledger envelope or command, and nothing else the platform signs verifies
as a document.

The client verifies with **BouncyCastle's Ed25519** (pure JVM, every supported
release) against a key **pinned at build time**: debug pins biz-kit's
published development key (`infra/capability-signing/development.pub`), and a
release build fails unless given the deployment's key, which may not be the
development one. biz-kit refuses the development seed in production.

The client uses a document only after checking, in order: `key_id` against the
pinned key, the signature, then — and only then decoding the CBOR —
`schema`, `schema_version`, and that `sub` and `role_assignment_id` are the
session's own. A document failing any check enables nothing. An **expired**
document that passes every check stays in use while the device cannot refresh
it, and the UI says it is stale: the document governs what is *offered*, every
request is authorized again by the server, and §25.2's offline status is the
case the field application exists for.

## Consequences

### Accepted

BouncyCastle is a large dependency for one primitive. R8 strips what is
unused, and it is the standard provider for exactly this gap below API 33.

A release build needs the production public key at build time. Rotation is a
new app release, or a key list in a later schema version.

The document is a service attestation derived from dispatch's role rows. When
dispatch's roles become ledger grants (biz-kit's consolidation, step 4), the
client should read its grants from the change feed and this document retires.

### Rejected alternatives

| Alternative | Why not |
| --- | --- |
| JWS with ES256 (the first draft) | A second signature convention beside the ledger's; biz-kit already canonicalizes, which was ES256's main appeal |
| Raise `minSdk` to 33 for native Ed25519 | §19.1 fixes 29 |
| HMAC with a shared secret | The secret ships in the APK, so any holder can mint documents |
| Fetch the verification key at runtime | A key obtained from the party it verifies |
| Treat an expired document as enabling nothing | A driver offline past twelve hours loses the status control §25.2 exists to keep working, while the server re-authorizes every queued event anyway |

## Verification

- `CapabilityDocumentVerifierTest` verifies a document **signed by biz-kit**
  with the development seed against the pinned development key, and rejects
  altered claims, another key, another schema, another subject or assignment,
  an unknown schema version, and malformed input.
- `CapabilityStoreTest` and `CapabilityRepositoryTest` re-verify stored
  documents and discard them on a `403`.
- `ContractConformanceTest` holds the DTOs to biz-kit's contract.
- biz-kit: `Dispatch.Access.CapabilitySignerTest`, `Dispatch.ConfigTest`, and
  `DispatchWeb.RoleCapabilityControllerTest` (see biz-kit
  `docs/architecture/2026-10-20-dispatch-capability-document.md`).

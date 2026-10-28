# ADR-0015: Dispatch client conformance to biz-kit extensions and Hawala boundaries

- **Status:** proposed; contracts and tests planned, not deployed
- **Date:** 2026-10-28
- **Deciders:** platform and Dispatch maintainers
- **Specification sections:** §19.1, §20, §21, §34 (existing contract and backend authority)
- **Companions:** [biz-kit extension ADR](https://github.com/larsbx/biz-kit/blob/main/docs/architecture/2026-10-28-cross-repository-extension-and-hawala-capability.md), [hawala-core ADR 0006](https://github.com/larsbx/hawala-core/blob/main/docs/decisions/0006-biz-kit-extension-host-boundary.md), [Eatsbot retirement ADR](https://github.com/larsbx/eatsbot/blob/dev/docs/modules/decisions/pages/biz-kit-interoperability-and-source-ownership.adoc), [school-ops decision 0007](https://github.com/larsbx/school-ops/blob/main/docs/decisions/0007-biz-kit-content-authority-and-extension-contract.md).

## Context

Accepted [ADR-0010](0010-the-server-is-biz-kit-apps-dispatch.md) places the Dispatch server, Ash policy, migrations and generated OpenAPI in `biz-kit/apps/dispatch`. The `central/` directory in d-patch is a frozen test-compatible snapshot, not a second server development branch. [ADR-0011](0011-android-signs-in-through-biz-kit.md) covers native sign-in, and [ADR-0012](0012-device-keys-are-self-registered-ed25519.md) governs signed device declarations.

Biz-kit is defining a common extension contract. Hawala-core and biz-kit are independently proposing a network-native Hawala capability (merged Proposed ADRs #42/#173, with G1–G11 still open). Dispatch may eventually initiate delivery-fee payments or settlement-related workflows, but it owns *transport facts*, not financial authority. Platform contract work must not cause this repository to implement a shadow Dispatch backend or a money ledger.

## Decision

**Keep Dispatch's backend authority in biz-kit and align its independently released clients to a versioned platform contract.** Preserve the current split and the existing security/privacy guarantees.

### Client/server ownership

- `biz-kit/apps/dispatch` remains canonical for Dispatch business commands, Ash permission enforcement, operational ledger admission, server API, migrations and generated `apps/dispatch/contracts/openapi.json`.
- This repository remains canonical for Android field clients, client-facing requirements, local offline interaction, client tests and threat model.
- `central/` stays frozen for historical compatibility until tests migrate; no feature or authorization work may land there as an independent production server.
- Generate/copy the authoritative server contract into `contracts/biz-kit-dispatch.openapi.json`, retaining `contracts/openapi.json` as the snapshot contract for `central/`. Server-contract changes land *server first*, then an explicitly pinned client sync/acceptance PR.
- When implemented, a proposed **BizKit.ExtensionV1** manifest belongs to the server-side Dispatch domain. The mobile client negotiates an authenticated, versioned *wire* contract. It does not load an Elixir extension or gain write authority from a capability declaration.

### Authorization and evidence remain separate

Preserve the current role/tenant/party-relationship admission boundary, client device signatures, revocation behavior, and four-way distinction among participant declarations, observations, external notices and AI inferences. A driver status or proof of delivery cannot be fabricated by an agent and cannot become payment finality.

Native Dispatch sign-in retains biz-kit authentication/PKCE and scoped grants. QR-only *participant identity presentation* applies when entering a **Hawala financial interaction**, not as a retroactive replacement for existing Dispatch login or device authorization. A Hawala QR does not confer Dispatch, fleet, payout or custody grants.

### Optional Hawala client composition

A Dispatch fee or payout UI may create a source-attributed, immutable, consent-bound *proposal* addressed to an authorized biz-kit Hawala capability, once its interface exists and is verified. This repository must not implement Hawala Iroh financial protocol, balance accounting, key custody, settlement adjudication or independent financial authority.

The Hawala service owns financial result states, scopes, signatures, authorization, reconciliation and network coordination. A business-verified delivery event is not proof that Hawala paid, and a `Committed(intent)` status is not a completed payout or escrow release. A Dispatch workflow may advance a financial-dependent state only after the **required** Hawala financial evidence is independently verified under pinned authority and privacy policy.

For Hawala interactions, participant keys and transaction QR authorization remain on participant devices. A Dispatch server/agent must not sign participant consent. Do not route private Hawala finance payloads through ActivityPub public discovery, dispatch location feeds or unsolicited driver-handset calls.

No live financial UI, payout authority or real-money operation is authorized by this ADR.

### Conformance and rollout

1. Review the biz-kit extension contract and document server/client versions, migration paths, source revision, scope and observable errors.
2. Pin generated OpenAPI diff and client DTO conformance to the exact biz-kit source commit. Require server-first, client-second PRs.
3. Exercise native sign-in, scoped grants, revocation, no-priority-policy regression, consented location, offline outbox replay, duplicate delivery, stale tokens and confirmed logout against the **actual biz-kit backend**. Do not equate a test against frozen `central/` with integration acceptance.
4. For future Hawala UI, run signed-byte cross-repo vectors, participant-key isolation, rejected/out-of-order/uncertain receipts, timeout/recovery and `HOLD` state tests against the authenticated Hawala service, not a mocked success JSON blob.
5. Separate pure/client, server integration, two-instance federation and live/regulated-operation evidence. Lean/Quint financial-conformance work belongs to hawala-core and its biz-kit host, with generated traces consumed here for boundary tests where appropriate.

## Consequences

### Accepted

Two-repository development remains intentional: biz-kit provides the server, d-patch provides the clients, and both share a versioned contract with exact source attribution. Hawala, where present, is an optional separate financial capability. Driver/dispatcher domain privileges cannot be inferred from Hawala peer membership, and vice versa.

### Rejected alternatives

| Alternative | Why not |
| --- | --- |
| Restart active feature work in `central/` | Forks canonical Dispatch server and authorization |
| Import Android clients into the Elixir umbrella | Unnecessary repository/toolchain coupling |
| Let mobile apps or Hawala QR metadata create platform grants | Breaks explicit authorization and participant consent |
| Treat location, delivery proof or `Committed(intent)` as payment completion | Confuses distinct authoritative facts |
| Make ActivityPub a financial transport | Public discovery offers no financial secrecy or finality |

## Verification

Acceptance requires matching text with the four companion ADRs. Follow-up CI should fail when the contract copy differs from the pinned server, an offline request is replayed after revocation, a nonparticipant signs Hawala consent, an external financial receipt is accepted without authority, or an execution intent is displayed as a settled payment. This PR is documentation only; none of these new Hawala UI tests or services is claimed implemented.

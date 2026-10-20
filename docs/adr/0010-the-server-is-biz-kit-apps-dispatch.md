# ADR-0010: The dispatch server is developed in biz-kit; d-patch keeps the clients

- **Status:** accepted
- **Date:** 2026-10-20
- **Deciders:** platform engineering
- **Specification sections:** §19.1, §20, §21, §34

## Context

biz-kit imported `central/` with `git subtree` as `apps/dispatch`, beside the
signed ledger (`coop_substrate`), the principal registry (`biz_kit_identity`),
and the orchestration plane. Its target is one signed ledger, one admission
path, one principal and grant model, and one database. Its README excludes the
Android, web and e2e clients by design: they are clients of that backend.

Developing the server in two places would fork it. The capability document
showed the cost at once: built here first, it duplicated signing, identity and
device machinery biz-kit already had.

## Decision

Server work — `/v1` endpoints, Ash resources, policies, the portal, migrations
— lands in biz-kit's `apps/dispatch`. d-patch keeps the clients
(`apps/android-driver`, `web/`, `e2e/`), the specification, the ADRs that
govern the clients, and the threat model.

`central/` stays in this repository unchanged, as the snapshot `make up` and
the existing CI gates run against, until the e2e suite targets biz-kit. It
takes no new features.

The client's transport contract is biz-kit's generated
`apps/dispatch/contracts/openapi.json`, copied here as
`contracts/biz-kit-dispatch.openapi.json` by `scripts/sync-dispatch-contract.sh`
and enforced on the client by `ContractConformanceTest`. `contracts/openapi.json`
remains `central/`'s.

Future client work should use biz-kit's existing machinery rather than build
it again here: device keys and enrolment (`CoopSubstrate.Enrolments`, Ed25519
per-device keys approved by governance), sign-in (`BizKit.Identity`), and
grants read from the change feed.

## Consequences

### Accepted

A server change and the client change that uses it are two pull requests in
two repositories, ordered server first. The contract copy makes the
dependency explicit and checkable.

`central/` drifts behind biz-kit until it is removed; nothing should be built
on what only it has.

### Rejected alternatives

| Alternative | Why not |
| --- | --- |
| Keep developing `central/` and re-import into biz-kit | Two authorities for one server; biz-kit's consolidation removes exactly that |
| Move the Android app into biz-kit | biz-kit excludes the clients deliberately, and the app's toolchain has nothing in common with the umbrella's |
| Delete `central/` now | `make up`, the e2e suite and several CI gates still run against it |

## Verification

`ContractConformanceTest` fails when the client's DTOs and biz-kit's contract
disagree; `scripts/sync-dispatch-contract.sh --check` fails when the copy is
stale. biz-kit CI runs `mix openapi.check` so its contract tracks its router.

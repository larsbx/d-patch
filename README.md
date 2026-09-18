# Dispatch Platform

A role-aware Android field application and an operations web portal sharing one
current operational view of a participant, trip, load, communications, and
location.

Built to `docs/SPECIFICATION.md` (v1.2). The specification is the contract; this
README is a map of how far the implementation has got and where things live.

## Four kinds of information

The system keeps these distinct everywhere — in the schema, the API, and the UI.
Most of the design follows from refusing to collapse them:

| Kind | Example | Rule |
| --- | --- | --- |
| Participant declarations | A driver sets `AT_PICKUP` | Only the participant may set it |
| Observed facts | A GPS sample, a call event | Recorded with source and freshness |
| External notices | An ELD notification | Never a certified hours-of-service record |
| AI inferences | A predicted ETA, a suggested action | Never overwrites a declaration, never presented as one |

An AI inference must never overwrite a driver declaration or be presented as
one. Every user-visible fact declares its source and freshness.

## Quick start

```sh
make up
```

Starts PostgreSQL/PostGIS, a local OIDC provider, and the central service, and
waits for every health check.

```sh
curl -s localhost:4000/health/live
curl -s localhost:4000/health/ready
make help          # every target
make ci            # every gate runnable without an Android SDK
```

Development accounts (one per seeded role profile) are in
`infra/oidc/dex.yaml`; the password is `dispatch`. They are published here
deliberately so they cannot be mistaken for production secrets.

## Layout

```
central/               Ash/Phoenix OTP release — the sole domain implementation
apps/android-driver/   Kotlin/Compose field application
contracts/             OpenAPI 3.1, generated and diff-checked from central/
infra/                 container images, reverse proxy, local OIDC
web/                   tests for the portal map element (the only first-party portal JS)
e2e/                   Playwright suite
docs/                  ADRs, runbooks, threat model, retention
scripts/               invariant and layout checks run by CI
```

Each deployable directory has its own README with local commands,
configuration, health checks, test commands, and failure modes.

## Architectural commitments

These are the ones that shape everything else.

**Everything behind a provider port.** Communications (§27), geospatial (§28),
and agent runtime (§29) are each a behaviour with a configuration-selected
adapter. Twilio, Google, and Hermes are the initial implementations, not the
domain model. A vendor type that escapes its adapter fails the `lint` gate.

**Roles are data, not code.** `DRIVER` is a seeded, versioned role profile over
canonical participants and role assignments. Adding an operational role means a
new `RoleDefinition` and policy tests — not a new table, controller, or
`if role == DRIVER`.

**Authorization lives in Ash policies.** Not in controller conditionals, not in
hidden buttons. Every externally initiated action receives an explicit actor and
tenant.

**No priority tier anywhere.** Calls, messages, ELD notices, location
exceptions, and approval requests share one class, processed in receipt order.
No caller's words and no AI classification can promote an item. There is no
domain `priority`, `severity`, or `urgency` field, and the `lint` gate refuses
one.

**No inbound path reaches the driver's handset.** Every inbound call is handled
by the disclosed AI assistant. There is no transfer, conference, or ring path,
and no `TRANSFERRED` or `RINGING_DRIVER` call state exists to reach. A driver's
own call to public emergency services bypasses the assistant entirely; an
inbound caller can never invoke that path.

**Biometric material never leaves the device.** System biometric authentication
and one-to-one face verification are distinct and never conflated. Optional face
matching is off by default and stays off until the Section 7.7 gates are
documented as approved.

## Status

| Slice | Scope | State |
| --- | --- | --- |
| 0 | Repository, contracts, Compose, CI, health checks, ADR template | **complete** |
| 1 | Participant identity, role profiles, assignments, status | not started |
| 2 | Consented location and maps | not started |
| 3 | ELD notification intake | not started |
| 4 | Communications ports and Twilio adapters | not started |
| 5 | Agent-runtime ports, tools, approvals | not started |
| 6 | Telegram/WhatsApp adapters | not started |
| 7 | Biometric identity assurance | not started |
| 8 | Break-glass, hardening, pilot | not started |

Slice 0 delivers the skeleton and the contracts, not the domain. `central/lib/dispatch/`
has a directory per domain and no resources in them yet; that is Slice 1's work.

## Contributing

- Section 19 makes **MUST**, **MUST NOT**, **SHOULD**, and **MAY** normative.
  Replacing a MUST requires an ADR and approval — see `docs/adr/0000-template.md`.
- A pull request cannot merge with a failing CI gate (§33.5).
- `contracts/openapi.json` is generated. Run `mix openapi.generate` in
  `central/` and commit the result; `openapi-diff` fails on drift.
- Section 35's definition of done applies per slice, not per pull request.

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
central/               frozen snapshot of the server; development continues in biz-kit (ADR-0010)
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
tenant. A load- or stop-scoped grant needs two independent things — an active
role assignment *and* an active party relationship — because they expire
separately, and a contract that ended should end access even while the role
remains.

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

The server now lives in [biz-kit](https://github.com/larsbx/biz-kit) as
`apps/dispatch`, beside the signed ledger, principal registry and grants it is
consolidating onto (ADR-0010). Server work lands there; this repository keeps
the clients, the specification and the client ADRs. `central/` is the frozen
snapshot `make up` and the existing gates still run against.

| Slice | Scope | State |
| --- | --- | --- |
| 0 | Repository, contracts, Compose, CI, health checks, ADR template | **complete** |
| 1 | Participant identity, role profiles, assignments, status | **built**: identity, access, loads, parties, status declarations, the audit chain, the `/v1` status surface, the role-scoped portal pages and streams; the signed capability document (in biz-kit); the capability-driven Android offline status UI. Android sign-in through biz-kit (until sign-out) and self-registered device keys; end-to-end runs against biz-kit for 1 (status), 13, 14 and 16. **Remaining:** the surfaces scenarios 15 and 17–19 need (below) |
| 2 | Consented location and maps | not started |
| 3 | ELD notification intake | not started |
| 4 | Communications ports and Twilio adapters | not started |
| 5 | Agent-runtime ports, tools, approvals | not started |
| 6 | Telegram/WhatsApp adapters | not started |
| 7 | Biometric identity assurance | not started |
| 8 | Break-glass, hardening, pilot | not started |

Slice 0 delivered the skeleton and the contracts. Slice 1 is being built in
parts. In place: organizations, users, participants, devices, the versioned
role definitions and assignments, the capability registry and six seeded role
profiles; loads, stops, assignments, and the load- and stop-party relationships
that §22.2 requires *in addition to* a role assignment; and participant status
declarations behind Ash policies, with a hash-chained audit stream.

The machine API is now reachable: OIDC bearer tokens are verified against the
issuer's published keys, §24.6's `X-Role-Assignment-ID` selects the single role
assignment a request acts under, and `POST /v1/me/status-events` — with the
`DRIVER` compatibility projection at `POST /v1/driver/status-events` — records a
declaration behind the same Ash policies, honouring `Idempotency-Key` and
device-sequence deduplication. Errors are RFC 9457 problems with stable reason
codes.

The portal is role-scoped and server-rendered. A browser session holds the
authenticated subject and the selected role assignment, both revalidated on
every request (§24.6), and §4.3's switcher is how a user holding several
assignments says which one they are acting under. Two surfaces are live:
`/partner/stops/:id` for shippers and receivers, and
`/operations/participants/:id` with its `DRIVER` compatibility projection.

The field-authorization boundary is a view model rather than a filter. §4.3's
prohibition list — no driver timeline, route trace, unrelated stops, negotiated
rate or carrier notes on a partner page — holds because those structs have
nowhere to put them, so a leak is a compile error rather than a policy nobody
revisits. An unrelated subject returns the same 404 as one that does not exist
(acceptance criterion 19), and every fact carries its source and freshness
(§26.3's `fact_badge`), because §1's four kinds of information are only distinct
if the page says which it is showing.

The participant stream of §24.5 is live at
`GET /ui/participants/:id/stream`. Its authorization is per *tick* rather than
per connection: a page authorizes once and is gone, while a stream keeps
answering for hours, so every heartbeat revalidates the assignment behind it.
That is what makes acceptance criterion 16 — a dispatcher losing the stream
*immediately* when the relationship ends — true of an idle socket and not only
of one that happens to receive an event.

The operations roster stream (`GET /ui/operations/stream`) is live beside it.

The field application enables features from a signed capability document
(§23.2, ADR-0009), served by biz-kit at `GET /v1/role-capabilities`: canonical
CBOR claims with an Ed25519 signature, the platform's one signature scheme. The
claims are bound to the caller, tenant, participant and selected assignment;
they list the role's features — each only if the assignment holds the
capability it exercises — and §5.1's status vocabulary, so the app holds no
second copy of it. The app pins the verification key at build time and enables
nothing a document does not say, from nothing that does not verify.

Status declarations go through §25.2's Room outbox: the device sequence is
allocated and the pending row written in one transaction, the screen updates
from that row, and `SyncWorker` (network required, charging not) sends due
events oldest first under the assignment each was declared under. A rejection
is final and shows its corrective action; anything else keeps the event
queued.

The app signs in through biz-kit's native sign-in (ADR-0011): the system
browser, PKCE, and a biz-kit session as the bearer token dispatch accepts. The
session and any sign-in in flight are sealed under an Android Keystore key.
The session lasts until the person signs out, across biz-kit restarts; one the
server ends early is answered `401`, and queued work waits for the next
sign-in while the stored capability document keeps working offline. ADR-0011
records the decision that replaces §23.1's 15-minute token and rotating
refresh token.

Each installation registers its own Ed25519 key with biz-kit once signed in
(ADR-0012), and signs every declaration as it is queued, offline or not, over
the platform's canonical form. Dispatch records a signed declaration as
`DEVICE_AUTHENTICATED`. The key is sealed under an Android Keystore key, like
the session; it is not hardware-bound.

The exit-criterion scenarios whose surfaces exist run end to end against
biz-kit's stack, online and after an offline interval (`e2e/README.md`): 1's
status half, 13, 14 and 16 in Chromium and the field app's protocol, and the
installed app on an emulator in CI as both field roles. Scenario 14's second
profile is biz-kit's test `Courier`, seeded in the development stack: the same
sign-in, document, device key, outbox and roster as the driver, with nothing in
the app or the server branching on which it is. The first runs found four defects that
biz-kit's own suite could not see, now fixed there: no portal page opened its
stream (Datastar's expressions need `unsafe-eval`, which §26.1's CSP rightly
refuses), every real `EventSource` was refused with a 406, a refused
declaration under an `Idempotency-Key` answered 500, and `/auth`'s login cookie
was `Secure` over dev's plain HTTP.

### Next (Slice 1 remainder)

Each of these is a surface biz-kit's dispatch does not have yet, so its
scenario has nothing to run against (ADR-0010):

1. **Administration of assignments** (scenario 15): grant and revoke through
   the portal or `/v1`, by an `ADMIN` who still sees no operational page. The
   e2e fixture stands in for this today.
2. **Partner actions** (scenarios 17–19): pickup and delivery readiness,
   window and instruction proposals that stay proposals, proof references, and
   the broker's contracted-loads view. `/partner/stops/:id` is read-only.
3. Later, when dispatch's roles become ledger grants: read grants from
   biz-kit's change feed and retire the capability document.

Scenario 1's "starts assignment sharing" is Slice 2's (consented location),
as are scenario 14's consent and location resources; its approval and
communications resources arrive with Slices 4 and 5.

## Contributing

- Section 19 makes **MUST**, **MUST NOT**, **SHOULD**, and **MAY** normative.
  Replacing a MUST requires an ADR and approval — see `docs/adr/0000-template.md`.
- A pull request cannot merge with a failing CI gate (§33.5). `scripts/verify.sh`
  runs the gates that need no database or browser; `--all` adds the rest.
- `contracts/openapi.json` is generated. Run `mix openapi.generate` in
  `central/` and commit the result; `openapi-diff` fails on drift.
- Section 35's definition of done applies per slice, not per pull request.

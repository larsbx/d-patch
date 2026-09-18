# ADR-0005: The tenant is the operating organization; users and organizations are global

- **Status:** accepted
- **Date:** 2026-09-18
- **Deciders:** platform engineering
- **Specification sections:** §22, §22.1, §23.2, §23.3

## Context

Section 22 states that **all** mutable aggregate tables must carry
`tenant_id uuid not null`. Section 22.1 then lists `organizations`, `users`,
`organization_memberships`, `participants`, `devices`, and `contacts` without
one, while `role_definitions` and `role_assignments` carry it explicitly. Both
are normative, and they cannot both be read literally.

The uniqueness rules decide it. Section 22.1 requires `users.oidc_subject` to be
unique — globally, since an OIDC subject identifies one person at one identity
provider regardless of who they work for. It also requires
`contacts.phone_e164` to be unique for *normalized active contacts within a
tenant*, which only means something if contacts are tenant-scoped.

So the two lists are describing different kinds of row, not disagreeing about
one kind.

## Decision

The tenant is an `Organization` — in the first release a carrier, since Section
23.2 scopes `DISPATCHER` by carrier. `tenant_id` references `organizations.id`.

**Global resources** (`global? true`, no `tenant_id`):

| Resource | Why |
| --- | --- |
| `organizations` | A tenant cannot be scoped to itself, and a broker or facility is one organization that many carriers reference |
| `users` | `oidc_subject` identifies one person across every tenant they work with |

**Tenant-scoped resources** (attribute multitenancy on `tenant_id`):
`organization_memberships`, `participants`, `devices`, `contacts`,
`role_definitions`, `role_assignments`, and every operational and append-only
table in Sections 22.2–22.6.

Multitenancy is attribute-based, never schema-based. Section 22 gives every
operational table a `tenant_id` column, and Section 23.3 requires authorization
to combine tenant, role, and scope in one policy decision — which a
schema-per-tenant layout would push into connection handling instead.

## Consequences

### Accepted

One person working for two carriers is one `users` row and two `participants`
rows, one per tenant. That is the intended shape: Section 23.3 requires every
request to select a single active role assignment and forbids unioning
capabilities merely because assignments share a user, so the participant — not
the user — is the operational identity.

An organization referenced by several tenants is one row. A carrier therefore
cannot see which other carriers reference the same broker; that separation lives
in `load_parties` and `stop_parties` (Section 22.2), which are tenant-scoped.

Because `organizations` and `users` are global, no Ash policy may grant access
on the strength of reading one. Section 22.2 already requires this: a contact
record, phone number, email address, organization kind, or caller claim never
grants access by itself.

### Rejected alternatives

| Alternative | Why not |
| --- | --- |
| `tenant_id` on `organizations` too | A tenant row would have to reference itself, and every broker would be duplicated per carrier, breaking `contacts` normalization across a shared party |
| `tenant_id` on `users` | Contradicts the global uniqueness of `oidc_subject`; one person signing in twice would be two identities |
| Schema-per-tenant multitenancy | Pushes a decision Section 23.3 places in Ash policies into connection routing, and makes a cross-tenant authorization test impossible to write |
| Treat Section 22's "all" as absolute | Makes `users.oidc_subject` unenforceable as stated in the same section |

## Verification

`Dispatch.AccessTest` asserts that a participant in one tenant cannot be read
with another tenant's context, that `Organization` and `User` are declared
global, and that every other resource in `Dispatch.Accounts` declares attribute
multitenancy. The `unit` gate runs it.

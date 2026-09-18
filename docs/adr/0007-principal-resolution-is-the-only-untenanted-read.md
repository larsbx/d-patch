# ADR-0007: Principal resolution is the only untenanted read

- **Status:** accepted
- **Date:** 2026-09-18
- **Deciders:** platform engineering
- **Specification sections:** §21.1, §23.3, §24.6; ADR-0005

## Context

Every domain read in this system is authorized against an actor and scoped to a
tenant. Resolving a bearer token into an actor cannot be either of those things:
it is the step that *produces* the actor, and the tenant is a property of the
role assignment it has not selected yet.

Nor can the tenant come from the request. ADR-0005 makes users global and
everything else tenant-scoped, so one person may hold assignments in several
operating organizations. Section 24.6 has the client name a role assignment, and
the assignment carries the tenant — which means the server must be able to ask
"which assignments does this subject hold, anywhere?" before it knows where to
look.

Ash's mechanism for a query that omits a tenant is `global? true` on the
resource's `multitenancy` block. Setting it on `Participant` and
`RoleAssignment` would answer the question, at a price: every other read of
those resources would silently span tenants when a `set_tenant` was forgotten,
instead of failing loudly. Tenant isolation would then depend on nobody ever
omitting a line.

## Decision

`Dispatch.Identity.PrincipalResolution` is the single place where an untenanted
read happens, and `contexts/2` is the only query that performs one. It is an
Ecto projection over `role_assignments` joined to `participants`, returning
three identifiers per row — role assignment, tenant, participant — and nothing
else. No names, no capabilities, no domain data.

`Participant` and `RoleAssignment` keep strict attribute multitenancy. A
forgotten tenant anywhere else still raises.

Once a context is selected, the assignment is re-read **through Ash with the
tenant set**, using the `:active` read so the validity interval is re-applied in
SQL, and `Dispatch.Access.Actor` checks it once more against the same instant.
The directory lookup therefore narrows the candidate set; it never grants
anything. That is Section 24.6's "selects authority context but never grants it"
made structural rather than procedural.

Selection itself is a filter over what the principal already holds, so a header
naming someone else's assignment finds nothing — the same code path handles a
forged header and a stale browser tab. Holding several assignments with no
header is refused rather than resolved, because Section 23.3 forbids unioning
capabilities and choosing on the caller's behalf would be a union by another
name.

## Consequences

### Accepted

`contexts/2` is hand-written SQL-shaped code rather than an Ash query, so it
does not benefit from resource-level changes automatically. A new principal type
or a change to what makes a participant eligible must be reflected here
deliberately. Given what this function decides, deliberate is the right mode.

The two `authorize?: false` calls in this module are the only pre-authorization
bypasses in the request path. Both carry `REVIEWED-UNAUTHORIZED` justifications
that `scripts/check-invariants.sh` requires, and both are keyed by values the
token already established.

Two queries run per request instead of one. That is the cost of not trusting the
first: the projection says where the caller *might* act, and the tenanted re-read
is what decides that they still may.

### Rejected alternatives

| Alternative | Why not |
| --- | --- |
| `global? true` on `Participant` and `RoleAssignment` | Turns every forgotten `set_tenant` from a loud error into a silent cross-tenant read |
| Carry the tenant in the token | Requires the identity provider to know the tenant model, and re-binds authority to a claim the server cannot re-verify against the assignment |
| Take the tenant from a request header | The header would then grant context rather than select it, which Section 24.6 forbids in as many words |
| Resolve per tenant by iterating all tenants | Cost grows with the tenant count on every request, and the iteration is itself an untenanted read wearing a loop |

## Verification

`Dispatch.Identity.PrincipalResolutionTest` builds one user with participants
and assignments in two organizations and asserts that the pair is ambiguous
without a header, that each header selects its own tenant, and that an
assignment held by a third party selects nothing. Making `select/2` return the
first candidate instead of refusing, or trusting the header without membership,
each makes those tests fail.

# ADR-0006: Role profiles declare their own assignment cardinality

- **Status:** accepted
- **Date:** 2026-09-18
- **Deciders:** platform engineering
- **Specification sections:** §22.2, §23.2

## Context

Section 22.2 says at most one `ACTIVE` assignment per operator participant is
enforced by a partial unique index **for the initial `DRIVER` role profile**,
and that "other role profiles may declare a different cardinality constraint
through reviewed application policy and a matching database constraint".

The first implementation applied the index to every operator participant
regardless of profile. That is wrong in a way that is easy to miss, because the
restriction looks like a safety property: a dispatcher, or a future courier or
team-driver profile, has no such limit, and a universal index blocks work the
specification permits while appearing merely cautious.

Section 22.2 asks for two things that pull in opposite directions — the rule
must live in the database, *and* it must apply per profile. A profile has no
column on `assignments`, and adding a foreign key to `role_assignments` would
make an operational record depend on an authorization record's lifetime.

## Decision

`Dispatch.Access.RoleProfile` gains a fifth callback:

```elixir
@callback allows_concurrent_assignments?() :: boolean()
```

The profile module is the "reviewed application policy" Section 22.2 names, so
the declaration belongs there. It defaults to `false`.

`Dispatch.Fleet.Assignment` carries `operator_exclusive`, a boolean derived from
the deciding profile when the assignment is planned, and the unique index is
partial on `status = 'ACTIVE' AND operator_exclusive`. That is the "matching
database constraint": the rule holds against a bulk import or a direct SQL
write, not only against application code.

The flag is derived, never accepted. `plan` takes `operator_role_assignment_id`
as an *argument* — used to resolve the profile and then discarded — rather than
accepting `operator_exclusive` as an attribute. If a caller could set it,
opting out of the restriction would be a matter of asserting that it does not
apply, which is not what "reviewed application policy" means.

An unresolvable or absent role assignment leaves the restrictive default. The
cost of being wrong is asymmetric: an unintended restriction blocks an
activation with a clear error, while an unintended relaxation produces two
active assignments and, by Sections 6.1 and 25.2, location samples and queued
offline events that cannot be attributed to a trip.

## Consequences

### Accepted

This adds a callback to the contract Section 23.2 states, so every profile
module must answer it — which is the point, since Section 22.2 expects profiles
to declare cardinality and the contract otherwise gives them nowhere to do so.
`Dispatch.Access.Roles.Base` supplies the restrictive default, so a profile that
has not considered concurrency inherits the safe answer rather than failing to
compile.

`operator_exclusive` is a denormalised copy of a profile's answer at planning
time. A profile that later changes its declaration does not retroactively
change existing rows. That is deliberate: an assignment planned under one set of
rules should not silently acquire another, and Section 23.3 already treats a
role definition version as immutable once used.

### Rejected alternatives

| Alternative | Why not |
| --- | --- |
| Keep the universal index | Applies a `DRIVER` rule to every profile, blocking work Section 22.2 permits |
| Enforce in application policy only | Section 22.2 requires a matching database constraint; application-only rules do not survive a bulk import |
| Foreign key from `assignments` to `role_assignments` | Makes an operational record depend on an authorization record's lifetime; revoking a role would orphan or cascade into trip history |
| Branch on the role key when building the index | Section 23.2 forbids branching on a seeded role key, and `scripts/check-invariants.sh` rejects it |
| Accept `operator_exclusive` from the caller | Opting out of a constraint would become a matter of claiming it does not apply |

## Verification

`Dispatch.FleetTest` covers both branches: the `DRIVER` profile refuses a second
activation, and `Dispatch.Support.Roles.Courier` — a test-only profile
declaring concurrency, of the kind Section 33.2 anticipates — activates two.
Flipping the courier's declaration makes that second test fail, so it tracks the
profile rather than a constant. A further test asserts `operator_exclusive` is
absent from the `plan` action's accepted attributes.

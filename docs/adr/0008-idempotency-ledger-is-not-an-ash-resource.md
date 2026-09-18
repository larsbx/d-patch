# ADR-0008: The idempotency ledger is not an Ash resource

- **Status:** accepted
- **Date:** 2026-09-18
- **Deciders:** platform engineering
- **Specification sections:** §21.1, §22, §24

## Context

Section 24 requires that every mutation accept `Idempotency-Key`, and that the
server store "the key, actor, request hash, response status, and response body
for 24 hours", returning `409 IDEMPOTENCY_KEY_REUSED` when a key is reused with
a different request hash.

Section 21.1 gives Ash resources, actions and policies ownership of the domain
and its authorization, and AshPostgres ownership of persistence. The default
assumption in this codebase is therefore that a stored thing is an Ash resource.

Section 22's table inventory does not list an idempotency table, which is
consistent: the sections that enumerate persisted state are about operational
records, and this is neither an operational record nor domain data. It is the
transport layer remembering what it already answered.

## Decision

`Dispatch.Idempotency.Record` is a plain Ecto schema with a hand-written
migration, and `Dispatch.Idempotency` is an ordinary module.

The deciding question was what policies the resource would have. A ledger entry
answers no authorization question: the actor it belongs to is part of its *key*,
not something a policy decides, and no user ever reads one. Modelling it as a
resource would mean writing policies that correspond to nothing and then
bypassing them with `authorize?: false` on every claim and completion. The
`REVIEWED-UNAUTHORIZED` discipline is only worth having while every marked
bypass is genuinely interesting; spending three of them on bookkeeping devalues
the ones that matter.

Two consequences of that choice are load-bearing:

- **The claim is a reservation, not a lookup.** `claim/3` performs one
  `INSERT ... ON CONFLICT DO NOTHING` against the unique index on
  `(tenant_id, role_assignment_id, idempotency_key)`. The caller that wins the
  insert runs the mutation; everything else is answered from the row. Two copies
  of one request arriving together is the normal case — Section 14's offline
  outbox makes retries routine — and a read-then-write would execute both.
- **Expiry is enforced on read.** A record past `expires_at` is deleted and the
  request treated as fresh, whether or not a sweep has run. Section 24's
  twenty-four hours is a bound; a bound that holds only while a job is running
  is not one.
- **An in-progress claim carries a lease.** A claim and the mutation it guards
  are two operations, and a process can die between them: the mutation commits,
  the response is never recorded, and the key is left `IN_PROGRESS`. Without a
  lease that key is refused for the full retention window, and the client's only
  way forward — a fresh key — duplicates the mutation, which is precisely what
  the ledger exists to prevent. A claim older than the lease may therefore be
  taken over, conditionally, so two retries arriving together cannot both take
  it. The lease is far longer than any request this service should take, so a
  slow but living request never loses its claim.

Only a stored *response* is replayable. A rejected or failed mutation abandons
its claim, freeing the key, because storing a failure would make a transient
client error permanent for a day.

## Consequences

### Accepted

This is the only plain Ecto schema in `Dispatch.*`, so it is a visible exception
to a rule that is otherwise absolute. The moduledoc states why, and the rule
holds everywhere else.

Its migration is hand-written and not covered by `mix ash_postgres.generate_migrations
--check`, which tracks Ash resource snapshots. A change to the schema must be
paired with a migration by hand.

Scoping keys to the role assignment rather than to the tenant or the user
follows Section 23.3, which makes the assignment the unit of authority. Two
actors choosing the same key string is a collision with no meaning, not a
replay.

### Rejected alternatives

| Alternative | Why not |
| --- | --- |
| An Ash resource with policies | The policies would model nothing; every write would bypass them, diluting `REVIEWED-UNAUTHORIZED` |
| An Ash resource with no authorizer | Leaves an unauthorized resource in a domain where the absence of policies is the thing reviewers look for |
| Key scoped to tenant or user | Section 23.3 makes the role assignment the unit of authority; a broader scope collides across unrelated authority |
| Look up, then insert after the mutation | Loses the race that retries make routine, and executes the mutation twice |
| Rely on the retention sweep for expiry | A paused or unshipped job would leave day-old responses replaying indefinitely |
| Leave in-progress claims until they expire | A crash between the mutation and the record strands the key for a day, and the client's only recourse duplicates the mutation |
| Wrap the mutation and the ledger write in one transaction | Would work for a single-database mutation, but not once a mutation has any effect outside this database; the lease covers both and needs no distributed transaction |
| Store failed responses too | Makes a transient client error permanent for twenty-four hours |

## Verification

`Dispatch.IdempotencyTest` covers the claim/replay/reuse outcomes, the
in-progress race, key scoping across two actors, hash canonicalisation, and
expiry on read. `DispatchWeb.StatusEventControllerTest` covers the same
behaviour through `/v1`, including that a rejected request frees its key.
Ignoring the request hash makes the reuse test fail; ignoring `expires_at` makes
both retention tests fail.

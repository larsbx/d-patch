# Migrations

Section 22 sets two rules: migrations are generated through AshPostgres and the
SQL is reviewed into `central/priv/repo/migrations`, and **production MUST NOT
auto-migrate on boot**.

## Generate

```sh
cd central
mix ash_postgres.generate_migrations --name add_something
```

Read the generated SQL before committing it. The generator is a drafting tool,
not an authority — it cannot know that a column holds precise location, or that
a table must carry no `UPDATE` grant for application roles (§22.3).

Check before opening a pull request:

- Append-only tables (§22.3) grant application roles no `UPDATE` or `DELETE`.
- Every mutable aggregate table has `id`, `tenant_id`, `created_at`,
  `updated_at`, and `version`.
- Spatial columns are `geography(Point,4326)` or `geometry(LineString,4326)`
  with a GiST index where they are queried (§28.2).
- Identities that Section 22.3 requires for idempotency exist as real
  constraints, not as application checks.

The `migration-check` gate runs `mix ash_postgres.generate_migrations --check`
and fails when a resource change has no generated migration.

## Apply

Development:

```sh
make migrate
```

Production is a deliberate step, run against a release that is already deployed
but not yet serving the new schema:

```sh
bin/dispatch eval "Dispatch.Release.migrate()"
```

`/health/ready` reports `migrations_pending` until this completes, so a
partially migrated deployment is visible rather than silent.

## Rollback

Ash migrations are forward-only by default. A schema change that must be undone
is a new migration, for the same reason Section 12 makes corrections
compensating events: the history stays readable.

A destructive migration (dropping a column holding operational data) requires an
ADR and a backup verified before the change, not a rollback plan after it.

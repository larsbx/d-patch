# Local development

## Start

```sh
make up
```

Starts PostgreSQL/PostGIS, the local OIDC provider, and the central service, and
waits for every health check. This is the Slice 0 exit criterion.

Verify:

```sh
curl -s localhost:4000/health/live
curl -s localhost:4000/health/ready
curl -s localhost:5556/dex/.well-known/openid-configuration
```

`/health/ready` reports `unready` while migrations are pending.

The development stack applies them on start (`mix ash.setup && mix phx.server`
in `compose.yaml`), which is why `make up` is one command. Section 22 forbids
auto-migration on boot in *production* specifically; the production image runs
`bin/dispatch start` and never migrates itself, so there a pending migration is
a state an operator resolves deliberately. See
[`migrations.md`](migrations.md).

## Development accounts

The local OIDC provider (`infra/oidc/dex.yaml`) has one account per seeded role
profile — `driver@`, `dispatcher@`, `admin@`, `broker@`, `shipper@`,
`receiver@` at `dispatch.test`, password `dispatch`. These credentials are
published in this repository deliberately so they can never be confused with a
production secret. Dex stores them in memory; a restart resets everything.

## Seed data

```sh
make seed
```

Seeds a carrier tenant, the six role profiles of Section 23.2, and three
counterparty organizations. It deliberately seeds **no participants and no role
assignments**: Section 23.2 derives all authority from an assignment, so
creating one would grant access nobody asked for, and a test that depended on a
seeded grant would be testing the seed rather than the policy.

## Reset

```sh
make reset   # drop, recreate, migrate
make down    # stop and remove volumes
```

## Tests

```sh
make test-unit          # no database needed
make test-integration   # PostgreSQL/PostGIS required
make test-web           # the portal map element
make ci                 # every gate runnable without an Android SDK
```

## Adapters

A clean checkout selects the deterministic unconfigured adapters (ADR-0004), so
nothing here needs a Twilio, Google, or Hermes credential. Every call to an
unconfigured port returns an error rather than fabricated data.

To exercise a real provider locally, set its variable from `.env.example` and
restart the service. `Dispatch.Config` validates the selection at startup and
refuses to boot on a mismatch.

Inspect the current selection:

```sh
curl -s -H "Authorization: Bearer $DIAGNOSTICS_TOKEN" \
  localhost:4000/health/dependencies | jq
```

This reports which module each port selected and whether each required secret is
present. It never reports a secret's value (§31).

## Failure modes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Invalid configuration; refusing to start` | A Section 31 rule is violated; the message names the code and key | Fix the named variable |
| `/health/ready` returns 503, `migrations_pending` | Migrations not applied | `make migrate` |
| `/health/ready` returns 503, `database_unreachable` | Postgres not healthy | `make ps`, then `make logs` |
| `/health/dependencies` returns 401 | `DIAGNOSTICS_TOKEN` unset or under 32 bytes | Set it and restart |
| Integration tests fail on `postgis_version()` | Database created without PostGIS | `make down && make up` to re-run the init script |

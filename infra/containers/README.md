# Container images

## `central.Dockerfile`

Three targets:

| Target | Use |
| --- | --- |
| `dev` | The Compose service. Mounts source and runs `mix phx.server`. |
| `builder` | Compiles the production release with `--warnings-as-errors`. |
| `runtime` | The shipped OCI image (§19.1). |

Section 21.4 installs the configured agent runtime separately, so no runtime
vendor is baked in. Section 31 loads every secret at runtime, so none is
embedded.

## Local commands

```sh
# from the repository root
docker build -f infra/containers/central.Dockerfile --target runtime -t dispatch-central:local .
make build     # the same, as the container-build gate runs it
```

## Configuration

The runtime image reads its entire configuration from the environment. See
`.env.example`. Production startup fails closed when a selected adapter's
required secret is absent (§31).

## Health checks

The image declares a `HEALTHCHECK` against `/health/live`. Liveness is
process-only by design (§32): a slow dependency must not cause a restart loop.
Orchestrators should gate traffic on `/health/ready` instead.

## Test commands

```sh
docker run --rm dispatch-central:local /app/bin/dispatch eval "IO.puts(:ok)"
```

## Failure modes

| Symptom | Cause |
| --- | --- |
| Build fails in `builder` on a warning | `mix compile --warnings-as-errors` is deliberate |
| Container exits immediately with `Invalid configuration` | A Section 31 rule is violated; the message names the key |
| `/health/ready` never turns healthy | Migrations are pending; §22 forbids applying them on boot |

## `postgres-init/`

Runs once on first database creation: creates `dispatch_test` and installs
PostGIS, `uuid-ossp`, and `citext` in both databases. Section 28.2 makes PostGIS
canonical from the first migration, so it is never added later.

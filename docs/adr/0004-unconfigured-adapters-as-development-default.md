# ADR-0004: Ship deterministic unconfigured adapters as the development default

- **Status:** accepted
- **Date:** 2026-09-18
- **Deciders:** platform engineering, security
- **Specification sections:** §8.6, §27.1, §28.1, §29.0, §31, §35

## Context

Every provider port in Sections 27, 28, and 29 must have a module selected for
the application to start. A clean checkout has no Twilio, Google, or Hermes
credential, so something has to be selected there — and Section 35 forbids an
undocumented mock in production code.

## Decision

Ship `Dispatch.Integrations.Comms.Unconfigured.*`,
`Dispatch.Integrations.Geo.Unconfigured.*`, and the
`Dispatch.Integrations.Agent.Fake.*` adapters Section 29.0 already names, and
select them by default in development and test.

They never simulate success. Every operation returns a deterministic error, so a
developer sees an unconfigured provider rather than fabricated status —
Section 8.6 requires exactly that of the real fallback path, and Section 28.3
requires a routing failure to leave the previous route visibly stale rather than
invent an ETA.

`Dispatch.Config` rejects all three namespaces when `config_env() == :prod`.
Selecting one in production is a startup failure, not a silently dead channel.

## Consequences

### Accepted

Three module namespaces exist solely to be refused in production. The check that
refuses them is a string prefix match, so a replacement adapter placed in one of
those namespaces inherits the refusal — which is the intended behaviour.

### Rejected alternatives

| Alternative | Why not |
| --- | --- |
| Allow a nil adapter and check at call time | The failure moves from startup to the middle of a customer call |
| Adapters that return plausible fake data | Section 8.6 forbids inventing status; a developer would build against fiction |
| Require real credentials for development | `make up` on a clean checkout would be impossible, breaking the Slice 0 exit criterion |

## Verification

`Dispatch.ConfigTest` asserts that `ADAPTER_NOT_PRODUCTION_SAFE` is absent in
`:test` and present in `:prod`. The `unit` gate runs it.

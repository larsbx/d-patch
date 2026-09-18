# ADR-0002: Pin Elixir 1.19.6 on OTP 28.3.3

- **Status:** accepted
- **Date:** 2026-09-18
- **Deciders:** platform engineering
- **Specification sections:** §19.1

## Context

Section 19.1 fixes the central stack as Elixir/OTP with Phoenix and Ash but
names no version. The version still has to be pinned somewhere, because the
`format`, `typecheck`, and `container-build` gates all produce
version-dependent output: a formatter difference alone can fail CI on a
developer machine that is merely newer.

## Decision

Pin Elixir 1.19.6 on Erlang/OTP 28.3.3 in `.tool-versions`, in the CI matrix,
and in `infra/containers/central.Dockerfile`. Phoenix 1.8 with Bandit, and Ash
3.x with AshPostgres 2.x.

## Consequences

### Accepted

Upgrading the toolchain touches three files and must be a deliberate change.
That is the intent: an implicit upgrade that silently reformats the tree or
shifts Dialyzer's findings is worse than a noisy one.

### Rejected alternatives

| Alternative | Why not |
| --- | --- |
| Leave the version unpinned | `mix format --check-formatted` and `mix dialyzer` are not stable across minor versions |
| Pin only in CI | A developer's local `mix format` would then disagree with the gate |

## Verification

The `format`, `typecheck`, and `container-build` gates all run on the pinned
version. A drift between `.tool-versions` and the CI matrix surfaces as a
formatting or Dialyzer failure.

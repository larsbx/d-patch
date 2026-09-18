# ADR-0003: Use a placeholder Android application ID until Section 17 is decided

- **Status:** proposed
- **Date:** 2026-09-18
- **Deciders:** product, platform engineering
- **Specification sections:** §17, §19.1, §28.3

## Context

Section 17 lists the Android application package name as a decision required
before implementation, and it has not been made. It is not a cosmetic choice:
Section 28.3 restricts the Google Maps Android key by application ID and signing
certificate, so the value is part of a security control, and changing a
published application ID is not possible without a new listing.

## Decision

Use `com.dispatch.driver` as an explicit placeholder. Record it here rather than
leaving it undeclared, so the eventual decision is a change to a documented
value and not a discovery.

No Google Maps key may be provisioned against the placeholder. Section 28.3's
key restrictions are configured only after the real application ID exists;
Slice 2 owns that work and MUST NOT start it against this value.

## Consequences

### Accepted

`apps/android-driver/app/build.gradle.kts` and the Dex development client in
`infra/oidc/dex.yaml` both carry the placeholder and must change together.

### Rejected alternatives

| Alternative | Why not |
| --- | --- |
| Block Slice 0 until the decision is made | The decision does not affect any Slice 0 deliverable |
| Pick a plausible production ID now | A guessed ID that reaches a store listing or a Maps key restriction is expensive to undo |

## Verification

This ADR moves to `accepted` when Section 17's package-name decision is
recorded, and to `superseded` when the real ID lands. Until then the
`android-unit` gate builds against the placeholder and no Maps key exists.

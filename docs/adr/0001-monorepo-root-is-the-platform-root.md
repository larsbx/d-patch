# ADR-0001: The repository root is the monorepo root

- **Status:** accepted
- **Date:** 2026-09-18
- **Deciders:** platform engineering
- **Specification sections:** §20

## Context

Section 20 requires "a monorepo with this exact top-level layout" and draws it
rooted at a directory named `dispatch-platform/`. This repository is `d-patch`.
Reproducing the drawing literally would mean `d-patch/dispatch-platform/…`,
adding a level that every path, CI `working-directory`, and Compose build
context must then carry.

## Decision

The repository root *is* the `dispatch-platform/` root. Everything inside it —
`central/`, `apps/android-driver/`, `contracts/`, `infra/`, `docs/`, `Makefile`,
`compose.yaml`, `.env.example`, `README.md`, `LICENSE` — matches Section 20
exactly. Only the name of the containing directory differs.

## Consequences

### Accepted

A reader comparing the specification to the tree sees `d-patch/central/` where
the document says `dispatch-platform/central/`. The layout below that point is
identical, so no path inside the repository differs.

### Rejected alternatives

| Alternative | Why not |
| --- | --- |
| Nest a `dispatch-platform/` directory inside the repository | A redundant level in every path, CI job, and build context, bought only for a directory name |
| Rename the repository | Out of scope for an implementation slice, and would break existing clones and remotes |

## Verification

`scripts/check-layout.sh` asserts that every path in Section 20's tree exists
relative to the repository root. It runs in the `lint` gate.

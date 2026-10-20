# Capability-document verification key

`development.pub` is the base64url Ed25519 public key of the **published
development seed** in biz-kit's `apps/dispatch/config/{dev,test}.exs`. The
debug Android build pins it so a clean checkout verifies the documents a local
biz-kit signs. Production refuses that seed; a release build pins the
deployment's key (`-Pdispatch.capabilityPublicKey=…`) and refuses this one.

The format and its rationale: d-patch ADR-0009, and biz-kit
`docs/architecture/2026-10-20-dispatch-capability-document.md`.

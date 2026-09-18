# Provider fixtures

Recorded provider callbacks for the shared adapter contract suites (§33.2).

Each provider directory holds matched pairs: a correctly signed callback and a
forged or replayed variant. Section 33.2 requires forgery, replay, and
duplicate-delivery tests to fail closed or resolve idempotently, which needs
both halves.

## Rules

- No real phone number. Use E.164 numbers from a documented test range.
- No real provider credential, account identifier, or signing key.
- No content recorded without consent (§27.7).
- A fixture asserting a signature must carry the exact public URL it was signed
  against; Section 27.3 reconstructs that URL from trusted proxy configuration,
  and a fixture that omits it cannot test the reconstruction.

Fixtures land with Slice 4.

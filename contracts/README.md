# Contracts

The transport boundary (§19.1). Everything here is generated or hand-written as
a fixture; nothing here is a source of behaviour.

| Path | What it is |
| --- | --- |
| `openapi.json` | OpenAPI 3.1, generated from the central service's OpenApiSpex operations. Diff-checked by the `openapi-diff` gate and used to generate the Android client. Do not hand-edit. |
| `events/` | JSON Schemas for outbox event payloads (§22.5). Provider-neutral. |
| `fixtures/` | Signed and forged provider callback fixtures used by the shared adapter contract suites (§33.2). |

## Regenerating

```sh
cd central && mix openapi.generate
```

Encoding is byte-stable: keys are sorted before serialisation, so the gate fails
on a real change and never on map ordering.

## Fixtures

Section 33.2 requires signed and forged Twilio webhook fixtures, and Section
27.7 requires comparison against recorded, consent-compliant traffic rather than
duplicated production traffic. Fixtures land here with Slice 4.

A fixture MUST NOT contain a real phone number, a real provider credential, or
content recorded without consent.

## `biz-kit-dispatch.openapi.json`

The server's contract as the field app consumes it: a copy of biz-kit's
generated `apps/dispatch/contracts/openapi.json` (ADR-0010). Refresh it with
`scripts/sync-dispatch-contract.sh`; `--check` fails when it is stale. The
Android `ContractConformanceTest` holds the client's DTOs to it.

It does not go stale silently. CI's `android-unit` refreshes it from the paired
biz-kit branch (the same-named branch, else main) before the tests, so the
client is always held to the server's contract as it stands; and `Vendored
dependency refresh` proposes the refreshed copy as a pull request daily when
biz-kit main has changed it.
`openapi.json` above remains `central/`'s.

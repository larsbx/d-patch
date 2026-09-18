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

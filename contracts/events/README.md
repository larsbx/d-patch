# Event schemas

JSON Schemas for `outbox_events.payload_json` (§22.5).

Outbox rows are written in the same transaction as the domain change they
describe (§21.1), so a schema here is part of the durability contract rather
than documentation of it.

Rules:

- Provider-neutral. No Twilio SID, Google Place ID, Hermes session handle, or
  other vendor identifier appears in a payload (§§27.1, 28.2, 29.0).
- `snake_case` field names, uppercase ASCII enum values, UUIDv7 identifiers,
  RFC 3339 UTC timestamps with millisecond precision (§19.2).
- `additionalProperties: false`. Section 29.0 rejects extra properties on tool
  requests; event payloads hold the same line.
- No payload carries a priority, severity, or urgency field (§27.6).

Schemas land with the slices that emit their events, starting at Slice 1.

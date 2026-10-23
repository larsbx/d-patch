# ADR-0013: Location samples carry fixed-point integers, and are always signed

- **Status:** accepted
- **Date:** 2026-10-22
- **Deciders:** platform engineering
- **Specification sections:** §6.1, §24.2, §25.3, §28.2

## Context

§24.2 has each uploaded sample carry `lat`, `lng` and `accuracy_m` (with
optional speed and bearing) and a signature. Devices sign over
`CoopEventCanonicalV1` (ADR-0009, ADR-0012). That form is deterministic CBOR
with no floats, on purpose: a signature needs the same bytes on the handset
and on the server. There is no float-to-text rendering that Android's runtime
and the BEAM are guaranteed to share, so a float field cannot be signed
reliably.

## Decision

1. **Fixed-point integers on the wire.** A sample sends `lat_e7` and `lng_e7`
   (degrees × 10⁷, about 1 cm), `accuracy_cm`, `speed_cmps` and
   `bearing_cdeg`. The server converts them to the canonical
   `geography(Point,4326)` and metres once, on ingest. Every other §24.2
   field keeps its name.
2. **Always signed.** Unlike a status declaration made from the portal, a
   sample only ever comes from a registered handset. A sample without a valid
   signature by the participant's own active device is refused
   (`DEVICE_SIGNATURE_REQUIRED` or `DEVICE_SIGNATURE_INVALID`). The signed
   statement is the sample's fields, less the signature, with
   `schema: "DispatchLocationSampleV1"`.
3. **The revocation race is decided by the signed acquisition time.** A sample
   is accepted when its consent grant, for its assignment, covered
   `occurred_at`. One acquired before revocation and uploaded after is kept;
   one acquired after is `REJECTED_CONSENT` (acceptance criterion 4). The
   "known skew bound" of §24.2 is the five-minute future bound status
   declarations already have.
4. **The route is the specification's.** `POST /v1/me/location-samples:batch`
   and the DRIVER projection `/v1/driver/location-samples:batch`, answering
   `207` with one result per sample.

## Consequences

- The Kotlin client converts once, at acquisition (`Math.round(lat * 1e7)`).
  It never re-derives a signed value from a stored float.
- Shared signing vectors (`device-signing-vectors.json`, `location_sample`)
  hold the client and server to the same bytes, including negative integers.
- Precision is about 1 cm for position and 1 cm for accuracy, well below
  any fix a phone reports.

# Data retention

Section 13 requires configurable retention for location, transcripts,
recordings, notifications, and biometric attestations. Section 22.3 makes
operational records append-only: a correction appends a compensating event, and
deletion is performed only by the dedicated retention role and is itself
audited.

Slice 8 implements the retention worker. This document defines the classes it
will act on; the numeric periods are a product, legal, and jurisdictional
decision (§17) and are deliberately not invented here.

## Retention classes

| Class | Contents | Period | Deletion behaviour |
| --- | --- | --- | --- |
| `OPERATIONAL_EVENT` | Status events, stop readiness, geofence events | Undecided (§17) | Append-only; retention delete only |
| `LOCATION` | `location_samples` | Undecided (§17) | Deleted wholesale; no aggregation retained by default |
| `COMMUNICATION_CONTENT` | Message bodies, call turns | Undecided (§17) | Ciphertext deleted; the conversation shell and provider references survive |
| `CALL_RECORDING` | Recordings, when enabled | Undecided (§17) | Disabled by default (§13); deletion and access handling required if enabled |
| `EXTERNAL_NOTIFICATION` | ELD and other Android notices | Undecided (§17) | Redacted payload deleted |
| `BIOMETRIC_ATTESTATION` | `face_verification_attestations` | `FACE_ATTESTATION_RETENTION_DAYS` | Deleted by the audited retention worker (§7.6) |
| `CONSENT_RECEIPT` | Consent grants, deletion receipts | Legal minimum | May be retained as legally required, without any biometric material |
| `AUDIT` | `audit_events`, `breakglass_events` | Undecided (§17) | Hash-chained; deletion must preserve chain verifiability |

## Invariants

- No retention class contains a face image, video, template, embedding,
  landmark, similarity score, or liveness telemetry. None of these ever reaches
  the server (§7.2, §7.6).
- Deleting an attestation does not delete the consent or deletion receipt that
  records the participant's decision.
- Application roles hold no `UPDATE` or `DELETE` on append-only tables. The
  retention role is separate and its actions are audited (§22.3).
- Retention deletion is a named Ash action invoked by a job, never a direct
  table write (§21.3).
- A shortened period never retroactively deletes material a legal hold covers;
  hold handling is an explicit input to the worker.

## Location, specifically

Section 6.1 scopes collection to the active assignment plus a configurable grace
period, and Section 6.3 scopes visibility by role and time. Retention is the
third control and is independent of both: a sample may be inside its retention
period and still be invisible to every broker, because the sharing window closed.

## Open decisions (§17)

Location retention and broker visibility windows; transcript and recording
retention; attestation retention period; audit retention; and the jurisdictions
whose biometric, call-recording, SMS, and employment rules apply. Each becomes a
value in the table above and a test asserting the worker honours it.

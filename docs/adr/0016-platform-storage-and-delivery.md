# ADR-0016: Clients consume platform storage and delivery contracts

Date: 2026-10-30 (America/Chicago).
Status: **Selected integration target; implementation and cutover gated.**

Shared contract: [biz-kit storage/consolidation ADR](https://github.com/larsbx/biz-kit/blob/adf9d7d7e03e80af650124ce0fdfd02a232cb9b3/docs/architecture/2026-10-30-postgres-event-log-redis-consolidation.md)
([PR #207](https://github.com/larsbx/biz-kit/pull/207)); financial boundary:
[hawala-core ADR 0007](https://github.com/larsbx/hawala-core/pull/57).
These records specify design, not runtime acceptance.

## Context

Accepted [ADR-0010](0010-the-server-is-biz-kit-apps-dispatch.md) assigns the backend to biz-kit and freezes central/. [PR #36](https://github.com/larsbx/d-patch/pull/36) proposes ADR-0015 separately; it is not assumed merged.

## Decision

- Server storage, Redis workers and migrations belong in biz-kit. Android
  uses authenticated APIs, not Redis/SQL/TB credentials. The frozen backend
  acquires no new storage features.
- Preserve durable offline intent, original mutation IDs, local device keys and
  exact signed fixed-point observations. Cache expiry cannot erase client intent.
- Resumable views expose source watermark and freshness. Detect notification gaps
  and fetch bounded authoritative state; delivery order is not admission order.
- Current server-side session/device/role and trip-consent checks remain required
  despite stale roster/location caches. Presence cannot restore revoked access.
- Sync generated OpenAPI server first and pin tested client/backend revisions.
  Replace full-board polling only after reconnect and bounded catch-up acceptance.
- Remove central/ build/deploy lanes only when every retained compatibility/e2e
  obligation targets actual biz-kit. Do not develop a second server here.
- Financial displays retain verified provenance/freshness; a client cannot
  create a new financial ID to retry an uncertain payout.

## Acceptance and rollout

- Restart with pending offline commands across Redis loss/lost replies and
  preserve original IDs with one admitted mutation.
- Drop/duplicate/reorder notifications; recover gaps without overwriting
  participant declarations with observations or AI inference.
- Refuse revoked access despite stale projections. Run OpenAPI/DTO conformance
  and e2e against pinned biz-kit before snapshot retirement.

Keep current permissions, services and durable owners until verified replacement,
recovery and single-writer cutover. Recorded historical acceptance does not certify
new adapters. A documentation merge alone moves no live authority.

## Source contracts

[ADR-0010](0010-the-server-is-biz-kit-apps-dispatch.md), [device keys](0012-device-keys-are-self-registered-ed25519.md) and [fixed-point samples](0013-location-samples-carry-fixed-point-integers.md).

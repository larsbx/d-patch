# ADR-0014: Location cadence follows demand

- **Status:** accepted
- **Date:** 2026-10-22
- **Deciders:** platform engineering, product owner
- **Specification sections:** §6.1, §23.2, §24.5, §25.3 (amends its sampling and upload defaults)

## Context

§25.3 fixes one cadence for a sharing device: a sample every 30 seconds while
moving, uploads in batches of 25 or every 60 seconds. That is too slow for
someone actually following a driver on a map, and wasteful for the hours
nobody is. The product owner asked for location to be real time exactly while
an authorized person is consuming it, and event driven rather than polled.

## Decision

1. **Demand is a precise-location reader watching.** A viewer creates demand
   only while they hold `operations.location.precise.read`, the participant
   is in their organization, and the participant's consent is active now
   (§23.2: precise location needs the capability *and* consent). Readers
   without it, freshness-only dispatchers among them, create none. Such a
   reader's participant page shows the live coordinates (five decimals); no
   one else's does.
2. **Watching is a live stream, not a flag.** The viewer's participant stream
   (§24.5) joins a cluster-wide `:pg` group for the participant while those
   conditions hold, re-deciding at every tick, and leaves when the stream ends.
   A closed tab, crashed process or lost node ends the watch without anyone
   remembering to; the server notices a closed tab at its next write, which
   while the device is live is its next upload.
3. **The phone holds a stream too.** `GET /v1/me/location-demand` is
   server-sent events for the sharing participant. Each `location-demand`
   event carries the policy to apply until the next one:
   `{"mode": "realtime", "sample_interval_ms": 3000, "upload_interval_ms": 0}`
   while anyone watches (upload each sample at once), otherwise
   `{"mode": "trickle", "sample_interval_ms": 120000, "upload_interval_ms": 600000}`.
   An event is sent on opening and on each change; nothing names the watchers.
   The values are server configuration (`:location_demand`).
4. **The stream lives exactly as long as sharing.** Without an active consent
   it is a `404 NO_ACTIVE_CONSENT`; it is revalidated at every heartbeat and on
   every change to the participant, and closes when the consent or role
   assignment ends. A device whose stream closes, or that cannot reach it,
   falls back to trickle.
5. **Uploads are pushed, as status is.** Each accepted batch already enqueues
   an outbox refresh on the participant's topics; open pages patch within the
   outbox's poll interval. "Real time" therefore means a few seconds end to
   end: one sample interval plus one outbox poll.

## Consequences

- §25.3's 30 s / 25-or-60 s defaults are replaced by the two modes above.
  Revocation, the foreground notification, low accuracy and the reboot rule
  are unchanged.
- The trickle is deliberately slower than §25.3's default: freshness ("last
  reported 4 min ago") stays meaningful for the roster at a fraction of the
  battery, and anyone who needs more opens the participant.
- The Android foreground service (Slice 2's next part) consumes the stream;
  until then `e2e/support/field-app.js` speaks it, and `location.spec.js`
  covers trickle → realtime → live coordinates → trickle → closed.
- Watch membership is cluster-wide (`:pg`), so a viewer and a device served
  by different nodes still find each other.

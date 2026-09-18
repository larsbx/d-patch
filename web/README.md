# Portal JavaScript

Section 26.1 forbids general application JavaScript in the portal. The map
custom element and its provider adapters are the single permitted exception;
this directory holds their tests.

The source lives with the assets it ships as, under
`central/priv/static/js/maps/`:

| File | Role |
| --- | --- |
| `participant-map.js` | The provider-neutral `<participant-map>` element (§26.6) |
| `map-freshness.js` | Staleness thresholds shared by every adapter (§26.6) |
| `google-adapter.js` | Initial Google presentation adapter (§28.5) |

## Local commands

```sh
node --test test/*.test.js     # the web-unit gate
```

No build step, no bundler, no `node_modules`. The modules are plain ESM served
directly by Phoenix.

## Configuration

The element reads only `data-participant-id`, `data-map-version`, and
`data-map-provider`. Section 26.6 keeps coordinates out of the DOM entirely:
they are fetched from `/ui/participants/{id}/map-data` and held in memory, never
written to an attribute, a Datastar signal, analytics, or a client log.

## Health checks

The element has no health surface. Its observable contract is that an
unauthorized or failed map-data request clears the map rather than leaving the
last authorized fix on screen.

## Failure modes

| Symptom | Cause |
| --- | --- |
| Map renders blank, console reports a CSP violation | The adapter's origins are missing from `infra/reverse-proxy/Caddyfile` |
| Map never updates | `data-map-version` is not changing; the server drives redraws through a Datastar patch |
| Marker stays after access is revoked | A regression: a non-OK map-data response must call `clear()` |

## Adding an adapter

Implement `{render, clear, destroy?}`, call `registerMapAdapter(name, factory)`,
and add the name to the allowlist in `Dispatch.Integrations.AdapterRegistry`.
Section 28.5's MapLibre migration target should require no change to any HEEx
component, fragment ID, Datastar signal, or Ash resource.

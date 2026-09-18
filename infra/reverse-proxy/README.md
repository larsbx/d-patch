# Reverse proxy

Development-only front for the central service.

It exists to make one production behaviour testable locally: Section 27.3
requires a provider webhook signature to be validated against the exact public
URL, reconstructed from trusted proxy configuration. Running the app directly on
`localhost:4000` hides every mistake in that reconstruction.

## Local commands

```sh
docker run --rm -p 8080:8080 \
  -v "$PWD/infra/reverse-proxy/Caddyfile:/etc/caddy/Caddyfile:ro" \
  --network dispatch-platform_default caddy:2-alpine
```

## Configuration

| Setting | Purpose |
| --- | --- |
| `X-Forwarded-Proto` / `-Host` / `-Port` | Inputs to public-URL reconstruction (Section 27.3) |
| `Content-Security-Policy` | Section 26.1: scripts from self plus the configured map adapter's origins; no inline scripts |

Switching the map presentation adapter (Section 28.5) requires updating and
testing the CSP here. It does not require a page-template change.

## Health checks

`GET http://localhost:8080/health/live` should return `200` and proxy to the
central service.

## Test commands

The `playwright` gate runs against the proxied origin so CSP violations surface
in CI rather than in production.

## Failure modes

| Symptom | Cause |
| --- | --- |
| Provider webhooks fail signature validation | Forwarded headers are being rewritten or dropped |
| Map renders blank, console reports a CSP violation | `script-src` / `connect-src` lack the adapter's origins |
| `502` from the proxy | `central` is not on the Compose network, or is not yet healthy |

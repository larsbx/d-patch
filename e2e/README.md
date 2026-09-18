# End-to-end tests

Playwright suite for the twenty-eight scenarios in Section 33.4. Slice 0 covers
the health surface; each later slice adds the scenarios its exit criterion
names.

## Run

```sh
make up                 # the stack must be running
npm ci
npx playwright install --with-deps chromium
npx playwright test
```

The suite targets the reverse proxy on `:8080`, not the application on `:4000`.
Section 27.3 reconstructs the public webhook URL from forwarded headers and
Section 26.1 pins a CSP; neither is exercised by talking to the application
port. Override with `E2E_BASE_URL` to test a different origin. Set
`DIAGNOSTICS_TOKEN` to exercise the authenticated dependency report.

## Projects

| Project | Purpose |
| --- | --- |
| `chromium` | The ordinary suite |
| `no-javascript` | Section 26.7: pages stay readable and forms complete through normal server navigation when Datastar never loads |

## Scenario coverage

| Section 33.4 scenarios | Slice |
| --- | --- |
| Health and readiness (Slice 0 exit criterion) | 0 ✅ |
| 1, 13–19 — identity, role profiles, status | 1 |
| 2, 10 — location, maps, provider replacement | 2 |
| 3–6 — calls, proposals, equal priority, runtime outage | 4, 5 |
| 8, 9 — Datastar patching and progressive behaviour | 1 |
| 11, 12 — adapter replacement | 4, 5 |
| 20–23 — break-glass | 8 |
| 24–28 — biometric identity assurance | 7 |

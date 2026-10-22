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

## Against biz-kit (Slice 1)

The server now lives in biz-kit (ADR-0010), so Slice 1's exit criterion runs
against biz-kit's one-command stack rather than Compose: dispatch on `:4000`,
SpruceGoose's `/auth` on `:4001`, a seeded carrier with a `DRIVER`, a
`DISPATCHER`, a `COURIER`, two `ADMIN`s and a `BROKER` of an unrelated load,
the driver's planned trip, and a development identity provider that signs
each of them in. Each person signs in once per run and keeps the session, as
the app does; `/auth` limits sign-in requests per client, rightly.

```sh
# in a biz-kit checkout beside this one (PostgreSQL with PostGIS on :5432)
scripts/e2e-stack.sh

# here
npm ci
npx playwright install --with-deps chromium
npx playwright test -c biz-kit.config.js
```

`BIZ_KIT_DIR` points elsewhere (default `../../biz-kit` from `e2e/support`);
`CHROMIUM_PATH` uses an installed Chromium. The portal runs in Chromium; the
field app's side is `support/field-app.js`, which speaks the app's protocol
byte for byte: PKCE sign-in, the capability document, an Ed25519 device key
registered with dispatch, and declarations signed when queued and sent later,
checked against biz-kit's own signing vectors (`biz-kit/signing.spec.js`).

The installed app itself runs against the same stack on an emulator
(`apps/android-driver/app/src/androidTest`, CI job `android-e2e-biz-kit`), once
as the driver and once as the courier: sign-in, the Keystore-sealed device
key, and the outbox across airplane mode. Start the stack with
`SPRUCE_GOOSE_PUBLIC_URL=http://10.0.2.2:4001`, then
`./gradlew :app:connectedDebugAndroidTest -Pdispatch.authProvider=driver`
(or `=courier`).

| Scenario | Online | After an offline interval | Where |
| --- | --- | --- | --- |
| 1 — sets `EN_ROUTE_PICKUP`, the portal patches without a reload | ✅ | ✅ queued, signed, sent in order; the latest made wins | `slice1.spec.js`, emulator |
| 1 — starts assignment sharing | — | — | Slice 2 (location) |
| 13 — the `DRIVER` assignment decides features and verdicts | ✅ | ✅ | `slice1.spec.js`, emulator |
| 14 — a second role profile without branching: participant, device, assignment, status | ✅ | ✅ (emulator) | `slice1.spec.js`, emulator as courier |
| 14 — consent, location, approval and communications resources | — | — | Slices 2, 4, 5 |
| 15 — an administrator manages assignments, with no operational page until another grants one | ✅ | — | `slice1.spec.js` |
| 15 — precise map and message content | — | — | Slices 2, 4 |
| 2 — a GPS sample appears with accuracy and age; an unrelated broker gets nothing | ✅ (protocol) | — | `location.spec.js` |
| Acceptance criterion 4 — revoking stops sharing, except what was acquired before | ✅ | ✅ | `location.spec.js` |
| 10 — geocoder, router and map adapters replaced independently | — | — | Slice 2, next |
| 16 — a dispatcher loses the stream when the relationship ends | ✅ within one heartbeat | — | `slice1.spec.js` |
| 17–19 — shipper, receiver, broker actions | — | — | pages are read-only; next |
| ADR-0011 — the session lasts until sign-out | ✅ | ✅ | `slice1.spec.js`, emulator |

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

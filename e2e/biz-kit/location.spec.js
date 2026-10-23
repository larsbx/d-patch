// Slice 2's consented location sharing against biz-kit's stack: scenario 2,
// demand-driven real time (ADR-0014), and acceptance criterion 4's revocation
// race, through the field app's protocol and the dispatcher's portal. Before slice1.spec.js on purpose: scenario 16
// there ends the dispatcher's assignment.
//
// Not here yet: the map itself (scenario 10's provider replacement) and the
// installed app's foreground service; both arrive with later parts of Slice 2.

import { test, expect } from "@playwright/test";
import { FieldApp } from "../support/field-app.js";
import { signInToPortal } from "../support/portal.js";
import { person } from "../support/stack.js";

test.describe.configure({ mode: "serial" });

const driver = person("driver");
const dispatcher = person("dispatcher");
const broker = person("broker");
const tracker = person("tracker");

const LONDON = { lat: 51.5074, lng: -0.1278 };
const PARIS = { lat: 48.8566, lng: 2.3522 };

async function sharingDriver(baseURL) {
  const app = await FieldApp.signedIn(baseURL, driver);
  expect((await app.registerDevice()).status()).toBeLessThan(300);
  const consent = await app.beginLocationConsent(driver.trip_id);
  expect(consent.status).toBe(201);
  // What the app reads to choose a trip, and to resume after a reboot (§25.3).
  const { body } = await app.assignments();
  const trip = body.assignments.find((a) => a.assignment_id === driver.trip_id);
  expect(trip.location_consent?.consent_grant_id).toBe(consent.body.consent_grant_id);
  return { app, grantId: consent.body.consent_grant_id };
}

// The device in realtime: a sample each interval until the policy changes.
async function untilDemand(app, stream, grantId, { intervalMs = 3_000, attempts = 15 } = {}) {
  for (let i = 0; i < attempts; i++) {
    await app.uploadSamples([app.acquire({ grantId, assignmentId: driver.trip_id, ...PARIS, accuracyM: 5 })]);
    const event = await stream.next(intervalMs).catch(() => undefined);
    if (event !== undefined) return event;
  }
  throw new Error("the demand never changed");
}

test("2: a GPS sample appears with its accuracy and age; an unrelated broker cannot fetch it", async ({ page, browser, baseURL }) => {
  await signInToPortal(page, baseURL, dispatcher);
  await page.goto(`/operations/participants/${driver.participant_id}`);
  const card = page.locator("#participant-location-card");
  await expect(card).toContainText("No location shared");
  await page.evaluate(() => (window.__e2eNoReload = true));

  const { app, grantId } = await sharingDriver(baseURL);
  const sample = app.acquire({ grantId, assignmentId: driver.trip_id, ...LONDON, accuracyM: 12.4 });
  const { status, results } = await app.uploadSamples([sample]);
  expect(status).toBe(207);
  expect(results).toEqual([{ sample_id: sample.sample_id, result: "ACCEPTED", code: null }]);

  // Patched in place: an observation, with its accuracy and age, not where.
  await expect(card).toContainText("Last reported", { timeout: 30_000 });
  await expect(card).toContainText("±12 m");
  await expect(card.locator('[data-source="OBSERVED"]')).toHaveCount(1);
  expect(await card.innerText()).not.toMatch(/51\.50|0\.127/);
  expect(await page.evaluate(() => window.__e2eNoReload === true)).toBe(true);

  // A broker of another load: no page, no stream.
  const elsewhere = await browser.newContext({ baseURL });
  const brokerPage = await elsewhere.newPage();
  await signInToPortal(brokerPage, baseURL, broker);
  expect((await brokerPage.goto(`/operations/participants/${driver.participant_id}`)).status()).toBe(404);
  // From the broker's own browser, with its session, and no redirect followed.
  const streamStatus = await brokerPage.evaluate(
    (url) => fetch(url, { redirect: "manual" }).then((r) => r.status),
    `/ui/participants/${driver.participant_id}/stream`,
  );
  expect(streamStatus).toBe(404);
  await elsewhere.close();
});

test("ADR-0014: the device shares in real time exactly while a precise reader watches", async ({ page, browser, baseURL }) => {
  const { app, grantId } = await sharingDriver(baseURL);
  const { status, stream } = await app.openDemand();
  expect(status).toBe(200);
  expect((await stream.next()).data).toMatchObject({ mode: "trickle" });

  // A dispatcher without precise location looks: no demand, no coordinates.
  const plain = await browser.newContext({ baseURL });
  const plainPage = await plain.newPage();
  await signInToPortal(plainPage, baseURL, dispatcher);
  await plainPage.goto(`/operations/participants/${driver.participant_id}`);
  await expect(plainPage.locator("#participant-location-card")).toContainText("Last reported");

  // One who may see where looks: the device hears within seconds.
  await signInToPortal(page, baseURL, tracker);
  await page.goto(`/operations/participants/${driver.participant_id}`);
  const realtime = await stream.next();
  expect(realtime).toEqual({
    event: "location-demand",
    data: { mode: "realtime", sample_interval_ms: 3000, upload_interval_ms: 0 },
  });

  // Each sample, sent as acquired, moves the watcher's page in place.
  await app.uploadSamples([app.acquire({ grantId, assignmentId: driver.trip_id, ...PARIS, accuracyM: 5 })]);
  await expect(page.locator("#participant-live-position")).toContainText("48.85660, 2.35220", { timeout: 10_000 });
  await expect(plainPage.locator("#participant-location-card")).toContainText("±5 m", { timeout: 10_000 });
  expect(await plainPage.locator("#participant-live-position").count()).toBe(0);
  await plain.close();

  // The watcher leaves. Their stream learns it at its next write; while the
  // device is live that is its next upload, every few seconds.
  await page.goto("/operations");
  expect((await untilDemand(app, stream, grantId)).data).toMatchObject({ mode: "trickle" });

  // Sharing ends: so does the stream.
  await app.revokeLocationConsent(grantId);
  expect(await stream.next()).toBeNull();
});

test("acceptance criterion 4: revoking stops sharing, except what was acquired before", async ({ baseURL }) => {
  const { app, grantId } = await sharingDriver(baseURL);

  // Queued offline, then the driver stops sharing before it is sent.
  const acquiredBefore = app.acquire({ grantId, assignmentId: driver.trip_id, ...LONDON, accuracyM: 8 });
  const revoked = await app.revokeLocationConsent(grantId);
  expect(revoked.status).toBe(200);
  expect(revoked.body.revoked_at).not.toBeNull();
  const acquiredAfter = app.acquire({ grantId, assignmentId: driver.trip_id, ...LONDON, accuracyM: 8 });

  const { results } = await app.uploadSamples([acquiredBefore, acquiredAfter]);
  expect(results.map((r) => [r.result, r.code])).toEqual([
    ["ACCEPTED", null],
    ["REJECTED", "REJECTED_CONSENT"],
  ]);
});

// Slice 2's consented location sharing against biz-kit's stack: scenario 2 and
// acceptance criterion 4's revocation race, through the field app's protocol
// and the dispatcher's portal. Before slice1.spec.js on purpose: scenario 16
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

const LONDON = { lat: 51.5074, lng: -0.1278 };

async function sharingDriver(baseURL) {
  const app = await FieldApp.signedIn(baseURL, driver);
  expect((await app.registerDevice()).status()).toBeLessThan(300);
  const consent = await app.beginLocationConsent(driver.trip_id);
  expect(consent.status).toBe(201);
  return { app, grantId: consent.body.consent_grant_id };
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

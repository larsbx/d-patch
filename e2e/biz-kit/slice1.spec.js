// Section 33.4 scenarios of Slice 1's exit criterion, against biz-kit's stack:
// the portal in Chromium, the field app's protocol as the app speaks it.
// "Online and after an offline interval" is the second describe block.
//
// Not here, because their surfaces do not exist yet in biz-kit (see the
// README's coverage table): 1's location sharing (Slice 2), 14's second role
// profile (test-only in biz-kit), 15, and 17–19's partner actions.

import { test, expect } from "@playwright/test";
import { FieldApp } from "../support/field-app.js";
import { signInToPortal } from "../support/portal.js";
import { fixture, person, stack } from "../support/stack.js";

test.describe.configure({ mode: "serial" });

const driver = person("driver");
const dispatcher = person("dispatcher");

const currentStatus = (page) =>
  page.locator(`#operations-participant-${driver.participant_id} .participant-current-status`);

// A marker that a full page load would wipe: the portal must patch, not reload.
const markNoReload = (page) => page.evaluate(() => (window.__e2eNoReload = true));
const stillNoReload = (page) => page.evaluate(() => window.__e2eNoReload === true);

async function signedInDriver(baseURL) {
  const app = await FieldApp.open(baseURL, driver);
  await app.signIn();
  expect((await app.registerDevice()).status()).toBeLessThan(300);
  return app;
}

test.describe("online", () => {
  test("13: the DRIVER assignment, not a user type, decides what the app offers and the server permits", async ({ baseURL }) => {
    const app = await FieldApp.open(baseURL, driver);
    const grant = await app.signIn();

    // ADR-0011: the session lasts until the person signs out.
    expect(grant).not.toHaveProperty("expires_in");
    expect(await app.principal()).toBe(driver.principal);

    const { claims } = await app.capabilities();
    expect(claims.role.key).toBe("DRIVER");
    expect(claims.role_assignment_id).toBe(driver.role_assignment_id);
    expect(claims.participant_id).toBe(driver.participant_id);
    expect(claims.features).toContain("status");
    expect(claims.status_options.map((o) => o.value)).toContain("EN_ROUTE_PICKUP");

    // Another assignment, another feature set and another verdict, from the
    // same endpoints.
    const desk = await FieldApp.open(baseURL, dispatcher);
    await desk.signIn();
    const deskDocument = await desk.capabilities();
    expect(deskDocument.claims.role.key).toBe("DISPATCHER");
    expect(deskDocument.claims.features).not.toContain("status");

    desk.declare("EN_ROUTE_PICKUP");
    const [refused] = await desk.sync();
    expect(refused).toMatchObject({ status: 403, body: { code: "FORBIDDEN" } });
  });

  test("1: a driver sets EN_ROUTE_PICKUP in the app and the dispatcher's portal updates", async ({ page, baseURL }) => {
    await signInToPortal(page, baseURL, dispatcher);
    await page.goto("/operations");
    await expect(page.locator(`#operations-participant-${driver.participant_id}`)).toBeVisible();
    await markNoReload(page);

    const app = await signedInDriver(baseURL);
    app.declare("EN_ROUTE_PICKUP");
    const [stored] = await app.sync();

    expect(stored.status).toBe(201);
    expect(stored.body.event.verification).toBe("DEVICE_AUTHENTICATED");
    expect(stored.body.current_status.value).toBe("EN_ROUTE_PICKUP");

    await expect(currentStatus(page)).toContainText("EN_ROUTE_PICKUP", { timeout: 30_000 });
    expect(await stillNoReload(page)).toBe(true);
  });
});

test.describe("after an offline interval", () => {
  test("1 and 13: declarations queued offline arrive signed and in order, and the latest made wins", async ({ page, baseURL }) => {
    await signInToPortal(page, baseURL, dispatcher);
    await page.goto("/operations");
    await markNoReload(page);
    const app = await signedInDriver(baseURL);

    // Offline: declared and signed on the device, nothing sent.
    const startedAt = Date.now();
    const atPickup = app.declare("AT_PICKUP", { occurredAt: new Date(startedAt) });
    const loading = app.declare("LOADING", { occurredAt: new Date(startedAt + 1_000) });
    await page.waitForTimeout(5_000);
    await expect(currentStatus(page)).not.toContainText("LOADING");

    // Back online, the same session: sent unchanged, oldest first.
    const answers = await app.sync();
    expect(answers.map((a) => a.status)).toEqual([201, 201]);
    for (const [answer, sent] of [[answers[0], atPickup], [answers[1], loading]]) {
      expect(answer.body.event.id).toBe(sent.event_id);
      expect(answer.body.event.verification).toBe("DEVICE_AUTHENTICATED");
      expect(Date.parse(answer.body.event.occurred_at)).toBe(Date.parse(sent.occurred_at));
    }
    await expect(currentStatus(page)).toContainText("LOADING", { timeout: 30_000 });

    // An upload made late does not displace a newer declaration.
    app.declare("AT_PICKUP", { occurredAt: new Date(startedAt - 60_000) });
    const [late] = await app.sync();
    expect(late.status).toBe(201);
    expect(late.body.current_status.value).toBe("LOADING");

    // A retry of a delivered declaration is the same event, not a second one.
    app.outbox.push(loading);
    const [retry] = await app.sync();
    expect(retry.body.event.id).toBe(loading.event_id);

    expect(await stillNoReload(page)).toBe(true);
  });

  test("the session lasts until the person signs out, and no longer", async ({ baseURL }) => {
    const app = await signedInDriver(baseURL);
    expect((await app.capabilities()).status).toBe(200);

    expect((await app.signOut()).status()).toBe(204);
    expect((await app.capabilities()).status).toBe(401);
  });
});

test.describe("relationship changes", () => {
  test("16: a dispatcher loses the operations stream when the relationship ends", async ({ page, baseURL }) => {
    await signInToPortal(page, baseURL, dispatcher);
    await page.goto("/operations");

    // The dispatcher's own browser holds the stream, with the portal's session.
    // It ends; the reconnect is refused, so EventSource gives up (CLOSED).
    await page.evaluate(() => {
      window.__e2eStream = new Promise((resolve) => {
        const source = new EventSource("/ui/operations/stream");
        source.addEventListener("open", () => (window.__e2eOpened = true));
        source.addEventListener("error", () => source.readyState === EventSource.CLOSED && resolve(Date.now()));
      });
    });
    await page.waitForFunction(() => window.__e2eOpened === true);

    fixture("revoke", stack.tenant_id, dispatcher.role_assignment_id);
    const revokedAt = Date.now();

    // Revalidated on every heartbeat (20 s): closed by the next one.
    const closedAt = await page.evaluate(() => window.__e2eStream);
    expect(closedAt - revokedAt).toBeLessThan(25_000);
    const after = await page.goto("/operations");
    expect(after.url()).not.toMatch(/\/operations$/);
  });
});

// Section 33.4 scenarios of Slice 1's exit criterion, against biz-kit's stack:
// the portal in Chromium, the field app's protocol as the app speaks it.
// "Online and after an offline interval" is the second describe block.
//
// Not here, because their surfaces do not exist yet in biz-kit (see the
// README's coverage table): 1's location sharing (Slice 2), 14's consent,
// location, approval and message resources, 15's precise map and message
// content (it is shown on the operational pages instead), and 17–19's partner
// actions.

import { test, expect } from "@playwright/test";
import { FieldApp } from "../support/field-app.js";
import { chooseRole, signInToPortal } from "../support/portal.js";
import { fixture, person, stack } from "../support/stack.js";

test.describe.configure({ mode: "serial" });

const driver = person("driver");
const dispatcher = person("dispatcher");
const courier = person("courier");
const admin = person("admin");
const admin2 = person("admin2");

const currentStatus = (page, who = driver) =>
  page.locator(`#operations-participant-${who.participant_id} .participant-current-status`);

// A marker that a full page load would wipe: the portal must patch, not reload.
const markNoReload = (page) => page.evaluate(() => (window.__e2eNoReload = true));
const stillNoReload = (page) => page.evaluate(() => window.__e2eNoReload === true);

async function signedInDriver(baseURL, who = driver) {
  const app = await FieldApp.signedIn(baseURL, who);
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
    // A session of its own: signing it out must not end the others' in this run.
    const app = await FieldApp.open(baseURL, driver);
    await app.signIn();
    expect((await app.capabilities()).status).toBe(200);

    expect((await app.signOut()).status()).toBe(204);
    expect((await app.capabilities()).status).toBe(401);
  });
});

test.describe("a second role profile", () => {
  test("14: a courier reuses the participant, device, assignment and status resources, unbranched", async ({ page, baseURL }) => {
    await signInToPortal(page, baseURL, dispatcher);
    await page.goto("/operations");
    await expect(page.locator(`#operations-participant-${courier.participant_id}`)).toBeVisible();
    await markNoReload(page);

    // The same sign-in, endpoints and app protocol; only the assignment differs.
    const app = await signedInDriver(baseURL, courier);
    const { claims } = await app.capabilities();
    expect(claims.role.key).toBe("COURIER");
    expect(claims.role_assignment_id).toBe(courier.role_assignment_id);
    // Features follow the profile's data, each only where a capability backs it.
    expect(claims.features).toEqual(expect.arrayContaining(["status", "inbox"]));
    expect(claims.features).not.toContain("map");
    expect(claims.status_options.map((o) => o.value)).toContain("AT_PICKUP");

    app.declare("AT_PICKUP");
    const [stored] = await app.sync();
    expect(stored.status).toBe(201);
    expect(stored.body.event.verification).toBe("DEVICE_AUTHENTICATED");

    // The dispatcher's operations projection carries it like any participant's.
    await expect(currentStatus(page, courier)).toContainText("AT_PICKUP", { timeout: 30_000 });
    expect(await stillNoReload(page)).toBe(true);
  });
});

// The administrator's grant form, as a person uses it.
async function grant(page, who, role) {
  await page.selectOption("#participant_id", who.participant_id);
  await page.selectOption("#role_definition_id", { label: role });
  await Promise.all([page.waitForURL("**/settings/users"), page.locator("#settings-grant button").click()]);
}

test.describe("administration", () => {
  test("15: an administrator manages assignments and sees nothing operational until another grants a role", async ({ page, browser, baseURL }) => {
    await signInToPortal(page, baseURL, admin);
    await expect(page).toHaveURL(/\/settings\/users$/);

    // Administration is not operational sight (Section 23.3).
    expect((await page.goto("/operations")).status()).toBe(404);
    expect((await page.goto(`/operations/participants/${driver.participant_id}`)).status()).toBe(404);

    // Manages: grants the courier a role, sees it, and revokes it.
    await page.goto("/settings/users");
    await grant(page, courier, "Dispatcher");
    const granted = page.locator(`#settings-member-${courier.participant_id} li`, { hasText: "Dispatcher" });
    await expect(granted).toBeVisible();
    await Promise.all([page.waitForURL("**/settings/users"), granted.getByRole("button", { name: "Revoke" }).click()]);
    await expect(granted).toHaveCount(0);

    // Never to themselves: not offered, and refused when sent anyway.
    await expect(page.locator(`#participant_id option[value="${admin.participant_id}"]`)).toHaveCount(0);
    await page.evaluate((id) => document.querySelector("#participant_id").add(new Option("me", id)), admin.participant_id);
    await page.selectOption("#participant_id", admin.participant_id);
    await page.selectOption("#role_definition_id", { label: "Dispatcher" });
    await page.locator("#settings-grant button").click();
    await expect(page.locator("#settings-message")).toContainText("No one can grant themselves a role");

    // Another administrator grants it, from their own browser.
    const elsewhere = await browser.newContext({ baseURL });
    const other = await elsewhere.newPage();
    await signInToPortal(other, baseURL, admin2);
    await grant(other, admin, "Dispatcher");
    await elsewhere.close();

    // Acting under it, the first now opens the operational pages.
    await page.goto("/select-role");
    const operational = await page.locator("#role_assignment_id option", { hasText: "Dispatcher" }).getAttribute("value");
    await chooseRole(page, operational);
    expect((await page.goto("/operations")).status()).toBe(200);
    expect((await page.goto(`/operations/participants/${driver.participant_id}`)).status()).toBe(200);
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

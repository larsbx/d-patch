// Section 33.4 scenarios 17–19: real biz-kit Ash actions and server HTML.
// The identical forms run with JavaScript enabled and disabled. Location and
// communication providers belong to later slices and are explicitly unavailable.
import { test, expect } from "@playwright/test";
import { signInToPortal } from "../support/portal.js";
import { fixture, person, stack } from "../support/stack.js";

const f = stack.partner_fixtures;
const pickup = `/partner/stops/${f.pickup_id}`;
const delivery = `/partner/stops/${f.delivery_id}`;
const missing = "00000000-0000-4000-8000-000000000099";
const later = (hours) => new Date(Date.now() + hours * 3_600_000).toISOString();

async function submit(form, page, button) {
  await Promise.all([page.waitForResponse((r) => r.request().method() === "POST"), form.getByRole("button", { name: button }).click()]);
  await page.waitForLoadState();
}
async function context(form) {
  const data = {};
  for (const name of ["_csrf_token", "idempotency_key", "expected_version", "expected_latest_id"]) {
    const field = form.locator(`[name="${name}"]`);
    if (await field.count()) data[name] = await field.inputValue();
  }
  return data;
}

for (const javaScriptEnabled of [true, false]) {
  test.describe(javaScriptEnabled ? "partner HTML" : "partner HTML without JavaScript", () => {
    test.use({ javaScriptEnabled });
    test.describe.configure({ mode: "serial" });

    test("17: shipper reports readiness and proposes a window, without confirming it or seeing delivery", async ({ page, baseURL }) => {
      await signInToPortal(page, baseURL, person("shipper"));
      await expect(page).toHaveURL(new RegExp(`${pickup}$`));
      const readiness = page.locator("#partner-readiness-form");
      await readiness.locator('[name="state"]').selectOption("READY");
      await readiness.locator('[name="note"]').fill("Pickup prepared by shipper");
      await submit(readiness, page, "Report readiness");
      await expect(page.locator("#partner-stop-readiness")).toContainText("READY");
      await expect(page.locator("#partner-stop-readiness .fact-badge")).toHaveAttribute("data-source", "PARTICIPANT");
      const confirmed = await page.locator("#partner-stop-window").innerText();
      const window = page.locator(".partner-window-form");
      await window.locator('[name="window_start"]').fill(later(3));
      await window.locator('[name="window_end"]').fill(later(4));
      await submit(window, page, "Propose window");
      await expect(page.locator(".partner-proposal").first()).toContainText("PROPOSED");
      expect(await page.locator("#partner-stop-window").innerText()).toBe(confirmed);
      await expect(page.getByRole("button", { name: /approve|confirm|execute/i })).toHaveCount(0);
      expect((await page.request.post(`${pickup}/proposals/confirm`)).status()).toBe(404);
      const denied = await page.goto(delivery);
      const deniedText = await denied.text();
      const absent = await page.goto(`/partner/stops/${missing}`);
      expect(denied.status()).toBe(404);
      expect(absent.status()).toBe(404);
      expect(await absent.text()).toBe(deniedText);
      expect(deniedText).not.toContain("Delivery bay");
    });

    test("18: receiver adds an encrypted proof reference and proposes instructions, without pickup or route data", async ({ page, baseURL }) => {
      await signInToPortal(page, baseURL, person("receiver"));
      await page.goto(delivery);
      const document = page.locator("#partner-document-form");
      await document.locator('[name="label"]').fill("Receiver signed POD");
      await document.locator('[name="reference"]').fill("POD-E2E-42");
      await submit(document, page, "Add reference");
      await expect(page.locator("#partner-documents")).toContainText("POD-E2E-42");
      await expect(page.locator("#partner-documents")).toContainText("not been uploaded, scanned or verified");
      const instructions = page.locator(".partner-instruction-form");
      await instructions.locator('[name="instructions"]').fill("Proposed receiver gate instructions");
      await submit(instructions, page, "Propose instructions");
      await expect(page.locator(".partner-proposals")).toContainText("Proposed receiver gate instructions");
      await expect(page.locator(".partner-proposals")).toContainText("PROPOSED");
      const html = await page.content();
      for (const privateValue of ["Pickup dock 4", "917500", "CARRIER-PRIVATE-NOTE", "participant-timeline", "route-trace"]) expect(html).not.toContain(privateValue);
      expect((await page.goto(pickup)).status()).toBe(404);
    });

    test("19: broker sees only the contracted load and cannot report readiness or add proof", async ({ page, baseURL }) => {
      const broker = person("broker");
      const client = await signInToPortal(page, baseURL, broker);
      await page.goto("/loads");
      await expect(page.locator("#partner-loads a")).toHaveCount(1);
      await page.locator("#partner-loads a").click();
      await expect(page.locator("#partner-load-page")).toContainText("E2E-CONTRACT");
      await expect(page.locator("#partner-load-location")).toContainText("No authorized location summary");
      await expect(page.locator("#partner-load-communications")).toContainText("Broker's own thread");
      await expect(page.locator(".partner-thread")).toHaveCount(1);
      await expect(page.locator("#partner-readiness-form, #partner-document-form")).toHaveCount(0);
      const form = page.locator(".partner-instruction-form").first();
      const data = await context(form);
      const denied = await client.http.post(`${baseURL}/v1/stops/${f.pickup_id}/readiness-events`, {
        headers: { authorization: `Bearer ${client.token}`, "x-role-assignment-id": broker.role_assignment_id,
          "idempotency-key": `broker-refused-${javaScriptEnabled}` },
        data: { expected_version: Number(data.expected_version), state: "READY" },
      });
      expect(denied.status()).toBe(404);
      await form.locator('[name="instructions"]').fill("Broker proposed contact instructions");
      await submit(form, page, "Propose instructions");
      await expect(page.locator(`#partner-proposals-${f.pickup_id}`)).toContainText("Broker proposed contact instructions");
      const body = await page.content();
      for (const secret of ["917500", "CARRIER-PRIVATE-NOTE", "POD-E2E-42", "Proposed receiver gate instructions", "route-trace", "OTHER-PARTY-THREAD-SECRET"]) expect(body).not.toContain(secret);
      const unrelated = await page.goto(`/loads/${f.unrelated_load_id}`);
      const absent = await page.goto(`/loads/${missing}`);
      expect(unrelated.status()).toBe(404);
      expect(absent.status()).toBe(404);
      expect(await unrelated.text()).toBe(await absent.text());
    });

    test("an offline form with an old proposal basis is refused, then fresh navigation works", async ({ page, baseURL, context: browserContext }) => {
      await signInToPortal(page, baseURL, person("shipper"));
      await page.goto(pickup);
      const form = page.locator(".partner-instruction-form");
      await form.locator('[name="instructions"]').fill("Offline proposed instructions");
      await browserContext.setOffline(true);
      fixture("change-stop", stack.tenant_id, f.pickup_id);
      await browserContext.setOffline(false);
      const response = page.waitForResponse((r) => r.request().method() === "POST");
      await form.getByRole("button", { name: "Propose instructions" }).click();
      expect((await response).status()).toBe(409);
      await expect(page.locator("#partner-message")).toContainText("Reload and review");
      await page.goto(pickup);
      await expect(page.locator("#partner-stop-card")).toContainText("Confirmed detail revised");
      await page.locator(".partner-instruction-form [name='instructions']").fill("Fresh proposed instructions");
      await submit(page.locator(".partner-instruction-form"), page, "Propose instructions");
      await expect(page.locator(".partner-proposals")).toContainText("Fresh proposed instructions");
    });
  });
}

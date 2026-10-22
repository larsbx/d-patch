// A portal user signs in with a biz-kit session, as dispatch's /login takes
// one, then selects their role (Section 4.3).

import { FieldApp } from "./field-app.js";

export async function signInToPortal(page, baseURL, who) {
  const client = await FieldApp.signedIn(baseURL, who);

  await page.goto("/login");
  await page.fill("#token", client.token);
  await page.locator('form[action="/session"] [type="submit"], form[action="/session"] button').first().click();
  await page.waitForURL("**/select-role");
  await chooseRole(page, who.role_assignment_id);
  return client;
}

/** Section 4.3's switcher: acts under `assignmentId`, landing on its profile's page. */
export async function chooseRole(page, assignmentId) {
  if (!page.url().endsWith("/select-role")) await page.goto("/select-role");
  await page.selectOption("#role_assignment_id", assignmentId);
  await Promise.all([
    page.waitForResponse((r) => r.url().endsWith("/select-role") && r.request().method() === "POST"),
    page.locator('form[action="/select-role"] [type="submit"], form[action="/select-role"] button').first().click(),
  ]);
  await page.waitForLoadState();
}

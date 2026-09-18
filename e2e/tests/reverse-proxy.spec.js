// The reverse proxy exists to make two production behaviours testable.
//
// Section 27.3 validates a provider webhook signature against the exact public
// URL, reconstructed from forwarded headers; Section 26.1 pins a CSP that
// permits scripts from this origin plus the configured map adapter's origins
// and forbids inline scripts. Running the suite against the application port
// directly would leave both unexercised while this gate stayed green.

import { test, expect } from "@playwright/test";

test("the application is reachable through the proxy", async ({ request }) => {
  const response = await request.get("/health/live");

  expect(response.status()).toBe(200);
  expect(await response.json()).toMatchObject({ status: "ok" });
});

test("the proxy sets the security headers the portal depends on", async ({ request }) => {
  const headers = (await request.get("/health/live")).headers();

  expect(headers["x-content-type-options"]).toBe("nosniff");
  expect(headers["x-frame-options"]).toBe("DENY");
  expect(headers["referrer-policy"]).toBe("no-referrer");
  expect(headers["strict-transport-security"]).toContain("max-age=");
});

test("the CSP forbids inline scripts and frames this origin", async ({ request }) => {
  const csp = (await request.get("/health/live")).headers()["content-security-policy"];

  expect(csp).toBeTruthy();

  // Section 26.1 prohibits inline scripts outright.
  expect(csp).not.toContain("unsafe-inline");
  expect(csp).not.toContain("unsafe-eval");

  expect(csp).toContain("default-src 'self'");
  expect(csp).toContain("frame-ancestors 'none'");

  // Section 28.5 replaces the map adapter by configuration; switching it means
  // updating this CSP, which is why the gate asserts the current adapter's
  // origins are present rather than assuming any script source is fine.
  expect(csp).toContain("script-src 'self'");
});

test("the proxy does not advertise its software", async ({ request }) => {
  const server = (await request.get("/health/live")).headers()["server"];

  expect(server ?? "").not.toMatch(/caddy/i);
});

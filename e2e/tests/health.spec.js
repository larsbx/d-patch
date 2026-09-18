// Slice 0 exit criterion: one command starts the stack and every service is
// ready. These assert that from outside the process, which is the only place
// the claim means anything.

import { test, expect } from "@playwright/test";

test("liveness reports the process is running", async ({ request }) => {
  const response = await request.get("/health/live");

  expect(response.status()).toBe(200);
  expect(await response.json()).toMatchObject({ status: "ok" });
});

test("readiness reports database and migration state", async ({ request }) => {
  const response = await request.get("/health/ready");
  const body = await response.json();

  // Section 22 forbids auto-migration on boot, so a pending migration is a
  // legitimate unready state rather than a failure of this test.
  expect([200, 503]).toContain(response.status());
  expect(body.checks).toHaveProperty("database");
  expect(body.checks).toHaveProperty("migrations");
});

test("health responses are never cached", async ({ request }) => {
  for (const path of ["/health/live", "/health/ready"]) {
    const response = await request.get(path);
    expect(response.headers()["cache-control"]).toBe("no-store");
  }
});

test("the dependency report requires authentication", async ({ request }) => {
  // Section 32 makes this an authenticated diagnostic endpoint; Section 31
  // forbids reporting a secret's value, so it must not be readable anonymously.
  const response = await request.get("/health/dependencies");

  expect(response.status()).toBe(401);
  expect(response.headers()["content-type"]).toContain("application/problem+json");

  // RFC 9457 (Section 19.2).
  const problem = await response.json();
  for (const field of ["type", "title", "status", "detail", "instance", "code", "correlation_id"]) {
    expect(problem).toHaveProperty(field);
  }
});

test("the dependency report never returns a secret value", async ({ request }) => {
  const token = process.env.DIAGNOSTICS_TOKEN;
  test.skip(!token, "DIAGNOSTICS_TOKEN is not set for this run");

  const response = await request.get("/health/dependencies", {
    headers: { authorization: `Bearer ${token}` },
  });

  expect(response.status()).toBe(200);
  const body = await response.json();

  // Section 31: presence only. Every reported secret is a boolean.
  for (const present of Object.values(body.adapters.required_secrets)) {
    expect(typeof present).toBe("boolean");
  }
});

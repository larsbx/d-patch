// Playwright configuration for the Section 33.4 end-to-end tests.
//
// The suite runs against the Compose stack rather than a mocked backend: every
// scenario in Section 33.4 is about what the server authorizes and renders, so
// a mock would assert nothing worth asserting.

import { defineConfig, devices } from "@playwright/test";

const baseURL = process.env.E2E_BASE_URL ?? "http://localhost:4000";

export default defineConfig({
  testDir: "./tests",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["html", { open: "never" }], ["list"]] : "list",
  use: {
    baseURL,
    trace: "on-first-retry",
    // Section 13 forbids logging precise coordinates and message content, so
    // artefacts are kept only for a failing run.
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
    // Section 26.7: every page must be useful before Datastar loads, and forms
    // must complete through ordinary server navigation if it never does.
    {
      name: "no-javascript",
      use: { ...devices["Desktop Chrome"], javaScriptEnabled: false },
      testMatch: /progressive\.spec\.js/,
    },
  ],
});

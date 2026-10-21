// Playwright configuration for the Section 33.4 scenarios run against biz-kit
// (d-patch ADR-0010): its one-command stack, `scripts/e2e-stack.sh`, serves
// dispatch on :4000 and SpruceGoose's /auth on :4001, and writes the stack file
// the tests read (E2E_STACK). The scenarios share one seeded tenant and run in
// order.

import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./biz-kit",
  fullyParallel: false,
  workers: 1,
  timeout: 90_000,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI ? [["html", { open: "never", outputFolder: "playwright-report-biz-kit" }], ["list"]] : "list",
  use: {
    baseURL: process.env.E2E_DISPATCH_URL ?? "http://127.0.0.1:4000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
    // A preinstalled browser, where the pinned build is not downloaded.
    launchOptions: process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {},
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});

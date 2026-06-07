import { defineConfig, devices } from "@playwright/test";

/**
 * Headless-by-default config. We're in WSL, so launching a head is both
 * unsupported (no display) and unnecessary — the smoke tests assert behaviour,
 * not visuals.
 *
 * `webServer` auto-starts the Vite dev server for the test run and re-uses an
 * already-running one if you happen to have `npm run dev` open in another tab.
 */
export default defineConfig({
  testDir: "./tests",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? "github" : "list",

  use: {
    baseURL: "http://localhost:5173",
    headless: true,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },

  projects: [
    {
      name: "desktop-chrome",
      use: { ...devices["Desktop Chrome"] },
    },
    {
      // Mobile-first project — same suite, narrow viewport with touch enabled,
      // matching the primary target (Android Chrome).
      name: "pixel-7",
      use: { ...devices["Pixel 7"] },
    },
  ],

  webServer: {
    command: "npm run dev",
    url: "http://localhost:5173",
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
    stdout: "ignore",
    stderr: "pipe",
  },
});

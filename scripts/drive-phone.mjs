// Connects to Chrome on the phone over the adb-forwarded DevTools port and
// drives the game tab: fills the name dialog if it's still open, takes a few
// screenshots, then plays a couple of swipes so we can confirm the autonomous
// movement actually advances the dot.
//
// Run from the project root: node scripts/drive-phone.mjs
import { chromium } from "@playwright/test";
import { mkdirSync } from "node:fs";

const OUT_DIR = new URL("../test-results/", import.meta.url);
mkdirSync(OUT_DIR, { recursive: true });

const browser = await chromium.connectOverCDP("http://localhost:9222");
const pages = browser.contexts().flatMap((c) => c.pages());
const target = pages.find((p) => p.url().startsWith("http://localhost:5173"));
if (!target) {
  console.error(
    "aMazeGame tab not found. Open tabs:",
    pages.map((p) => p.url()),
  );
  process.exit(1);
}

const logs = [];
target.on("pageerror", (e) => logs.push(`pageerror: ${e.message}`));
target.on("console", (m) => {
  if (m.type() === "error" || m.type() === "warning") {
    logs.push(`console.${m.type()}: ${m.text()}`);
  }
});

async function shot(name) {
  const path = new URL(name, OUT_DIR).pathname;
  await target.screenshot({ path });
  console.log(`saved ${path}`);
}

console.log("title:", await target.title());
console.log("url:", target.url());
console.log("viewport:", target.viewportSize());

await shot("phone-1-initial.png");

// If the name dialog is still showing, fill it and submit.
const nameDialogVisible = await target.locator("#name-dialog").isVisible();
if (nameDialogVisible) {
  console.log("name dialog open — filling 'PhonePilot'");
  await target.locator("#name-input").fill("PhonePilot");
  await target.locator('#name-dialog button[type="submit"]').click();
  await target.locator("#name-dialog").waitFor({ state: "hidden" });
  await shot("phone-2-named.png");
} else {
  console.log("name dialog not visible — already named");
}

// Try a couple of swipes. The renderer is canvas-only, so we drive via the
// keyboard arrow path that input.ts also handles — same code path as a swipe.
await target.keyboard.press("ArrowRight");
await target.waitForTimeout(400);
await target.keyboard.press("ArrowDown");
await target.waitForTimeout(400);
await target.keyboard.press("ArrowLeft");
await target.waitForTimeout(400);
await shot("phone-3-after-input.png");

console.log("logs:", logs);
await browser.close();

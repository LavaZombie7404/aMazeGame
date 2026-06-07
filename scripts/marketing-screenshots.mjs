// Captures the web app from the live GH Pages deploy, on a phone-sized
// viewport, with a name filled and a few swipes to seed the trail. Saved
// next to the Android screenshot for the README.
import { chromium, devices } from "@playwright/test";
import { mkdirSync } from "node:fs";

const OUT = new URL("../docs/images/", import.meta.url);
mkdirSync(OUT, { recursive: true });

const URL_LIVE = "https://lavazombie7404.github.io/aMazeGame/";

const browser = await chromium.launch();
try {
  const context = await browser.newContext({
    ...devices["Pixel 7"],
    deviceScaleFactor: 2,
  });
  const page = await context.newPage();
  await page.goto(URL_LIVE, { waitUntil: "networkidle" });

  // First-run dialog
  await page.waitForSelector("#name-dialog[open]", { timeout: 10_000 });
  await page.locator("#name-input").fill("LavaZombie7404");
  await page.locator('#name-dialog button[type="submit"]').click();
  await page.locator("#name-dialog").waitFor({ state: "hidden" });

  // Let the renderer settle (maze paint, first frames).
  await page.waitForTimeout(800);

  // Seed a trail so the screenshot isn't just empty space.
  for (const k of ["ArrowDown", "ArrowRight", "ArrowDown", "ArrowLeft"]) {
    await page.keyboard.press(k);
    await page.waitForTimeout(600);
  }
  await page.waitForTimeout(500);

  const out = new URL("web-mobile.png", OUT).pathname;
  await page.screenshot({ path: out, fullPage: false });
  console.log("saved", out);
} finally {
  await browser.close();
}

// Reconnect to the phone, reload the game, capture EVERYTHING (console, page
// errors, network failures, requests), then poke at runtime state.
import { chromium } from "@playwright/test";
import { mkdirSync, writeFileSync } from "node:fs";

const OUT = new URL("../test-results/", import.meta.url);
mkdirSync(OUT, { recursive: true });

const browser = await chromium.connectOverCDP("http://localhost:9222");
const pages = browser.contexts().flatMap((c) => c.pages());
const page = pages.find((p) => p.url().startsWith("http://localhost:5173"));
if (!page) {
  console.error("aMazeGame tab not found");
  process.exit(1);
}

const log = [];
const sink = (line) => {
  console.log(line);
  log.push(line);
};

page.on("console", (m) =>
  sink(`[console.${m.type()}] ${m.text()}`),
);
page.on("pageerror", (e) =>
  sink(`[pageerror] ${e.name}: ${e.message}\n${e.stack ?? ""}`),
);
page.on("requestfailed", (r) =>
  sink(`[requestfailed] ${r.url()} -> ${r.failure()?.errorText}`),
);
page.on("response", (r) => {
  if (r.status() >= 400) sink(`[http ${r.status()}] ${r.url()}`);
});
page.on("crash", () => sink("[page.crash]"));
page.on("close", () => sink("[page.close]"));

// Wipe IndexedDB and reload so we start clean (name dialog reappears).
sink("--- wiping IndexedDB ---");
await page
  .evaluate(async () => {
    const dbs = await indexedDB.databases();
    for (const d of dbs) {
      if (d.name) {
        await new Promise((res, rej) => {
          const r = indexedDB.deleteDatabase(d.name);
          r.onsuccess = () => res();
          r.onerror = () => rej(r.error);
          r.onblocked = () => res();
        });
      }
    }
    return "ok";
  })
  .catch((e) => sink(`evaluate(wipe) threw: ${e.message}`));

sink("--- reloading ---");
await page.reload({ waitUntil: "domcontentloaded" });

// Give boot some time to either work or crash.
await page.waitForTimeout(2000);

// Take a screenshot of whatever state we're in now.
await page.screenshot({ path: new URL("phone-debug-1-boot.png", OUT).pathname });

// Try to submit the name dialog if it's open.
const dialogOpen = await page
  .locator("#name-dialog")
  .isVisible()
  .catch(() => false);
sink(`name dialog visible? ${dialogOpen}`);
if (dialogOpen) {
  await page.locator("#name-input").fill("DebugBot");
  await page.locator('#name-dialog button[type="submit"]').click();
  await page.waitForTimeout(1500);
}
await page.screenshot({
  path: new URL("phone-debug-2-after-name.png", OUT).pathname,
});

// Reach into the runtime: is the canvas sized? Was render called? What does
// the canvas backing store look like? Are there window-level errors recorded?
const runtime = await page
  .evaluate(() => {
    const c = document.getElementById("maze");
    const rect = c?.getBoundingClientRect();
    const ctx = c?.getContext("2d");
    let centerPixel = null;
    let topLeftPixel = null;
    if (ctx && c.width && c.height) {
      const cw = Math.floor(c.width / 2);
      const ch = Math.floor(c.height / 2);
      const p = ctx.getImageData(cw, ch, 1, 1).data;
      centerPixel = [p[0], p[1], p[2], p[3]];
      const p2 = ctx.getImageData(0, 0, 1, 1).data;
      topLeftPixel = [p2[0], p2[1], p2[2], p2[3]];
    }
    return {
      hudPlayer: document.getElementById("player-name")?.textContent ?? null,
      hudScore: document.getElementById("score")?.textContent ?? null,
      canvas: {
        present: !!c,
        cssRect: rect && { w: rect.width, h: rect.height },
        backing: c && { w: c.width, h: c.height },
        centerPixel,
        topLeftPixel,
      },
      userAgent: navigator.userAgent,
      devicePixelRatio: window.devicePixelRatio,
      hardwareConcurrency: navigator.hardwareConcurrency,
      memoryDeviceMB: navigator.deviceMemory ?? null,
    };
  })
  .catch((e) => ({ error: e.message }));
sink("--- runtime state ---");
sink(JSON.stringify(runtime, null, 2));

// Save the captured log so we can dig later.
writeFileSync(new URL("phone-debug-log.txt", OUT).pathname, log.join("\n"));

await browser.close();

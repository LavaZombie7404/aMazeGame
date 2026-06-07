import { expect, test } from "@playwright/test";

test.describe("aMazeGame smoke", () => {
  test("loads and prompts for a player name", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveTitle("aMazeGame");

    // First-run flow: the name dialog should be open.
    const dialog = page.locator("#name-dialog");
    await expect(dialog).toBeVisible();
    await expect(page.locator("#name-input")).toBeVisible();
  });

  test("submitting a name starts the game and renders the canvas", async ({
    page,
  }) => {
    await page.goto("/");

    await page.locator("#name-input").fill("Tester");
    await page.locator('#name-dialog button[type="submit"]').click();

    // Name dialog closes.
    await expect(page.locator("#name-dialog")).toBeHidden();

    // Canvas has measurable dimensions in CSS pixels.
    const canvas = page.locator("#maze");
    await expect(canvas).toBeVisible();
    const box = await canvas.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.width).toBeGreaterThan(100);
    expect(box!.height).toBeGreaterThan(100);

    // HUD reflects the player name.
    await expect(page.locator("#player-name")).toHaveText("Tester");
    // Score starts at zero.
    await expect(page.locator("#score")).toHaveText("0");

    // Canvas backing store has pixels written to it (sanity-check that the
    // renderer actually ran — a blank canvas would have all-zero pixel data).
    const drewSomething = await canvas.evaluate((el) => {
      const c = el as HTMLCanvasElement;
      if (c.width === 0 || c.height === 0) return false;
      const ctx = c.getContext("2d")!;
      const sample = ctx.getImageData(
        Math.floor(c.width / 2),
        Math.floor(c.height / 2),
        1,
        1,
      ).data;
      // Any non-transparent pixel means the renderer painted something.
      return sample[3] > 0;
    });
    expect(drewSomething).toBe(true);
  });

  test("settings dialog opens and exposes shape selectors", async ({
    page,
  }) => {
    await page.goto("/");
    await page.locator("#name-input").fill("Tester");
    await page.locator('#name-dialog button[type="submit"]').click();

    await page.locator("#settings-btn").click();

    const settings = page.locator("#settings-dialog");
    await expect(settings).toBeVisible();
    // Three shape selectors must be present and populated with at least the
    // skin-default option plus the eight built-in shapes.
    for (const id of [
      "#character-shape-select",
      "#start-shape-select",
      "#goal-shape-select",
    ]) {
      const sel = page.locator(id);
      await expect(sel).toBeVisible();
      const count = await sel.locator("option").count();
      expect(count).toBeGreaterThanOrEqual(9);
    }
  });
});

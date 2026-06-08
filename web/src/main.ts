import { cellIndex, DIR_VEC, generateMaze, hasWall, hashMaze, type Maze } from "./maze";
import { playGoalChime, playWhoosh } from "./sfx";
import {
  fitMetrics,
  render,
  type ShapeOverrides,
  type ViewMetrics,
} from "./renderer";
import { attachSwipe } from "./input";
import {
  createMovementState,
  DIR_FROM_NAME,
  queueDirection,
  step,
  type MovementState,
} from "./movement";
import {
  getPlayer,
  hasSeenMaze,
  incrementStreak,
  markGenerated,
  recordCompletion,
  resetStreak,
  setAutoMode,
  setColorOverride,
  setLegacyMovement,
  setPlayerName,
  setShapeOverride,
  setSkinId,
  setSpeedMultiplier,
  wasGeneratedThisSession,
  type DifficultyBucket,
  type PlayerRecord,
  type ShapeSlot,
} from "./storage";
import type { ShapeRef } from "./shapes";
import { mulberry32, randomSeed } from "./rng";
import { DEFAULT_SKIN_ID, getSkin, listSkins, type Skin } from "./skins";
import { listShapes } from "./shapes";
import { loadCore } from "./core/wasm";

// Validation tap: confirm the shared Rust WASM core is loadable. The TS
// gameplay path still runs from `./maze` and `./movement` — the WASM core is
// the eventual replacement (and is what the Android app consumes today).
void loadCore()
  .then((x) => console.info("amaze-core WASM loaded, ABI v" + x.core_abi_version()))
  .catch((e) => console.warn("amaze-core WASM not loaded:", e));

const canvas = document.getElementById("maze") as HTMLCanvasElement;
const ctx = canvas.getContext("2d")!;
const scoreEl = document.getElementById("score")!;
const streakEl = document.getElementById("streak")!;
const nameEl = document.getElementById("player-name")!;
const settingsBtn = document.getElementById("settings-btn") as HTMLButtonElement;
const resetBtn = document.getElementById("reset-btn") as HTMLButtonElement;
const dailyBtn = document.getElementById("daily-btn") as HTMLButtonElement;
const nameDialog = document.getElementById("name-dialog") as HTMLDialogElement;
const nameInput = document.getElementById("name-input") as HTMLInputElement;
const settingsDialog = document.getElementById(
  "settings-dialog",
) as HTMLDialogElement;
const skinSelect = document.getElementById("skin-select") as HTMLSelectElement;
const characterShapeSelect = document.getElementById(
  "character-shape-select",
) as HTMLSelectElement;
const startShapeSelect = document.getElementById(
  "start-shape-select",
) as HTMLSelectElement;
const goalShapeSelect = document.getElementById(
  "goal-shape-select",
) as HTMLSelectElement;
const legacyMovementToggle = document.getElementById(
  "legacy-movement-toggle",
) as HTMLInputElement;
const speedSelect = document.getElementById("speed-select") as HTMLSelectElement;
const characterColorInput = document.getElementById(
  "character-color-input",
) as HTMLInputElement;
const startColorInput = document.getElementById(
  "start-color-input",
) as HTMLInputElement;
const goalColorInput = document.getElementById(
  "goal-color-input",
) as HTMLInputElement;
const characterColorReset = document.getElementById(
  "character-color-reset",
) as HTMLButtonElement;
const startColorReset = document.getElementById(
  "start-color-reset",
) as HTMLButtonElement;
const goalColorReset = document.getElementById(
  "goal-color-reset",
) as HTMLButtonElement;
const autoModeToggle = document.getElementById(
  "auto-mode-toggle",
) as HTMLInputElement;

const SPEED_OPTIONS = [0.5, 0.75, 1, 1.5, 2, 3] as const;

let maze: Maze;
let movement: MovementState;
let metrics: ViewMetrics;
let skin: Skin;
let overrides: ShapeOverrides = { character: null, start: null, goal: null };
let legacyMovement = false;
let speedMultiplier = 1;
let autoMode = false;
/**
 * Precomputed shortest-path solution for the current maze.
 * `pathDir[cellIdx]` = direction to take from that cell to advance along
 * the BFS shortest path toward the goal. `-1` for cells not on the path.
 */
let pathDir: Int8Array = new Int8Array(0);
/**
 * "Invisible" extra walls used in auto mode: all edges not on the solution
 * path are sealed. Renderer ignores this; movement-collision uses it.
 */
let pathExtraWalls: Uint8Array = new Uint8Array(0);

/** PRD §6.0 — alternate simple ↔ complex buckets across rounds. */
function bucketFor(mazesCompleted: number): DifficultyBucket {
  return mazesCompleted % 2 === 0 ? "simple" : "complex";
}

// Maze size bounds are now driven by what fits on screen: at one
// notebook-square per cell, the short axis fits 17 cells (PRD §6.0). Bigger
// would force shrinking the cells below the grid, which we explicitly don't
// want.
const MIN_SIZE = 12;
const MAX_SIZE = 17;

function sizeForBucket(bucket: DifficultyBucket, rand: () => number): number {
  if (bucket === "simple") {
    return MIN_SIZE + Math.floor(rand() * 3); // 12..14 inclusive
  }
  return 15 + Math.floor(rand() * (MAX_SIZE - 15 + 1)); // 15..17 inclusive
}

async function generateUniqueMaze(bucket: DifficultyBucket): Promise<Maze> {
  for (let attempt = 0; attempt < 50; attempt++) {
    const sizeRand = mulberry32(randomSeed());
    const size = sizeForBucket(bucket, sizeRand);
    const seed = randomSeed();
    const candidate = generateMaze(size, seed);
    const h = await hashMaze(candidate);
    if (wasGeneratedThisSession(h)) continue;
    if (await hasSeenMaze(h)) continue;
    markGenerated(h);
    return candidate;
  }
  throw new Error("Unable to generate a unique maze after 50 attempts.");
}

async function nextRound(player: PlayerRecord) {
  const bucket = bucketFor(player.mazesCompleted);
  maze = await generateUniqueMaze(bucket);
  movement = createMovementState(maze);
  resetAutoCache();
  resize();
}

/** Today's daily maze size — fixed so everyone solves the same N×N maze. */
const DAILY_SIZE = 15;

/**
 * Today's UTC date as an integer seed (YYYYMMDD). Same on every device, so
 * everyone gets the same maze. Re-rolls automatically at UTC midnight.
 */
function dailySeed(): number {
  const d = new Date();
  return d.getUTCFullYear() * 10000 + (d.getUTCMonth() + 1) * 100 + d.getUTCDate();
}

async function loadDailyMaze() {
  maze = generateMaze(DAILY_SIZE, dailySeed());
  movement = createMovementState(maze);
  resetAutoCache();
  resize();
}

function resize() {
  if (!maze) return;
  metrics = fitMetrics(canvas, maze.size);
}

async function ensurePlayerName(): Promise<PlayerRecord> {
  let p = await getPlayer();
  if (p?.name) return p;
  await new Promise<void>((resolve) => {
    nameDialog.showModal();
    nameDialog.addEventListener(
      "close",
      async () => {
        const name = (nameInput.value || "Player").trim().slice(0, 24);
        await setPlayerName(name);
        resolve();
      },
      { once: true },
    );
  });
  p = await getPlayer();
  if (!p) throw new Error("Player record not created");
  return p;
}

/** Push skin colors into CSS variables so the HUD follows the active skin. */
function applySkinToDom(s: Skin) {
  const root = document.documentElement;
  root.style.setProperty("--paper", s.palette.paper);
  root.style.setProperty("--ink", s.palette.ink);
  root.style.setProperty("--ink-soft", s.palette.accent);
  root.style.setProperty("--accent", s.palette.accent);
  root.style.setProperty("--dot", s.palette.character);
  root.style.setProperty("--hud-bg", s.hudBackground);
  root.style.setProperty("--skin-font", s.font);
}

function buildOverrideRef(
  base: ShapeRef,
  shapeName: string | null,
  color: string | null,
): ShapeRef | null {
  if (!shapeName && !color) return null;
  const style = color
    ? { ...(base.style ?? {}), fill: color, stroke: color, innerFill: color }
    : base.style;
  return {
    name: shapeName ?? base.name,
    style,
    sizeFactor: base.sizeFactor,
  };
}

function overridesFromPlayer(p: PlayerRecord): ShapeOverrides {
  return {
    character: buildOverrideRef(skin.character, p.characterOverride, p.characterColor),
    start: buildOverrideRef(skin.start, p.startOverride, p.startColor),
    goal: buildOverrideRef(skin.goal, p.goalOverride, p.goalColor),
  };
}

/**
 * BFS from start to goal, then walk back. Fills:
 *  - `pathDir[cellIdx]` — direction from that cell to the next on the
 *    shortest path (or -1 if the cell is off the path).
 *  - `extraWalls[cellIdx]` — wall mask that *closes every edge not on the
 *    path*. Starts as 0b1111 (all walls) and clears one bit per path edge.
 *    Used by auto mode to seal off side branches without changing what the
 *    renderer draws.
 */
function computePathSolution(
  m: Maze,
): { pathDir: Int8Array; extraWalls: Uint8Array } {
  const n = m.size;
  const pathDir = new Int8Array(n * n).fill(-1);
  const extraWalls = new Uint8Array(n * n).fill(0b1111);
  const visited = new Uint8Array(n * n);
  const parentDir = new Int8Array(n * n).fill(-1);
  const startIdx = cellIndex(n, m.start.x, m.start.y);
  const goalIdx = cellIndex(n, m.goal.x, m.goal.y);
  if (startIdx === goalIdx) {
    extraWalls[startIdx] = 0;
    return { pathDir, extraWalls };
  }
  const queue: number[] = [startIdx];
  let head = 0;
  visited[startIdx] = 1;
  let found = false;
  while (head < queue.length) {
    const cur = queue[head++]!;
    if (cur === goalIdx) {
      found = true;
      break;
    }
    const cx = cur % n;
    const cy = (cur - cx) / n;
    for (let d = 0; d < 4; d++) {
      if (hasWall(m, cx, cy, d)) continue;
      const [vx, vy] = DIR_VEC[d]!;
      const nx = cx + vx;
      const ny = cy + vy;
      if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue;
      const nIdx = cellIndex(n, nx, ny);
      if (visited[nIdx]) continue;
      visited[nIdx] = 1;
      parentDir[nIdx] = d;
      queue.push(nIdx);
    }
  }
  if (!found) return { pathDir, extraWalls };
  let idx = goalIdx;
  while (idx !== startIdx) {
    const dir = parentDir[idx]!;
    if (dir < 0) break;
    const [vx, vy] = DIR_VEC[dir]!;
    const px = (idx % n) - vx;
    const py = ((idx - (idx % n)) / n) - vy;
    const pIdx = cellIndex(n, px, py);
    pathDir[pIdx] = dir;
    extraWalls[pIdx] &= ~(1 << dir);
    const backDir = (dir + 2) % 4;
    extraWalls[idx] &= ~(1 << backDir);
    idx = pIdx;
  }
  return { pathDir, extraWalls };
}

function resetAutoCache() {
  pathDir = new Int8Array(0);
  pathExtraWalls = new Uint8Array(0);
}

/** Push (or clear) the auto-mode extra walls into the live movement state. */
function applyAutoExtraWalls() {
  if (!movement) return;
  if (autoMode) {
    if (pathExtraWalls.length === 0) {
      const sol = computePathSolution(maze);
      pathDir = sol.pathDir;
      pathExtraWalls = sol.extraWalls;
    }
    movement.extraWalls = pathExtraWalls;
  } else {
    movement.extraWalls = new Uint8Array(0);
  }
}

/** Skin's default color for a slot (used when no override is stored). */
function slotDefaultColor(slot: ShapeSlot): string {
  const ref =
    slot === "character" ? skin.character : slot === "start" ? skin.start : skin.goal;
  return ref.style?.fill ?? ref.style?.stroke ?? ref.style?.innerFill ?? "#000000";
}

async function currentStoredColor(slot: ShapeSlot): Promise<string | null> {
  const p = await getPlayer();
  if (!p) return null;
  return slot === "character"
    ? p.characterColor
    : slot === "start"
      ? p.startColor
      : p.goalColor;
}

function refreshHud(p: PlayerRecord) {
  nameEl.textContent = p.name;
  scoreEl.textContent = String(p.mazesCompleted);
  streakEl.textContent = p.bestStreak > 0
    ? `${p.currentStreak} (best ${p.bestStreak})`
    : String(p.currentStreak);
}

function populateShapeSelect(select: HTMLSelectElement, current: string | null) {
  select.innerHTML = "";
  const defaultOpt = document.createElement("option");
  defaultOpt.value = "";
  defaultOpt.textContent = "(skin default)";
  select.appendChild(defaultOpt);
  for (const name of listShapes()) {
    const opt = document.createElement("option");
    opt.value = name;
    opt.textContent = name;
    if (current === name) opt.selected = true;
    select.appendChild(opt);
  }
  if (!current) defaultOpt.selected = true;
}

function populateSettings(p: PlayerRecord) {
  // Skin select
  skinSelect.innerHTML = "";
  for (const s of listSkins()) {
    const opt = document.createElement("option");
    opt.value = s.id;
    opt.textContent = s.name;
    if (s.id === p.skinId) opt.selected = true;
    skinSelect.appendChild(opt);
  }
  // Hide the skin row if there is only one skin available (PRD §4.3).
  const skinRow = skinSelect.closest("label");
  if (skinRow) {
    (skinRow as HTMLElement).style.display =
      listSkins().length > 1 ? "" : "none";
  }

  populateShapeSelect(characterShapeSelect, p.characterOverride);
  populateShapeSelect(startShapeSelect, p.startOverride);
  populateShapeSelect(goalShapeSelect, p.goalOverride);
  legacyMovementToggle.checked = p.legacyMovement;
  // Snap saved speed to the closest preset option so we don't leave it on a
  // foreign value that the dropdown can't display.
  const closest = SPEED_OPTIONS.reduce((best, v) =>
    Math.abs(v - p.speedMultiplier) < Math.abs(best - p.speedMultiplier) ? v : best,
  );
  speedSelect.value = String(closest);
  characterColorInput.value = p.characterColor ?? slotDefaultColor("character");
  startColorInput.value = p.startColor ?? slotDefaultColor("start");
  goalColorInput.value = p.goalColor ?? slotDefaultColor("goal");
  autoModeToggle.checked = p.autoMode;
}

async function onSettingsChanged() {
  const nextSkinId = skinSelect.value || DEFAULT_SKIN_ID;
  if (nextSkinId !== skin.id) {
    skin = getSkin(nextSkinId);
    applySkinToDom(skin);
    await setSkinId(nextSkinId);
  }
  const slotsToPersist: Array<[ShapeSlot, HTMLSelectElement]> = [
    ["character", characterShapeSelect],
    ["start", startShapeSelect],
    ["goal", goalShapeSelect],
  ];
  for (const [slot, el] of slotsToPersist) {
    await setShapeOverride(slot, el.value || null);
  }
  if (legacyMovementToggle.checked !== legacyMovement) {
    legacyMovement = legacyMovementToggle.checked;
    await setLegacyMovement(legacyMovement);
  }
  if (autoModeToggle.checked !== autoMode) {
    autoMode = autoModeToggle.checked;
    await setAutoMode(autoMode);
    resetAutoCache();
    applyAutoExtraWalls();
  }
  const nextSpeed = Number(speedSelect.value);
  if (Number.isFinite(nextSpeed) && nextSpeed !== speedMultiplier) {
    speedMultiplier = nextSpeed;
    await setSpeedMultiplier(speedMultiplier);
  }
  const colorSlots: Array<[ShapeSlot, HTMLInputElement, string | null]> = [
    ["character", characterColorInput, await currentStoredColor("character")],
    ["start", startColorInput, await currentStoredColor("start")],
    ["goal", goalColorInput, await currentStoredColor("goal")],
  ];
  for (const [slot, input, stored] of colorSlots) {
    const v = input.value;
    if (v && v !== stored) {
      await setColorOverride(slot, v);
    }
  }
  const p = await getPlayer();
  if (p) {
    overrides = overridesFromPlayer(p);
    refreshHud(p);
  }
}

let lastFrame = performance.now();
let completing = false;

function frame(now: number) {
  const dt = Math.min(0.05, (now - lastFrame) / 1000);
  lastFrame = now;
  if (maze && movement && metrics && !completing) {
    if (autoMode) {
      if (pathDir.length === 0) {
        const sol = computePathSolution(maze);
        pathDir = sol.pathDir;
        pathExtraWalls = sol.extraWalls;
        movement.extraWalls = pathExtraWalls;
      }
      const idx = cellIndex(maze.size, movement.cellX, movement.cellY);
      const d = pathDir[idx]!;
      if (d >= 0) queueDirection(maze, movement, d);
    }
    const res = step(maze, movement, dt * speedMultiplier, legacyMovement);
    render(
      ctx,
      {
        maze,
        player: { x: movement.renderX, y: movement.renderY },
        visited: movement.visited,
      },
      metrics,
      skin,
      overrides,
      now,
    );
    if (res.reachedGoal) {
      completing = true;
      void onCompletion();
    }
  }
  requestAnimationFrame(frame);
}

async function onCompletion() {
  // Auto mode plays the maze for you — don't credit a score for it.
  if (!autoMode) {
    const h = await hashMaze(maze);
    const currentBucket = bucketFor(
      (await getPlayer())?.mazesCompleted ?? 0,
    );
    await recordCompletion(h, maze.size, maze.seed, currentBucket);
    await incrementStreak();
  }
  playGoalChime();
  const p = await getPlayer();
  if (p) refreshHud(p);
  // Brief pause, then next maze.
  await new Promise((r) => setTimeout(r, 600));
  if (p) await nextRound(p);
  resetAutoCache();
  completing = false;
}

async function boot() {
  const player = await ensurePlayerName();
  skin = getSkin(player.skinId);
  applySkinToDom(skin);
  overrides = overridesFromPlayer(player);
  legacyMovement = player.legacyMovement;
  speedMultiplier = player.speedMultiplier || 1;
  autoMode = player.autoMode;
  refreshHud(player);
  await nextRound(player);

  attachSwipe(document.body, {
    onSwipe: (d) => {
      if (!maze || !movement) return;
      queueDirection(maze, movement, DIR_FROM_NAME[d]);
    },
  });

  resetBtn.addEventListener("click", async () => {
    // Abandon the current maze and generate a new one. Skipping breaks the
    // current streak. The skipped maze doesn't count toward the score and
    // won't repeat this session.
    await resetStreak();
    const p = await getPlayer();
    if (p) {
      await nextRound(p);
      refreshHud(p);
    }
  });

  dailyBtn.addEventListener("click", async () => {
    await loadDailyMaze();
    playWhoosh();
  });

  settingsBtn.addEventListener("click", async () => {
    const p = await getPlayer();
    if (p) populateSettings(p);
    settingsDialog.showModal();
  });
  settingsDialog.addEventListener("close", () => {
    void onSettingsChanged();
  });

  // "Reset to skin default" buttons null out the stored color and snap the
  // picker back to whatever the active skin says for that slot.
  const resetPairs: Array<[HTMLButtonElement, HTMLInputElement, ShapeSlot]> = [
    [characterColorReset, characterColorInput, "character"],
    [startColorReset, startColorInput, "start"],
    [goalColorReset, goalColorInput, "goal"],
  ];
  for (const [btn, input, slot] of resetPairs) {
    btn.addEventListener("click", async (e) => {
      e.preventDefault();
      input.value = slotDefaultColor(slot);
      await setColorOverride(slot, null);
      const p = await getPlayer();
      if (p) overrides = overridesFromPlayer(p);
    });
  }

  window.addEventListener("resize", resize);
  window.addEventListener("orientationchange", resize);

  lastFrame = performance.now();
  requestAnimationFrame(frame);
}

void boot();

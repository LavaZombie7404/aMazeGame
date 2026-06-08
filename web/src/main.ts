import { generateMaze, hashMaze, type Maze } from "./maze";
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
  markGenerated,
  recordCompletion,
  setLegacyMovement,
  setPlayerName,
  setShapeOverride,
  setSkinId,
  wasGeneratedThisSession,
  type DifficultyBucket,
  type PlayerRecord,
  type ShapeSlot,
} from "./storage";
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
const nameEl = document.getElementById("player-name")!;
const settingsBtn = document.getElementById("settings-btn") as HTMLButtonElement;
const resetBtn = document.getElementById("reset-btn") as HTMLButtonElement;
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

let maze: Maze;
let movement: MovementState;
let metrics: ViewMetrics;
let skin: Skin;
let overrides: ShapeOverrides = { character: null, start: null, goal: null };
let legacyMovement = false;

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

function overridesFromPlayer(p: PlayerRecord): ShapeOverrides {
  return {
    character: p.characterOverride
      ? { ...skin.character, name: p.characterOverride }
      : null,
    start: p.startOverride ? { ...skin.start, name: p.startOverride } : null,
    goal: p.goalOverride ? { ...skin.goal, name: p.goalOverride } : null,
  };
}

function refreshHud(p: PlayerRecord) {
  nameEl.textContent = p.name;
  scoreEl.textContent = String(p.mazesCompleted);
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
    const res = step(maze, movement, dt, legacyMovement);
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
  const h = await hashMaze(maze);
  const currentBucket = bucketFor(
    (await getPlayer())?.mazesCompleted ?? 0,
  );
  await recordCompletion(h, maze.size, maze.seed, currentBucket);
  const p = await getPlayer();
  if (p) refreshHud(p);
  // Brief pause, then next maze.
  await new Promise((r) => setTimeout(r, 600));
  if (p) await nextRound(p);
  completing = false;
}

async function boot() {
  const player = await ensurePlayerName();
  skin = getSkin(player.skinId);
  applySkinToDom(skin);
  overrides = overridesFromPlayer(player);
  legacyMovement = player.legacyMovement;
  refreshHud(player);
  await nextRound(player);

  attachSwipe(document.body, {
    onSwipe: (d) => {
      if (!maze || !movement) return;
      queueDirection(maze, movement, DIR_FROM_NAME[d]);
    },
  });

  resetBtn.addEventListener("click", async () => {
    // Abandon the current maze and generate a new one. The skipped maze
    // doesn't count toward the score and won't repeat this session.
    const p = await getPlayer();
    if (p) await nextRound(p);
  });

  settingsBtn.addEventListener("click", async () => {
    const p = await getPlayer();
    if (p) populateSettings(p);
    settingsDialog.showModal();
  });
  settingsDialog.addEventListener("close", () => {
    void onSettingsChanged();
  });

  window.addEventListener("resize", resize);
  window.addEventListener("orientationchange", resize);

  lastFrame = performance.now();
  requestAnimationFrame(frame);
}

void boot();

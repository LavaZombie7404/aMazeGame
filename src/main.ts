import { generateMaze, hashMaze, type Maze } from "./maze";
import { fitMetrics, render, type ViewMetrics } from "./renderer";
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
  setPlayerName,
  wasGeneratedThisSession,
} from "./storage";
import { pickInt, mulberry32, randomSeed } from "./rng";

const MIN_SIZE = 6;
const MAX_SIZE = 12;

const canvas = document.getElementById("maze") as HTMLCanvasElement;
const ctx = canvas.getContext("2d")!;
const scoreEl = document.getElementById("score")!;
const nameEl = document.getElementById("player-name")!;
const dialog = document.getElementById("name-dialog") as HTMLDialogElement;
const nameInput = document.getElementById("name-input") as HTMLInputElement;

let maze: Maze;
let movement: MovementState;
let metrics: ViewMetrics;

/**
 * Generate a maze that has never been seen on this device. Picks a fresh seed
 * each time and rejects collisions. With ~10^x state space, collisions are
 * astronomically rare, but we still enforce the invariant.
 */
async function generateUniqueMaze(): Promise<Maze> {
  for (let attempt = 0; attempt < 50; attempt++) {
    const sizeRand = mulberry32(randomSeed());
    const size = pickInt(sizeRand, MIN_SIZE, MAX_SIZE);
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

async function nextRound() {
  maze = await generateUniqueMaze();
  movement = createMovementState(maze);
  resize();
}

function resize() {
  if (!maze) return;
  metrics = fitMetrics(canvas, maze.size);
}

async function ensurePlayerName(): Promise<string> {
  const existing = await getPlayer();
  if (existing?.name) return existing.name;
  return new Promise((resolve) => {
    dialog.showModal();
    dialog.addEventListener(
      "close",
      async () => {
        const name = (nameInput.value || "Player").trim().slice(0, 24);
        await setPlayerName(name);
        resolve(name);
      },
      { once: true },
    );
  });
}

async function updateHud() {
  const p = await getPlayer();
  if (p) {
    nameEl.textContent = p.name;
    scoreEl.textContent = String(p.mazesCompleted);
  }
}

let lastFrame = performance.now();
let completing = false;

function frame(now: number) {
  const dt = Math.min(0.05, (now - lastFrame) / 1000);
  lastFrame = now;
  if (maze && movement && metrics && !completing) {
    const res = step(maze, movement, dt);
    render(ctx, { maze, player: { x: movement.renderX, y: movement.renderY } }, metrics);
    if (res.reachedGoal) {
      completing = true;
      void onCompletion();
    }
  }
  requestAnimationFrame(frame);
}

async function onCompletion() {
  const h = await hashMaze(maze);
  const total = await recordCompletion(h, maze.size, maze.seed);
  scoreEl.textContent = String(total);
  // Brief pause, then next maze.
  await new Promise((r) => setTimeout(r, 600));
  await nextRound();
  completing = false;
}

async function boot() {
  const name = await ensurePlayerName();
  nameEl.textContent = name;
  await nextRound();
  await updateHud();

  attachSwipe(document.body, {
    onSwipe: (d) => {
      if (!maze || !movement) return;
      queueDirection(maze, movement, DIR_FROM_NAME[d]);
    },
  });

  window.addEventListener("resize", resize);
  window.addEventListener("orientationchange", resize);

  lastFrame = performance.now();
  requestAnimationFrame(frame);
}

void boot();

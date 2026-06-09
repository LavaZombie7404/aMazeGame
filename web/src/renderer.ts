import { cellIndex, hasWall, isBridge, type Maze, N, E, S, W } from "./maze";
import { mulberry32 } from "./rng";
import { drawShapeRef, type ShapeRef } from "./shapes";
import type { Skin, TrailStyle } from "./skins";

export interface ViewMetrics {
  cell: number;
  offsetX: number;
  offsetY: number;
  width: number;
  height: number;
  dpr: number;
}

export interface ShapeOverrides {
  character: ShapeRef | null;
  start: ShapeRef | null;
  goal: ShapeRef | null;
}

export interface RenderState {
  maze: Maze;
  /** Player position in maze coordinates (fractional during transitions). */
  player: { x: number; y: number };
  /** Cell-index set of cells the player has visited this maze. */
  visited: ReadonlySet<number>;
}

/**
 * Maximum number of cells along the smallest screen axis. The cell size is
 * derived from this — it's the math-notebook square size, shared by the maze
 * and the visible grid (PRD §6.0). This is the visual budget; mazes smaller
 * than MAX_CELLS_ON_SCREEN render with the same square size and just get
 * letter-boxed by the renderer.
 */
export const MAX_CELLS_ON_SCREEN = 17;

/**
 * The camera over the maze: how much we're zoomed in (`scale`, multiplying the
 * base notebook-square size) and where the board's top-left sits on the canvas
 * (`offsetX/Y`, in CSS px). At `scale = 1` a cell is exactly one notebook
 * square (`baseCellSize`), preserving the original look for mazes that fit. Big
 * mazes (e.g. the 50×50 daily) start zoomed out so the whole board is visible,
 * and the player pans/zooms from there.
 */
export interface Camera {
  scale: number;
  offsetX: number;
  offsetY: number;
}

/** Zoom-in ceiling. Lower bound is "whole maze fits" — derived per maze. */
const MAX_SCALE = 4;

/**
 * The notebook-square size: screen_min / 17, independent of maze N. This is the
 * cell size at scale 1; the grid background is drawn at this pitch too.
 */
export function baseCellSize(canvas: HTMLCanvasElement): number {
  const rect = canvas.getBoundingClientRect();
  return Math.max(
    8,
    Math.floor((Math.min(rect.width, rect.height) - 16) / MAX_CELLS_ON_SCREEN),
  );
}

/**
 * Smallest scale we allow: the one at which the whole board just fits the
 * canvas. For mazes ≤ 17 cells this is clamped to 1 so they keep the original
 * letter-boxed notebook look instead of zooming to fill the screen.
 */
function minScaleFor(size: number): number {
  return Math.min(1, MAX_CELLS_ON_SCREEN / size);
}

/**
 * Default camera for a freshly loaded maze. Always starts at scale 1 (one
 * notebook square per cell), so a maze looks the same whether it's small or
 * huge. A maze that fits ends up centered (clamp does that); a big one (the
 * 50×50 daily) is centered on its start cell, showing the region the player
 * begins in — they pan/zoom out from there rather than facing a tiny shrunk
 * board. Drawing only the on-screen cells (see `visibleRange`) keeps this fast
 * regardless of total maze size.
 */
export function defaultCamera(
  canvas: HTMLCanvasElement,
  size: number,
  focusX: number,
  focusY: number,
): Camera {
  const rect = canvas.getBoundingClientRect();
  const cell = baseCellSize(canvas); // scale 1
  const cam: Camera = {
    scale: 1,
    offsetX: rect.width / 2 - (focusX + 0.5) * cell,
    offsetY: rect.height / 2 - (focusY + 0.5) * cell,
  };
  clampCamera(cam, canvas, size);
  return cam;
}

/**
 * The inclusive cell-index window currently visible on screen, padded by one
 * cell so walls shared with just-off-screen neighbours still render. Drawing
 * code loops over this instead of the whole maze, so a 50×50 costs the same as
 * a 17×17 at the same zoom.
 */
export function visibleRange(
  m: ViewMetrics,
  size: number,
): { c0: number; c1: number; r0: number; r1: number } {
  return {
    c0: Math.max(0, Math.floor(-m.offsetX / m.cell) - 1),
    c1: Math.min(size - 1, Math.floor((m.width - m.offsetX) / m.cell) + 1),
    r0: Math.max(0, Math.floor(-m.offsetY / m.cell) - 1),
    r1: Math.min(size - 1, Math.floor((m.height - m.offsetY) / m.cell) + 1),
  };
}

/**
 * Keep the camera legal in place: clamp the zoom to [fit, MAX_SCALE], then keep
 * the board from being dragged off-screen. On an axis where the board is
 * smaller than the canvas it's centered (and can't be panned); otherwise the
 * offset is clamped so an edge can't be pulled inside the viewport.
 */
export function clampCamera(
  cam: Camera,
  canvas: HTMLCanvasElement,
  size: number,
): void {
  const rect = canvas.getBoundingClientRect();
  cam.scale = Math.min(MAX_SCALE, Math.max(minScaleFor(size), cam.scale));
  const board = baseCellSize(canvas) * cam.scale * size;
  cam.offsetX =
    board <= rect.width
      ? (rect.width - board) / 2
      : Math.min(0, Math.max(rect.width - board, cam.offsetX));
  cam.offsetY =
    board <= rect.height
      ? (rect.height - board) / 2
      : Math.min(0, Math.max(rect.height - board, cam.offsetY));
}

/**
 * Build the per-frame view metrics from the current camera. Does NOT resize the
 * canvas backing store — that's `syncBackingStore`, called only on real resize
 * (writing canvas.width every frame would needlessly clear + reallocate).
 */
export function metricsFor(
  canvas: HTMLCanvasElement,
  cam: Camera,
): ViewMetrics {
  const dpr = Math.min(window.devicePixelRatio || 1, 3);
  const rect = canvas.getBoundingClientRect();
  return {
    cell: baseCellSize(canvas) * cam.scale,
    offsetX: cam.offsetX,
    offsetY: cam.offsetY,
    width: rect.width,
    height: rect.height,
    dpr,
  };
}

/**
 * Zoom by `factor` while keeping the world point under (cx, cy) — the cursor or
 * pinch midpoint, in CSS px — pinned to that screen position. Clamps afterward.
 */
export function zoomCamera(
  cam: Camera,
  canvas: HTMLCanvasElement,
  size: number,
  factor: number,
  cx: number,
  cy: number,
): void {
  const base = baseCellSize(canvas);
  const oldCell = base * cam.scale;
  const worldX = (cx - cam.offsetX) / oldCell;
  const worldY = (cy - cam.offsetY) / oldCell;
  cam.scale = Math.min(MAX_SCALE, Math.max(minScaleFor(size), cam.scale * factor));
  const newCell = base * cam.scale;
  cam.offsetX = cx - worldX * newCell;
  cam.offsetY = cy - worldY * newCell;
  clampCamera(cam, canvas, size);
}

/** Pan by a screen-space delta (CSS px), then clamp back into bounds. */
export function panCamera(
  cam: Camera,
  canvas: HTMLCanvasElement,
  size: number,
  dx: number,
  dy: number,
): void {
  cam.offsetX += dx;
  cam.offsetY += dy;
  clampCamera(cam, canvas, size);
}

/** Match the canvas backing store to its CSS box. Call on resize only. */
export function syncBackingStore(canvas: HTMLCanvasElement): void {
  const dpr = Math.min(window.devicePixelRatio || 1, 3);
  const rect = canvas.getBoundingClientRect();
  canvas.width = Math.round(rect.width * dpr);
  canvas.height = Math.round(rect.height * dpr);
}

function cellCenter(m: ViewMetrics, x: number, y: number): [number, number] {
  return [m.offsetX + (x + 0.5) * m.cell, m.offsetY + (y + 0.5) * m.cell];
}

function drawWalls(
  ctx: CanvasRenderingContext2D,
  maze: Maze,
  m: ViewMetrics,
  skin: Skin,
  tMs: number,
) {
  // Seed the wobble *per cell* (from the maze seed + cell index) rather than
  // from one running stream. With viewport culling the set of drawn cells
  // changes as you pan, so a single shared `rand` would give each wall a
  // different wobble every frame (shimmer). Per-cell seeding makes each wall's
  // look depend only on its position — stable while panning, same maze same
  // look.
  const { c0, c1, r0, r1 } = visibleRange(m, maze.size);
  for (let y = r0; y <= r1; y++) {
    for (let x = c0; x <= c1; x++) {
      const px = m.offsetX + x * m.cell;
      const py = m.offsetY + y * m.cell;
      const rand = mulberry32(
        (maze.seed ^ (cellIndex(maze.size, x, y) * 0x9e3779b9)) >>> 0,
      );
      if (hasWall(maze, x, y, N)) {
        skin.drawWall(ctx, px, py, px + m.cell, py, rand, tMs);
      }
      if (hasWall(maze, x, y, W)) {
        skin.drawWall(ctx, px, py, px, py + m.cell, rand, tMs);
      }
      if (y === maze.size - 1 && hasWall(maze, x, y, S)) {
        skin.drawWall(ctx, px, py + m.cell, px + m.cell, py + m.cell, rand, tMs);
      }
      if (x === maze.size - 1 && hasWall(maze, x, y, E)) {
        skin.drawWall(ctx, px + m.cell, py, px + m.cell, py + m.cell, rand, tMs);
      }
    }
  }
}

/**
 * Bridges are 4-walls-open cells, so wall rendering can't show them. Mark
 * each bridge with a small "+" glyph at its centre in the skin's ink color
 * so players can spot where they'll be forced straight-through.
 */
function drawBridges(
  ctx: CanvasRenderingContext2D,
  maze: Maze,
  m: ViewMetrics,
  skin: Skin,
) {
  ctx.save();
  ctx.strokeStyle = skin.palette.ink;
  ctx.lineWidth = Math.max(1.4, m.cell * 0.06);
  ctx.lineCap = "round";
  ctx.globalAlpha = 0.45;
  const { c0, c1, r0, r1 } = visibleRange(m, maze.size);
  for (let y = r0; y <= r1; y++) {
    for (let x = c0; x <= c1; x++) {
      if (!isBridge(maze, x, y)) continue;
      const cx = m.offsetX + (x + 0.5) * m.cell;
      const cy = m.offsetY + (y + 0.5) * m.cell;
      const r = m.cell * 0.22;
      ctx.beginPath();
      ctx.moveTo(cx - r, cy);
      ctx.lineTo(cx + r, cy);
      ctx.moveTo(cx, cy - r);
      ctx.lineTo(cx, cy + r);
      ctx.stroke();
    }
  }
  ctx.restore();
}

function applyTrailDash(ctx: CanvasRenderingContext2D, t: TrailStyle) {
  switch (t.style) {
    case "dotted":
      ctx.setLineDash([0.1, t.width * 1.6]);
      ctx.lineCap = "round";
      break;
    case "dashed":
      ctx.setLineDash([t.width * 2.4, t.width * 1.6]);
      ctx.lineCap = "butt";
      break;
    case "solid":
    default:
      ctx.setLineDash([]);
      ctx.lineCap = "round";
      break;
  }
}

/**
 * For each pair of horizontally/vertically adjacent visited cells that are
 * connected (no wall between them), draw a stroke between their centres in
 * the skin's trail style. The maze is a tree, so a visited→visited adjacency
 * with no wall = the player did walk through that edge at some point.
 *
 * Backtracking through the same edge re-draws the same stroke, which by
 * design produces no extra layer (PRD §6.2).
 */
function drawTrail(
  ctx: CanvasRenderingContext2D,
  maze: Maze,
  visited: ReadonlySet<number>,
  m: ViewMetrics,
  trail: TrailStyle,
) {
  if (visited.size === 0) return;
  ctx.save();
  ctx.strokeStyle = trail.color;
  ctx.lineWidth = trail.width;
  ctx.globalAlpha = trail.alpha ?? 1;
  applyTrailDash(ctx, trail);

  const { c0, c1, r0, r1 } = visibleRange(m, maze.size);
  for (let y = r0; y <= r1; y++) {
    for (let x = c0; x <= c1; x++) {
      const here = cellIndex(maze.size, x, y);
      if (!visited.has(here)) continue;
      // Look East and South only so each edge is drawn at most once.
      if (
        x + 1 < maze.size &&
        !hasWall(maze, x, y, E) &&
        visited.has(cellIndex(maze.size, x + 1, y))
      ) {
        const [x1, y1] = cellCenter(m, x, y);
        const [x2, y2] = cellCenter(m, x + 1, y);
        ctx.beginPath();
        ctx.moveTo(x1, y1);
        ctx.lineTo(x2, y2);
        ctx.stroke();
      }
      if (
        y + 1 < maze.size &&
        !hasWall(maze, x, y, S) &&
        visited.has(cellIndex(maze.size, x, y + 1))
      ) {
        const [x1, y1] = cellCenter(m, x, y);
        const [x2, y2] = cellCenter(m, x, y + 1);
        ctx.beginPath();
        ctx.moveTo(x1, y1);
        ctx.lineTo(x2, y2);
        ctx.stroke();
      }
    }
  }

  ctx.restore();
}

function drawPlayerShadow(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  size: number,
) {
  ctx.fillStyle = "rgba(29,36,51,0.18)";
  ctx.beginPath();
  ctx.arc(x + 1.5, y + 2, size / 2, 0, Math.PI * 2);
  ctx.fill();
}

export function render(
  ctx: CanvasRenderingContext2D,
  state: RenderState,
  m: ViewMetrics,
  skin: Skin,
  overrides: ShapeOverrides,
  tMs: number,
) {
  ctx.save();
  ctx.scale(m.dpr, m.dpr);

  skin.drawBackground(ctx, m, tMs);
  drawWalls(ctx, state.maze, m, skin, tMs);
  drawBridges(ctx, state.maze, m, skin);
  drawTrail(ctx, state.maze, state.visited, m, skin.trail);

  // Start marker
  const [sx, sy] = cellCenter(m, state.maze.start.x, state.maze.start.y);
  drawShapeRef(ctx, overrides.start ?? skin.start, sx, sy, m.cell);

  // Goal marker
  const [gx, gy] = cellCenter(m, state.maze.goal.x, state.maze.goal.y);
  drawShapeRef(ctx, overrides.goal ?? skin.goal, gx, gy, m.cell);

  // Player
  const px = m.offsetX + (state.player.x + 0.5) * m.cell;
  const py = m.offsetY + (state.player.y + 0.5) * m.cell;
  const character = overrides.character ?? skin.character;
  const shadowSize = m.cell * (character.sizeFactor ?? 0.45);
  drawPlayerShadow(ctx, px, py, shadowSize);
  drawShapeRef(ctx, character, px, py, m.cell);

  ctx.restore();
}

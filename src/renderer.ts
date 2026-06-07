import { cellIndex, hasWall, type Maze, N, E, S, W } from "./maze";
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

export function fitMetrics(
  canvas: HTMLCanvasElement,
  size: number,
): ViewMetrics {
  const dpr = Math.min(window.devicePixelRatio || 1, 3);
  const rect = canvas.getBoundingClientRect();
  const usable = Math.min(rect.width, rect.height) - 16;
  const cell = Math.floor(usable / size);
  const board = cell * size;
  const offsetX = (rect.width - board) / 2;
  const offsetY = (rect.height - board) / 2;
  canvas.width = Math.round(rect.width * dpr);
  canvas.height = Math.round(rect.height * dpr);
  return { cell, offsetX, offsetY, width: rect.width, height: rect.height, dpr };
}

function cellCenter(m: ViewMetrics, x: number, y: number): [number, number] {
  return [m.offsetX + (x + 0.5) * m.cell, m.offsetY + (y + 0.5) * m.cell];
}

function drawWalls(
  ctx: CanvasRenderingContext2D,
  maze: Maze,
  m: ViewMetrics,
  skin: Skin,
) {
  // Seed wobble from the maze so the same maze always looks the same.
  const rand = mulberry32(maze.seed ^ 0x9e3779b9);
  for (let y = 0; y < maze.size; y++) {
    for (let x = 0; x < maze.size; x++) {
      const px = m.offsetX + x * m.cell;
      const py = m.offsetY + y * m.cell;
      if (hasWall(maze, x, y, N)) {
        skin.drawWall(ctx, px, py, px + m.cell, py, rand);
      }
      if (hasWall(maze, x, y, W)) {
        skin.drawWall(ctx, px, py, px, py + m.cell, rand);
      }
      if (y === maze.size - 1 && hasWall(maze, x, y, S)) {
        skin.drawWall(ctx, px, py + m.cell, px + m.cell, py + m.cell, rand);
      }
      if (x === maze.size - 1 && hasWall(maze, x, y, E)) {
        skin.drawWall(ctx, px + m.cell, py, px + m.cell, py + m.cell, rand);
      }
    }
  }
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

  for (let y = 0; y < maze.size; y++) {
    for (let x = 0; x < maze.size; x++) {
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
) {
  ctx.save();
  ctx.scale(m.dpr, m.dpr);

  skin.drawBackground(ctx, m);
  drawWalls(ctx, state.maze, m, skin);
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

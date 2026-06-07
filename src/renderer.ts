import { hasWall, type Maze, N, E, S, W } from "./maze";
import { mulberry32 } from "./rng";

export interface ViewMetrics {
  cell: number;
  offsetX: number;
  offsetY: number;
  width: number;
  height: number;
  dpr: number;
}

export interface RenderState {
  maze: Maze;
  // Player position in maze coordinates (cells, fractional during transitions).
  player: { x: number; y: number };
}

const INK = "#1d2433";
const INK_SOFT = "#2a3450";
const PAPER = "#f7f3e8";
const PAPER_LINE = "#b9c7d8";
const PAPER_MARGIN = "#e9a3a6";
const DOT = "#c83b3b";
const GOAL = "#2f6fb8";

export function fitMetrics(
  canvas: HTMLCanvasElement,
  size: number,
): ViewMetrics {
  const dpr = Math.min(window.devicePixelRatio || 1, 3);
  const rect = canvas.getBoundingClientRect();
  // Reserve a small margin for the pen overshoot.
  const usable = Math.min(rect.width, rect.height) - 16;
  const cell = Math.floor(usable / size);
  const board = cell * size;
  const offsetX = (rect.width - board) / 2;
  const offsetY = (rect.height - board) / 2;
  canvas.width = Math.round(rect.width * dpr);
  canvas.height = Math.round(rect.height * dpr);
  return { cell, offsetX, offsetY, width: rect.width, height: rect.height, dpr };
}

/** Notebook paper background: warm cream + ruled lines + red margin. */
function drawPaper(ctx: CanvasRenderingContext2D, m: ViewMetrics) {
  ctx.fillStyle = PAPER;
  ctx.fillRect(0, 0, m.width, m.height);

  // Ruled horizontal lines, every 28px-ish.
  ctx.strokeStyle = PAPER_LINE;
  ctx.lineWidth = 1;
  ctx.globalAlpha = 0.55;
  const spacing = 28;
  ctx.beginPath();
  for (let y = spacing; y < m.height; y += spacing) {
    ctx.moveTo(0, y);
    ctx.lineTo(m.width, y);
  }
  ctx.stroke();
  ctx.globalAlpha = 1;

  // Red margin on the left.
  ctx.strokeStyle = PAPER_MARGIN;
  ctx.lineWidth = 1.2;
  ctx.globalAlpha = 0.7;
  ctx.beginPath();
  const marginX = Math.min(48, m.width * 0.08);
  ctx.moveTo(marginX, 0);
  ctx.lineTo(marginX, m.height);
  ctx.stroke();
  ctx.globalAlpha = 1;
}

/**
 * Draw a single "pen" stroke from (x1,y1) to (x2,y2) with hand-drawn jitter:
 * tiny perpendicular wobble along the path, slight overshoot at endpoints,
 * variable line width and a hint of ink pooling. Deterministic per stroke.
 */
function penStroke(
  ctx: CanvasRenderingContext2D,
  x1: number,
  y1: number,
  x2: number,
  y2: number,
  rand: () => number,
) {
  const dx = x2 - x1;
  const dy = y2 - y1;
  const len = Math.hypot(dx, dy);
  if (len === 0) return;
  const nx = -dy / len;
  const ny = dx / len;
  const steps = Math.max(6, Math.floor(len / 6));

  // Slight overshoot at both ends — pen lingering.
  const overshoot = 1.5 + rand() * 2.5;
  const sx = x1 - (dx / len) * overshoot;
  const sy = y1 - (dy / len) * overshoot;
  const ex = x2 + (dx / len) * overshoot;
  const ey = y2 + (dy / len) * overshoot;

  ctx.strokeStyle = INK;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.lineWidth = 2.2 + rand() * 0.6;

  ctx.beginPath();
  ctx.moveTo(sx, sy);
  for (let i = 1; i <= steps; i++) {
    const t = i / steps;
    const px = sx + (ex - sx) * t;
    const py = sy + (ey - sy) * t;
    const wobble = (rand() - 0.5) * 1.6;
    ctx.lineTo(px + nx * wobble, py + ny * wobble);
  }
  ctx.stroke();

  // Occasional ink pool near endpoints.
  if (rand() < 0.35) {
    ctx.fillStyle = INK;
    ctx.beginPath();
    ctx.arc(sx, sy, 1.5 + rand(), 0, Math.PI * 2);
    ctx.fill();
  }
}

function drawWalls(
  ctx: CanvasRenderingContext2D,
  maze: Maze,
  m: ViewMetrics,
) {
  // Same seed → identical wall wobble between frames (no jitter shimmer).
  const rand = mulberry32(maze.seed ^ 0x9e3779b9);

  for (let y = 0; y < maze.size; y++) {
    for (let x = 0; x < maze.size; x++) {
      const px = m.offsetX + x * m.cell;
      const py = m.offsetY + y * m.cell;
      if (hasWall(maze, x, y, N)) {
        penStroke(ctx, px, py, px + m.cell, py, rand);
      }
      if (hasWall(maze, x, y, W)) {
        penStroke(ctx, px, py, px, py + m.cell, rand);
      }
      // South/East walls only drawn at the boundary to avoid double-stroke.
      if (y === maze.size - 1 && hasWall(maze, x, y, S)) {
        penStroke(ctx, px, py + m.cell, px + m.cell, py + m.cell, rand);
      }
      if (x === maze.size - 1 && hasWall(maze, x, y, E)) {
        penStroke(ctx, px + m.cell, py, px + m.cell, py + m.cell, rand);
      }
    }
  }
}

function drawGoal(
  ctx: CanvasRenderingContext2D,
  maze: Maze,
  m: ViewMetrics,
) {
  const cx = m.offsetX + (maze.goal.x + 0.5) * m.cell;
  const cy = m.offsetY + (maze.goal.y + 0.5) * m.cell;
  const r = m.cell * 0.28;
  // Hand-drawn star/circle target.
  ctx.strokeStyle = GOAL;
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.arc(cx, cy, r, 0, Math.PI * 2);
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(cx, cy, r * 0.55, 0, Math.PI * 2);
  ctx.fillStyle = GOAL;
  ctx.globalAlpha = 0.6;
  ctx.fill();
  ctx.globalAlpha = 1;
}

function drawPlayer(
  ctx: CanvasRenderingContext2D,
  state: RenderState,
  m: ViewMetrics,
) {
  const cx = m.offsetX + (state.player.x + 0.5) * m.cell;
  const cy = m.offsetY + (state.player.y + 0.5) * m.cell;
  const r = Math.max(4, m.cell * 0.22);
  // Soft shadow / ink smudge.
  ctx.fillStyle = "rgba(29,36,51,0.18)";
  ctx.beginPath();
  ctx.arc(cx + 1.5, cy + 2, r, 0, Math.PI * 2);
  ctx.fill();

  ctx.fillStyle = DOT;
  ctx.beginPath();
  ctx.arc(cx, cy, r, 0, Math.PI * 2);
  ctx.fill();

  ctx.strokeStyle = INK_SOFT;
  ctx.lineWidth = 1.3;
  ctx.stroke();
}

export function render(
  ctx: CanvasRenderingContext2D,
  state: RenderState,
  m: ViewMetrics,
) {
  ctx.save();
  ctx.scale(m.dpr, m.dpr);
  drawPaper(ctx, m);
  drawWalls(ctx, state.maze, m);
  drawGoal(ctx, state.maze, m);
  drawPlayer(ctx, state, m);
  ctx.restore();
}

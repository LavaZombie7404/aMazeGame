import type { Skin, SkinMetrics } from "./types";

const INK = "#1d2433";
const INK_SOFT = "#2a3450";
const PAPER = "#f7f3e8";
const PAPER_LINE = "#b9c7d8";
const PAPER_MARGIN = "#e9a3a6";
const DOT_RED = "#c83b3b";
const GOAL_BLUE = "#2f6fb8";
const TRAIL_BLUE = "#7896c2";

/**
 * A Romanian-style "caiet de matematică" page: cream paper, a faint blue
 * SQUARE grid (~5mm squares), and a red left-margin rule. The grid is the
 * defining feature of a math notebook — ruled (horizontal-only) paper would
 * be a writing notebook ("caiet cu linii"), which is the wrong genre for
 * this game.
 */
function drawPaper(ctx: CanvasRenderingContext2D, m: SkinMetrics) {
  ctx.fillStyle = PAPER;
  ctx.fillRect(0, 0, m.width, m.height);

  // Grid spacing == m.cell so each notebook square IS one maze cell. The
  // grid is anchored to the maze top-left so wall edges land exactly on grid
  // lines (the pen-stroke walls overdraw the lines underneath).
  ctx.strokeStyle = PAPER_LINE;
  ctx.lineWidth = 1;
  ctx.globalAlpha = 0.5;
  ctx.beginPath();
  const offX = ((m.offsetX % m.cell) + m.cell) % m.cell;
  const offY = ((m.offsetY % m.cell) + m.cell) % m.cell;
  for (let x = offX; x < m.width; x += m.cell) {
    ctx.moveTo(x + 0.5, 0);
    ctx.lineTo(x + 0.5, m.height);
  }
  for (let y = offY; y < m.height; y += m.cell) {
    ctx.moveTo(0, y + 0.5);
    ctx.lineTo(m.width, y + 0.5);
  }
  ctx.stroke();
  ctx.globalAlpha = 1;

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
 * Wobbly pen stroke with endpoint overshoot, perpendicular jitter, variable
 * line width and a hint of ink pooling. Determinism comes from the `rand`
 * passed in by the renderer.
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

  if (rand() < 0.35) {
    ctx.fillStyle = INK;
    ctx.beginPath();
    ctx.arc(sx, sy, 1.5 + rand(), 0, Math.PI * 2);
    ctx.fill();
  }
}

export const mathTextbookSkin: Skin = {
  id: "math-textbook",
  name: "Math textbook",
  palette: {
    ink: INK,
    paper: PAPER,
    accent: INK_SOFT,
    character: DOT_RED,
    start: INK_SOFT,
    goal: GOAL_BLUE,
    trail: TRAIL_BLUE,
  },
  font: '"Patrick Hand", "Comic Sans MS", "Bradley Hand", system-ui, sans-serif',
  hudBackground: "rgba(247, 243, 232, 0.85)",
  // tMs is accepted but unused — math-textbook is intentionally static.
  drawBackground(ctx, m, _tMs) {
    drawPaper(ctx, m);
  },
  drawWall(ctx, x1, y1, x2, y2, rand, _tMs) {
    penStroke(ctx, x1, y1, x2, y2, rand);
  },
  character: {
    name: "dot",
    style: { fill: DOT_RED, stroke: INK_SOFT, strokeWidth: 1.3 },
    sizeFactor: 0.44,
  },
  start: {
    name: "ring",
    style: { stroke: INK_SOFT, strokeWidth: 1.4 },
    sizeFactor: 0.36,
  },
  goal: {
    name: "ring",
    style: { stroke: GOAL_BLUE, strokeWidth: 2, innerFill: GOAL_BLUE },
    sizeFactor: 0.56,
  },
  trail: {
    color: TRAIL_BLUE,
    width: 4,
    style: "dotted",
    alpha: 0.55,
  },
};

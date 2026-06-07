import type { Skin, SkinMetrics } from "./types";

/**
 * Geometry Dash-inspired skin family. Three palettes — Classic Neon, Sub
 * Zero, and Meltdown — built off the same factory so the visual rules stay
 * consistent.
 *
 * Visual rules (all three skins):
 * - Dark vertical gradient background, with a faint glowing grid that
 *   slowly scrolls downward (the GD "camera" feel).
 * - Walls drawn as **sharp** straight strokes (no wobble — pen-stroke
 *   wobble belongs to the math-textbook skin). Each wall is rendered as a
 *   stack of three strokes: outer halo + mid glow + core line, which is
 *   how you fake a neon glow on plain Canvas2D.
 * - Beat pulse: alpha and effective glow thickness modulated by a 1.2s
 *   sine wave. Walls + start/goal markers pulse together.
 * - Trail: solid glowing line, fades along the way.
 *
 * Phase-2 of the PRD; matches user's framing in `CLAUDE.md`.
 */

interface GeometryDashPalette {
  bgTop: string;
  bgBottom: string;
  grid: string;        // dim line color for the scrolling grid
  gridGlow: string;    // brighter pulse highlight at the grid intersection
  wall: string;        // primary neon for the walls
  wallGlow: string;    // outer halo color (usually same as wall, lower alpha)
  player: string;      // character (cube)
  start: string;       // start portal ring
  goal: string;        // goal portal ring
  trail: string;
  hudBg: string;
}

/** 1.2s beat period — half a second up, half a second down. */
function beat(tMs: number): number {
  return 0.85 + 0.15 * Math.sin((tMs / 1200) * Math.PI * 2);
}

function drawNeonLine(
  ctx: CanvasRenderingContext2D,
  x1: number,
  y1: number,
  x2: number,
  y2: number,
  color: string,
  glow: string,
  baseWidth: number,
  pulse: number,
) {
  // Outer halo
  ctx.strokeStyle = glow;
  ctx.globalAlpha = 0.18 * pulse;
  ctx.lineWidth = baseWidth * 5;
  ctx.lineCap = "round";
  ctx.beginPath();
  ctx.moveTo(x1, y1);
  ctx.lineTo(x2, y2);
  ctx.stroke();
  // Mid glow
  ctx.strokeStyle = color;
  ctx.globalAlpha = 0.45 * pulse;
  ctx.lineWidth = baseWidth * 2.2;
  ctx.beginPath();
  ctx.moveTo(x1, y1);
  ctx.lineTo(x2, y2);
  ctx.stroke();
  // Core
  ctx.globalAlpha = 1;
  ctx.lineWidth = baseWidth;
  ctx.beginPath();
  ctx.moveTo(x1, y1);
  ctx.lineTo(x2, y2);
  ctx.stroke();
}

function drawGdBackground(
  ctx: CanvasRenderingContext2D,
  m: SkinMetrics,
  tMs: number,
  palette: GeometryDashPalette,
) {
  // Vertical gradient base.
  const grad = ctx.createLinearGradient(0, 0, 0, m.height);
  grad.addColorStop(0, palette.bgTop);
  grad.addColorStop(1, palette.bgBottom);
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, m.width, m.height);

  // Scrolling grid — scroll speed = one cell every 4 seconds.
  const scroll = (tMs / 4000) * m.cell;
  const offX = ((m.offsetX % m.cell) + m.cell) % m.cell;
  const offY = ((((m.offsetY + scroll) % m.cell) + m.cell) % m.cell);
  const pulse = beat(tMs);

  // Faint grid lines.
  ctx.strokeStyle = palette.grid;
  ctx.globalAlpha = 0.22 + 0.06 * pulse;
  ctx.lineWidth = 1;
  ctx.beginPath();
  for (let x = offX; x < m.width; x += m.cell) {
    ctx.moveTo(x + 0.5, 0);
    ctx.lineTo(x + 0.5, m.height);
  }
  for (let y = offY; y < m.height; y += m.cell) {
    ctx.moveTo(0, y + 0.5);
    ctx.lineTo(m.width, y + 0.5);
  }
  ctx.stroke();

  // Brighter intersection dots — the "stars" in the GD scrolling sky.
  ctx.fillStyle = palette.gridGlow;
  ctx.globalAlpha = 0.35 + 0.25 * pulse;
  for (let x = offX; x < m.width; x += m.cell) {
    for (let y = offY; y < m.height; y += m.cell) {
      ctx.beginPath();
      ctx.arc(x, y, 1.4, 0, Math.PI * 2);
      ctx.fill();
    }
  }
  ctx.globalAlpha = 1;
}

function makeGeometryDashSkin(
  id: string,
  name: string,
  palette: GeometryDashPalette,
): Skin {
  return {
    id,
    name,
    palette: {
      ink: palette.wall,
      paper: palette.bgTop,
      accent: palette.wall,
      character: palette.player,
      start: palette.start,
      goal: palette.goal,
      trail: palette.trail,
    },
    font:
      '"Russo One", "Eurostile Extended", "Bahnschrift", "Inter", system-ui, sans-serif',
    hudBackground: palette.hudBg,
    drawBackground(ctx, m, tMs) {
      drawGdBackground(ctx, m, tMs, palette);
    },
    drawWall(ctx, x1, y1, x2, y2, _rand, tMs) {
      drawNeonLine(ctx, x1, y1, x2, y2, palette.wall, palette.wallGlow, 2.6, beat(tMs));
    },
    character: {
      name: "square",
      style: {
        fill: palette.player,
        stroke: palette.player,
        strokeWidth: 1.4,
      },
      sizeFactor: 0.5,
    },
    start: {
      name: "ring",
      style: {
        stroke: palette.start,
        strokeWidth: 2.4,
        innerFill: palette.start,
      },
      sizeFactor: 0.46,
    },
    goal: {
      name: "ring",
      style: {
        stroke: palette.goal,
        strokeWidth: 2.6,
        innerFill: palette.goal,
      },
      sizeFactor: 0.56,
    },
    trail: {
      color: palette.trail,
      width: 4,
      style: "solid",
      alpha: 0.7,
    },
  };
}

export const geometryDashClassicSkin = makeGeometryDashSkin(
  "geometry-dash-classic",
  "GD · Classic Neon",
  {
    bgTop: "#0a0a23",
    bgBottom: "#1a0a3a",
    grid: "#3b3168",
    gridGlow: "#7c5cff",
    wall: "#00ddff",
    wallGlow: "#00ddff",
    player: "#ff2eb5",
    start: "#00ddff",
    goal: "#ffd23f",
    trail: "#00ddff",
    hudBg: "rgba(10, 10, 35, 0.85)",
  },
);

export const geometryDashSubZeroSkin = makeGeometryDashSkin(
  "geometry-dash-sub-zero",
  "GD · Sub Zero",
  {
    bgTop: "#03102b",
    bgBottom: "#0c2350",
    grid: "#23457a",
    gridGlow: "#9ee5ff",
    wall: "#9ee5ff",
    wallGlow: "#9ee5ff",
    player: "#3fa9ff",
    start: "#9ee5ff",
    goal: "#ffffff",
    trail: "#9ee5ff",
    hudBg: "rgba(3, 16, 43, 0.88)",
  },
);

export const geometryDashMeltdownSkin = makeGeometryDashSkin(
  "geometry-dash-meltdown",
  "GD · Meltdown",
  {
    bgTop: "#1a0204",
    bgBottom: "#2e0a07",
    grid: "#5b1a14",
    gridGlow: "#ff8a2c",
    wall: "#ff8a2c",
    wallGlow: "#ff8a2c",
    player: "#ffe156",
    start: "#ff8a2c",
    goal: "#ff2e2e",
    trail: "#ff8a2c",
    hudBg: "rgba(26, 2, 4, 0.9)",
  },
);

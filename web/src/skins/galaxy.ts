import type { Skin, SkinMetrics } from "./types";

/**
 * Galaxy skin — the Milky Way as seen from Earth, edge-on.
 *
 * The "background" is procedurally drawn each frame:
 *   1. A vertical gradient from near-black at the top to a deeper black-blue
 *      at the bottom.
 *   2. A diagonal nebula band (the galactic plane), drawn as a rotated
 *      elliptical radial gradient — warm core fading out through purple to
 *      transparent.
 *   3. A field of small twinkling stars seeded so the layout is stable
 *      across frames. Density is biased along the galactic plane so the
 *      Milky Way band is recognisable.
 *
 * Walls are pale blue thin lines (legibility on a dark background); the
 * character is a glowing yellow dot, and start/goal are neon rings to keep
 * the "deep space portal" feel.
 */

const BG_TOP = "#040716";
const BG_BOTTOM = "#01020A";
const NEBULA_CORE = "rgba(255, 233, 176, 0.22)";
const NEBULA_MID = "rgba(180, 130, 220, 0.10)";
const NEBULA_EDGE = "rgba(80, 60, 200, 0)";
const STAR_BRIGHT = "#fff5d0";
const WALL = "#9ED9FF";
const WALL_GLOW = "#9ED9FF";
const PLAYER = "#FFE066";
const START_PORTAL = "#5CC8FF";
const GOAL_STAR = "#FFD23F";
const TRAIL = "#5CC8FF";

/** Deterministic Mulberry32. */
function mulberry32(seed: number): () => number {
  let s = seed | 0;
  return () => {
    s = (s + 0x6d2b79f5) | 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Pre-sampled star list per metrics signature — recomputed only on resize. */
interface StarField {
  signature: string;
  stars: Array<{ x: number; y: number; r: number; a: number; phase: number }>;
  bright: Array<{ x: number; y: number; r: number }>;
}
let cachedField: StarField | null = null;

function rebuildStarField(m: SkinMetrics): StarField {
  const rng = mulberry32(0xC0DECAFE);
  const cx = m.width / 2;
  const cy = m.height / 2;
  const angle = -0.45; // ~26° upward, matches the diagonal nebula band
  const cos = Math.cos(angle);
  const sin = Math.sin(angle);
  const longAxis = Math.max(m.width, m.height) * 0.85;
  const shortAxis = m.height * 0.28;

  const starCount = Math.max(120, Math.floor((m.width * m.height) / 1400));
  const stars: StarField["stars"] = [];
  for (let i = 0; i < starCount; i++) {
    let x: number, y: number;
    if (rng() < 0.55) {
      const t = (rng() - 0.5) * 1.2;
      const off = (rng() - 0.5) * shortAxis * 1.8;
      x = cx + t * longAxis * cos - off * sin;
      y = cy + t * longAxis * sin + off * cos;
    } else {
      x = rng() * m.width;
      y = rng() * m.height;
    }
    const r = rng() * 0.9 + 0.25;
    const a = rng() * 0.65 + 0.35;
    const phase = rng() * Math.PI * 2;
    stars.push({ x, y, r, a, phase });
  }

  const bright: StarField["bright"] = [];
  for (let i = 0; i < 10; i++) {
    const t = (rng() - 0.5) * 0.85;
    const off = (rng() - 0.5) * shortAxis * 0.45;
    const x = cx + t * longAxis * cos - off * sin;
    const y = cy + t * longAxis * sin + off * cos;
    const r = 1.4 + rng() * 1.4;
    bright.push({ x, y, r });
  }

  return {
    signature: `${m.width}x${m.height}`,
    stars,
    bright,
  };
}

function getStarField(m: SkinMetrics): StarField {
  const sig = `${m.width}x${m.height}`;
  if (!cachedField || cachedField.signature !== sig) {
    cachedField = rebuildStarField(m);
  }
  return cachedField;
}

function drawGalaxyBackground(
  ctx: CanvasRenderingContext2D,
  m: SkinMetrics,
  tMs: number,
) {
  // Vertical deep-space gradient.
  const grad = ctx.createLinearGradient(0, 0, 0, m.height);
  grad.addColorStop(0, BG_TOP);
  grad.addColorStop(1, BG_BOTTOM);
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, m.width, m.height);

  // Milky-Way band — a rotated radial gradient that paints the dust + glow.
  const cx = m.width / 2;
  const cy = m.height / 2;
  const angle = -0.45;
  const longAxis = Math.max(m.width, m.height) * 0.85;
  const shortAxis = m.height * 0.28;
  ctx.save();
  ctx.translate(cx, cy);
  ctx.rotate(angle);
  ctx.scale(longAxis / shortAxis, 1);
  const nebula = ctx.createRadialGradient(0, 0, 0, 0, 0, shortAxis);
  nebula.addColorStop(0, NEBULA_CORE);
  nebula.addColorStop(0.4, NEBULA_MID);
  nebula.addColorStop(1, NEBULA_EDGE);
  ctx.fillStyle = nebula;
  ctx.beginPath();
  ctx.arc(0, 0, shortAxis, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();

  // Stars — twinkle with a slow sine.
  const field = getStarField(m);
  for (const s of field.stars) {
    if (s.x < 0 || s.x > m.width || s.y < 0 || s.y > m.height) continue;
    const tw = 0.85 + 0.15 * Math.sin((tMs / 800) * Math.PI * 2 + s.phase);
    ctx.fillStyle = `rgba(255,255,255,${(s.a * tw).toFixed(3)})`;
    ctx.beginPath();
    ctx.arc(s.x, s.y, s.r, 0, Math.PI * 2);
    ctx.fill();
  }
  // Bright core stars.
  ctx.fillStyle = STAR_BRIGHT;
  for (const b of field.bright) {
    if (b.x < 0 || b.x > m.width || b.y < 0 || b.y > m.height) continue;
    ctx.beginPath();
    ctx.arc(b.x, b.y, b.r, 0, Math.PI * 2);
    ctx.fill();
  }
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
) {
  ctx.strokeStyle = glow;
  ctx.globalAlpha = 0.15;
  ctx.lineWidth = baseWidth * 4;
  ctx.lineCap = "round";
  ctx.beginPath();
  ctx.moveTo(x1, y1);
  ctx.lineTo(x2, y2);
  ctx.stroke();
  ctx.strokeStyle = color;
  ctx.globalAlpha = 0.5;
  ctx.lineWidth = baseWidth * 1.8;
  ctx.beginPath();
  ctx.moveTo(x1, y1);
  ctx.lineTo(x2, y2);
  ctx.stroke();
  ctx.globalAlpha = 1;
  ctx.lineWidth = baseWidth;
  ctx.beginPath();
  ctx.moveTo(x1, y1);
  ctx.lineTo(x2, y2);
  ctx.stroke();
}

export const galaxySkin: Skin = {
  id: "galaxy",
  name: "Galaxy",
  palette: {
    ink: WALL,
    paper: BG_TOP,
    accent: PLAYER,
    character: PLAYER,
    start: START_PORTAL,
    goal: GOAL_STAR,
    trail: TRAIL,
  },
  font: '"Orbitron", "Eurostile", "Bahnschrift", "Inter", system-ui, sans-serif',
  hudBackground: "rgba(4, 7, 22, 0.88)",
  drawBackground(ctx, m, tMs) {
    drawGalaxyBackground(ctx, m, tMs);
  },
  drawWall(ctx, x1, y1, x2, y2, _rand, _tMs) {
    drawNeonLine(ctx, x1, y1, x2, y2, WALL, WALL_GLOW, 2.0);
  },
  character: {
    name: "dot",
    style: { fill: PLAYER, stroke: PLAYER, strokeWidth: 1.6 },
    sizeFactor: 0.46,
  },
  start: {
    name: "ring",
    style: { stroke: START_PORTAL, strokeWidth: 2.4, innerFill: START_PORTAL },
    sizeFactor: 0.44,
  },
  goal: {
    name: "star-5",
    style: { fill: GOAL_STAR, stroke: GOAL_STAR, strokeWidth: 1.4 },
    sizeFactor: 0.6,
  },
  trail: {
    color: TRAIL,
    width: 4,
    style: "solid",
    alpha: 0.55,
  },
};

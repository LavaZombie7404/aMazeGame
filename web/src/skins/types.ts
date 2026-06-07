import type { ShapeRef } from "../shapes";

export interface SkinPalette {
  ink: string;
  paper: string;
  accent: string;
  character: string;
  start: string;
  goal: string;
  trail: string;
}

export interface TrailStyle {
  color: string;
  width: number;
  style: "solid" | "dotted" | "dashed";
  /** 0..1 multiplier on opacity. */
  alpha?: number;
}

export interface SkinMetrics {
  cell: number;
  offsetX: number;
  offsetY: number;
  width: number;
  height: number;
  dpr: number;
}

export interface Skin {
  id: string;
  name: string;
  palette: SkinPalette;
  font: string;

  /** HUD background color (paper-ish for math-textbook, dark for GD). */
  hudBackground: string;

  /**
   * Paint the entire canvas background before any maze content. `tMs` is the
   * monotonic time since boot in milliseconds, available to skins that want
   * to animate (e.g. Geometry Dash's scrolling grid + pulse).
   */
  drawBackground(ctx: CanvasRenderingContext2D, m: SkinMetrics, tMs: number): void;

  /**
   * Draw a single wall segment from (x1,y1) to (x2,y2). The renderer calls
   * this once per wall in the maze. `rand` is a deterministic PRNG seeded
   * from the maze, so repeated frames produce identical wall jitter. `tMs`
   * is the same animation clock as `drawBackground` — used by skins for
   * beat-synced thickness / brightness.
   */
  drawWall(
    ctx: CanvasRenderingContext2D,
    x1: number,
    y1: number,
    x2: number,
    y2: number,
    rand: () => number,
    tMs: number,
  ): void;

  /** Default shape refs — players can override these in their preferences. */
  character: ShapeRef;
  start: ShapeRef;
  goal: ShapeRef;

  trail: TrailStyle;
}

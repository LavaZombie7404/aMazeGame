import type { Shape, ShapeRef } from "./types";
import {
  arrow,
  diamond,
  dot,
  flag,
  ring,
  square,
  star5,
  triangle,
} from "./primitives";

const REGISTRY: Record<string, Shape> = {
  [dot.name]: dot,
  [ring.name]: ring,
  [square.name]: square,
  [diamond.name]: diamond,
  [triangle.name]: triangle,
  [star5.name]: star5,
  [flag.name]: flag,
  [arrow.name]: arrow,
};

export function listShapes(): string[] {
  return Object.keys(REGISTRY).sort();
}

export function getShape(name: string): Shape {
  const s = REGISTRY[name];
  if (!s) throw new Error(`Unknown shape: ${name}`);
  return s;
}

/**
 * Draw a shape from the registry, applying the ref's style and (cell-size)
 * factor on top.
 */
export function drawShapeRef(
  ctx: CanvasRenderingContext2D,
  ref: ShapeRef,
  cx: number,
  cy: number,
  cellSize: number,
): void {
  const shape = getShape(ref.name);
  const size = cellSize * (ref.sizeFactor ?? 0.45);
  shape.draw(ctx, cx, cy, size, ref.style ?? {});
}

export type { Shape, ShapeRef, ShapeStyle } from "./types";

export interface ShapeStyle {
  fill?: string;
  stroke?: string;
  strokeWidth?: number;
  // Used by some shapes (e.g. ring) that have an inner fill on top of an outer stroke.
  innerFill?: string;
}

export interface Shape {
  /** Stable identifier — referenced by skins and by player overrides. */
  name: string;
  /**
   * Draw the shape centred on (cx, cy) fitting roughly within a `size`×`size`
   * bounding box. The shape's draw fn is responsible for setting fill/stroke.
   */
  draw(
    ctx: CanvasRenderingContext2D,
    cx: number,
    cy: number,
    size: number,
    style: ShapeStyle,
  ): void;
}

/**
 * A reference from a skin (or a player override) to a shape in the library,
 * with optional per-use style overrides.
 */
export interface ShapeRef {
  name: string;
  style?: ShapeStyle;
  /** Optional scale factor on the cell-based default size. */
  sizeFactor?: number;
}

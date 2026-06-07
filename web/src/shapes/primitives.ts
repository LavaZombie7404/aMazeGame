import type { Shape, ShapeStyle } from "./types";

function applyStroke(
  ctx: CanvasRenderingContext2D,
  style: ShapeStyle,
  defaultStrokeWidth = 1.3,
): boolean {
  if (!style.stroke) return false;
  ctx.strokeStyle = style.stroke;
  ctx.lineWidth = style.strokeWidth ?? defaultStrokeWidth;
  ctx.lineJoin = "round";
  ctx.lineCap = "round";
  return true;
}

function applyFill(
  ctx: CanvasRenderingContext2D,
  style: ShapeStyle,
): boolean {
  if (!style.fill) return false;
  ctx.fillStyle = style.fill;
  return true;
}

export const dot: Shape = {
  name: "dot",
  draw(ctx, cx, cy, size, style) {
    const r = size / 2;
    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, Math.PI * 2);
    if (applyFill(ctx, style)) ctx.fill();
    if (applyStroke(ctx, style)) ctx.stroke();
  },
};

export const ring: Shape = {
  name: "ring",
  draw(ctx, cx, cy, size, style) {
    const r = size / 2;
    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, Math.PI * 2);
    if (applyStroke(ctx, style, 2)) ctx.stroke();
    if (style.innerFill) {
      ctx.fillStyle = style.innerFill;
      ctx.globalAlpha = 0.6;
      ctx.beginPath();
      ctx.arc(cx, cy, r * 0.55, 0, Math.PI * 2);
      ctx.fill();
      ctx.globalAlpha = 1;
    }
  },
};

export const square: Shape = {
  name: "square",
  draw(ctx, cx, cy, size, style) {
    const h = size / 2;
    ctx.beginPath();
    ctx.rect(cx - h, cy - h, size, size);
    if (applyFill(ctx, style)) ctx.fill();
    if (applyStroke(ctx, style)) ctx.stroke();
  },
};

export const diamond: Shape = {
  name: "diamond",
  draw(ctx, cx, cy, size, style) {
    const h = size / 2;
    ctx.beginPath();
    ctx.moveTo(cx, cy - h);
    ctx.lineTo(cx + h, cy);
    ctx.lineTo(cx, cy + h);
    ctx.lineTo(cx - h, cy);
    ctx.closePath();
    if (applyFill(ctx, style)) ctx.fill();
    if (applyStroke(ctx, style)) ctx.stroke();
  },
};

export const triangle: Shape = {
  name: "triangle",
  draw(ctx, cx, cy, size, style) {
    const h = size / 2;
    ctx.beginPath();
    ctx.moveTo(cx, cy - h);
    ctx.lineTo(cx + h * 0.95, cy + h * 0.75);
    ctx.lineTo(cx - h * 0.95, cy + h * 0.75);
    ctx.closePath();
    if (applyFill(ctx, style)) ctx.fill();
    if (applyStroke(ctx, style)) ctx.stroke();
  },
};

export const star5: Shape = {
  name: "star-5",
  draw(ctx, cx, cy, size, style) {
    const outer = size / 2;
    const inner = outer * 0.45;
    ctx.beginPath();
    for (let i = 0; i < 10; i++) {
      const r = i % 2 === 0 ? outer : inner;
      const a = (i * Math.PI) / 5 - Math.PI / 2;
      const x = cx + Math.cos(a) * r;
      const y = cy + Math.sin(a) * r;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.closePath();
    if (applyFill(ctx, style)) ctx.fill();
    if (applyStroke(ctx, style)) ctx.stroke();
  },
};

export const flag: Shape = {
  name: "flag",
  draw(ctx, cx, cy, size, style) {
    const h = size / 2;
    // Pole
    if (applyStroke(ctx, style, 2)) {
      ctx.beginPath();
      ctx.moveTo(cx - h * 0.45, cy - h);
      ctx.lineTo(cx - h * 0.45, cy + h);
      ctx.stroke();
    }
    // Flag triangle
    ctx.beginPath();
    ctx.moveTo(cx - h * 0.45, cy - h);
    ctx.lineTo(cx + h * 0.7, cy - h * 0.45);
    ctx.lineTo(cx - h * 0.45, cy + h * 0.1);
    ctx.closePath();
    if (applyFill(ctx, style)) ctx.fill();
    if (applyStroke(ctx, style)) ctx.stroke();
  },
};

export const arrow: Shape = {
  name: "arrow",
  draw(ctx, cx, cy, size, style) {
    const h = size / 2;
    // Arrow pointing right.
    ctx.beginPath();
    ctx.moveTo(cx - h, cy - h * 0.45);
    ctx.lineTo(cx + h * 0.25, cy - h * 0.45);
    ctx.lineTo(cx + h * 0.25, cy - h);
    ctx.lineTo(cx + h, cy);
    ctx.lineTo(cx + h * 0.25, cy + h);
    ctx.lineTo(cx + h * 0.25, cy + h * 0.45);
    ctx.lineTo(cx - h, cy + h * 0.45);
    ctx.closePath();
    if (applyFill(ctx, style)) ctx.fill();
    if (applyStroke(ctx, style)) ctx.stroke();
  },
};

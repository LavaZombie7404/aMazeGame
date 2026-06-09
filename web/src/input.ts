export type Direction = "up" | "down" | "left" | "right";

export interface SwipeListenerOptions {
  /** Minimum distance in CSS pixels for a gesture to count as a swipe. */
  minDistance?: number;
  /** Called with the dominant swipe direction. */
  onSwipe: (dir: Direction) => void;
}

/**
 * Attach a touch + pointer swipe listener that fires on every directional swipe,
 * including continuous ones (finger never lifts) — each axis-crossing emits the
 * latest dominant direction. Requirement #7: "by sliding your finger up down
 * up and left the character moves next."
 */
export function attachSwipe(
  el: HTMLElement,
  opts: SwipeListenerOptions,
): () => void {
  const minDistance = opts.minDistance ?? 24;
  let startX = 0;
  let startY = 0;
  let lastEmittedDir: Direction | null = null;
  let active = false;

  const onStart = (x: number, y: number) => {
    active = true;
    startX = x;
    startY = y;
    lastEmittedDir = null;
  };

  const onMove = (x: number, y: number) => {
    if (!active) return;
    const dx = x - startX;
    const dy = y - startY;
    if (Math.hypot(dx, dy) < minDistance) return;
    const dir: Direction =
      Math.abs(dx) > Math.abs(dy)
        ? dx > 0
          ? "right"
          : "left"
        : dy > 0
          ? "down"
          : "up";
    if (dir !== lastEmittedDir) {
      lastEmittedDir = dir;
      opts.onSwipe(dir);
      // Reset origin so the next axis-crossing fires another swipe.
      startX = x;
      startY = y;
    }
  };

  const onEnd = () => {
    active = false;
    lastEmittedDir = null;
  };

  // One finger steers; a second finger means a camera gesture (pan/zoom), so we
  // bail out of steering and let attachCamera take over.
  const touchStart = (e: TouchEvent) => {
    if (e.touches.length !== 1) {
      active = false;
      return;
    }
    const t = e.touches[0]!;
    onStart(t.clientX, t.clientY);
  };
  const touchMove = (e: TouchEvent) => {
    if (e.touches.length !== 1) {
      active = false;
      return;
    }
    const t = e.touches[0]!;
    onMove(t.clientX, t.clientY);
    e.preventDefault();
  };
  const touchEnd = () => onEnd();

  const keyDown = (e: KeyboardEvent) => {
    const map: Record<string, Direction> = {
      ArrowUp: "up",
      ArrowDown: "down",
      ArrowLeft: "left",
      ArrowRight: "right",
      w: "up",
      a: "left",
      s: "down",
      d: "right",
    };
    const dir = map[e.key];
    if (dir) {
      opts.onSwipe(dir);
      e.preventDefault();
    }
  };

  el.addEventListener("touchstart", touchStart, { passive: true });
  el.addEventListener("touchmove", touchMove, { passive: false });
  el.addEventListener("touchend", touchEnd);
  el.addEventListener("touchcancel", touchEnd);
  window.addEventListener("keydown", keyDown);

  return () => {
    el.removeEventListener("touchstart", touchStart);
    el.removeEventListener("touchmove", touchMove);
    el.removeEventListener("touchend", touchEnd);
    el.removeEventListener("touchcancel", touchEnd);
    window.removeEventListener("keydown", keyDown);
  };
}

export interface CameraListenerOptions {
  /** Multiply the zoom by `factor`, keeping (cx, cy) in CSS px fixed on screen. */
  onZoom: (factor: number, cx: number, cy: number) => void;
  /** Pan the view by a screen-space delta in CSS px. */
  onPan: (dx: number, dy: number) => void;
}

/**
 * Camera controls that coexist with {@link attachSwipe}:
 *  - desktop: mouse wheel zooms toward the cursor; hold left button and drag to pan.
 *  - touch: two-finger pinch zooms toward the midpoint; two-finger drag pans.
 *
 * One-finger touch is left untouched so it keeps steering the dot.
 */
export function attachCamera(
  el: HTMLElement,
  opts: CameraListenerOptions,
): () => void {
  // --- desktop: wheel zoom + left-drag pan ---
  const onWheel = (e: WheelEvent) => {
    e.preventDefault();
    // Trackpads/mice report wildly different deltas; normalize to a gentle
    // per-notch factor based on direction only.
    const factor = e.deltaY < 0 ? 1.1 : 1 / 1.1;
    opts.onZoom(factor, e.clientX, e.clientY);
  };

  let dragging = false;
  let lastX = 0;
  let lastY = 0;
  const onMouseDown = (e: MouseEvent) => {
    if (e.button !== 0) return;
    dragging = true;
    lastX = e.clientX;
    lastY = e.clientY;
  };
  const onMouseMove = (e: MouseEvent) => {
    if (!dragging) return;
    opts.onPan(e.clientX - lastX, e.clientY - lastY);
    lastX = e.clientX;
    lastY = e.clientY;
  };
  const onMouseUp = () => {
    dragging = false;
  };

  // --- touch: two-finger pinch zoom + pan ---
  let pinchDist = 0;
  let pinchX = 0;
  let pinchY = 0;
  const touchMetrics = (e: TouchEvent) => {
    const a = e.touches[0]!;
    const b = e.touches[1]!;
    return {
      dist: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY),
      midX: (a.clientX + b.clientX) / 2,
      midY: (a.clientY + b.clientY) / 2,
    };
  };
  const onTouchStart = (e: TouchEvent) => {
    if (e.touches.length !== 2) return;
    const m = touchMetrics(e);
    pinchDist = m.dist;
    pinchX = m.midX;
    pinchY = m.midY;
  };
  const onTouchMove = (e: TouchEvent) => {
    if (e.touches.length !== 2) return;
    e.preventDefault();
    const m = touchMetrics(e);
    if (pinchDist > 0 && m.dist > 0) {
      opts.onZoom(m.dist / pinchDist, m.midX, m.midY);
    }
    opts.onPan(m.midX - pinchX, m.midY - pinchY);
    pinchDist = m.dist;
    pinchX = m.midX;
    pinchY = m.midY;
  };

  el.addEventListener("wheel", onWheel, { passive: false });
  el.addEventListener("mousedown", onMouseDown);
  window.addEventListener("mousemove", onMouseMove);
  window.addEventListener("mouseup", onMouseUp);
  el.addEventListener("touchstart", onTouchStart, { passive: true });
  el.addEventListener("touchmove", onTouchMove, { passive: false });

  return () => {
    el.removeEventListener("wheel", onWheel);
    el.removeEventListener("mousedown", onMouseDown);
    window.removeEventListener("mousemove", onMouseMove);
    window.removeEventListener("mouseup", onMouseUp);
    el.removeEventListener("touchstart", onTouchStart);
    el.removeEventListener("touchmove", onTouchMove);
  };
}

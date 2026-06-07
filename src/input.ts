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

  const touchStart = (e: TouchEvent) => {
    const t = e.touches[0];
    if (!t) return;
    onStart(t.clientX, t.clientY);
  };
  const touchMove = (e: TouchEvent) => {
    const t = e.touches[0];
    if (!t) return;
    onMove(t.clientX, t.clientY);
    e.preventDefault();
  };
  const touchEnd = () => onEnd();

  const mouseDown = (e: MouseEvent) => onStart(e.clientX, e.clientY);
  const mouseMove = (e: MouseEvent) => onMove(e.clientX, e.clientY);
  const mouseUp = () => onEnd();

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
  el.addEventListener("mousedown", mouseDown);
  window.addEventListener("mousemove", mouseMove);
  window.addEventListener("mouseup", mouseUp);
  window.addEventListener("keydown", keyDown);

  return () => {
    el.removeEventListener("touchstart", touchStart);
    el.removeEventListener("touchmove", touchMove);
    el.removeEventListener("touchend", touchEnd);
    el.removeEventListener("touchcancel", touchEnd);
    el.removeEventListener("mousedown", mouseDown);
    window.removeEventListener("mousemove", mouseMove);
    window.removeEventListener("mouseup", mouseUp);
    window.removeEventListener("keydown", keyDown);
  };
}

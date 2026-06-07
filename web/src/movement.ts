import { cellIndex, hasWall, type Maze, N, E, S, W } from "./maze";
import type { Direction } from "./input";

export const DIR_FROM_NAME: Record<Direction, number> = {
  up: N,
  right: E,
  down: S,
  left: W,
};

const VEC: Record<number, [number, number]> = {
  [N]: [0, -1],
  [E]: [1, 0],
  [S]: [0, 1],
  [W]: [-1, 0],
};

/**
 * Returns true if the player can move from (x,y) in the given direction
 * (i.e. there's no wall blocking that edge).
 */
export function canMove(
  maze: Maze,
  x: number,
  y: number,
  dir: number,
): boolean {
  return !hasWall(maze, x, y, dir);
}

/**
 * From an integer cell, list the open directions excluding the one the player
 * is currently traveling along (so a straight corridor is NOT a "decision").
 * A cell is a decision point if `openExits(...).length >= 2`, or if it's a
 * dead-end (== 0), or if the only continuation requires a turn.
 */
export function openExits(
  maze: Maze,
  x: number,
  y: number,
  excludeDir: number | null,
): number[] {
  const exits: number[] = [];
  for (let d = 0; d < 4; d++) {
    if (d === excludeDir) continue;
    if (canMove(maze, x, y, d)) exits.push(d);
  }
  return exits;
}

export function isDecisionCell(
  maze: Maze,
  x: number,
  y: number,
  incomingDir: number | null,
): boolean {
  // Goal is always a decision (stop).
  if (x === maze.goal.x && y === maze.goal.y) return true;
  // The walker only stops when the player actually has to choose. A single-
  // exit cell is auto-routed even when it's a forced turn — the player isn't
  // making a decision there, the maze is. Only a real fork (2+ exits) or a
  // dead-end (0 exits) stops the walker.
  const back = incomingDir === null ? null : (incomingDir + 2) % 4;
  const exits = openExits(maze, x, y, back);
  if (exits.length === 0) return true; // dead-end
  if (exits.length === 1) return false; // single forced path — auto-take
  return true;
}

export interface MovementState {
  // Logical grid position.
  cellX: number;
  cellY: number;
  // Fractional position used by the renderer (lerps from cell to cell).
  renderX: number;
  renderY: number;
  // Current travel direction, or null when idle (waiting for input).
  dir: number | null;
  // Queued next direction from latest swipe. Applied at next decision cell.
  queuedDir: number | null;
  // Progress from 0..1 between current cell and next cell.
  progress: number;
  // Cell-index set of cells the dot has occupied in this maze. Used for the
  // trail (PRD §6.2). Backtracking through a cell is a no-op on this set.
  visited: Set<number>;
}

export function createMovementState(maze: Maze): MovementState {
  const visited = new Set<number>();
  visited.add(cellIndex(maze.size, maze.start.x, maze.start.y));
  return {
    cellX: maze.start.x,
    cellY: maze.start.y,
    renderX: maze.start.x,
    renderY: maze.start.y,
    dir: null,
    queuedDir: null,
    progress: 0,
    visited,
  };
}

/** Cells per second. Tuned for "snappy but readable". */
export const SPEED = 7;

export interface StepResult {
  reachedGoal: boolean;
}

/**
 * Advance the movement state by dt seconds. Returns true if the goal was
 * reached this step.
 */
export function step(maze: Maze, s: MovementState, dt: number): StepResult {
  if (s.dir === null) {
    // Idle: see if a queued direction can be honoured.
    if (s.queuedDir !== null && canMove(maze, s.cellX, s.cellY, s.queuedDir)) {
      s.dir = s.queuedDir;
      s.queuedDir = null;
    } else {
      return { reachedGoal: false };
    }
  }

  s.progress += dt * SPEED;

  while (s.progress >= 1) {
    s.progress -= 1;
    const [vx, vy] = VEC[s.dir!]!;
    s.cellX += vx;
    s.cellY += vy;
    s.renderX = s.cellX;
    s.renderY = s.cellY;
    s.visited.add(cellIndex(maze.size, s.cellX, s.cellY));

    if (s.cellX === maze.goal.x && s.cellY === maze.goal.y) {
      s.dir = null;
      s.queuedDir = null;
      s.progress = 0;
      return { reachedGoal: true };
    }

    // At a new cell — auto-route through any single-exit cell, only stop at
    // forks / dead-ends / goal.
    const back = (s.dir! + 2) % 4;
    const exits = openExits(maze, s.cellX, s.cellY, back);
    if (exits.length === 0) {
      // Dead-end.
      s.dir = null;
      s.progress = 0;
      return { reachedGoal: false };
    } else if (exits.length === 1) {
      // Single forced path — auto-take it (might be a turn).
      s.dir = exits[0]!;
    } else {
      // Real fork. Honour the queued direction if valid, otherwise stop.
      if (s.queuedDir !== null && canMove(maze, s.cellX, s.cellY, s.queuedDir)) {
        s.dir = s.queuedDir;
        s.queuedDir = null;
      } else {
        s.dir = null;
        s.progress = 0;
        return { reachedGoal: false };
      }
    }
  }

  // Interpolate render position between cells.
  if (s.dir !== null) {
    const [vx, vy] = VEC[s.dir]!;
    s.renderX = s.cellX + vx * s.progress;
    s.renderY = s.cellY + vy * s.progress;
  }
  return { reachedGoal: false };
}

export function queueDirection(
  maze: Maze,
  s: MovementState,
  dir: number,
): void {
  // If currently idle at a cell and this direction is valid, start immediately.
  if (s.dir === null && canMove(maze, s.cellX, s.cellY, dir)) {
    s.dir = dir;
    s.queuedDir = null;
    s.progress = 0;
    return;
  }
  s.queuedDir = dir;
}

import { hasWall, type Maze, N, E, S, W } from "./maze";
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
  // Decision = anything other than "exactly one forward exit".
  const back = incomingDir === null ? null : (incomingDir + 2) % 4;
  const exits = openExits(maze, x, y, back);
  if (exits.length === 0) return true; // dead-end
  if (exits.length === 1 && incomingDir !== null && exits[0] === incomingDir)
    return false; // straight corridor
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
}

export function createMovementState(maze: Maze): MovementState {
  return {
    cellX: maze.start.x,
    cellY: maze.start.y,
    renderX: maze.start.x,
    renderY: maze.start.y,
    dir: null,
    queuedDir: null,
    progress: 0,
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

    if (s.cellX === maze.goal.x && s.cellY === maze.goal.y) {
      s.dir = null;
      s.queuedDir = null;
      s.progress = 0;
      return { reachedGoal: true };
    }

    // At a new cell — decide what to do.
    if (isDecisionCell(maze, s.cellX, s.cellY, s.dir)) {
      // If the queued direction is a valid exit from this cell, take it.
      if (s.queuedDir !== null && canMove(maze, s.cellX, s.cellY, s.queuedDir)) {
        s.dir = s.queuedDir;
        s.queuedDir = null;
      } else {
        s.dir = null;
        s.progress = 0;
        return { reachedGoal: false };
      }
    } else {
      // Straight corridor — continue in the same direction. Already set.
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

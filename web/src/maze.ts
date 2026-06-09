import { mulberry32 } from "./rng";
import { sha256Hex } from "./sha256";

// A cell stores which of its 4 walls are present. The grid is `size` × `size`.
// 0 = N, 1 = E, 2 = S, 3 = W.
export const N = 0;
export const E = 1;
export const S = 2;
export const W = 3;

export const DIR_VEC: ReadonlyArray<readonly [number, number]> = [
  [0, -1],
  [1, 0],
  [0, 1],
  [-1, 0],
];

export const OPPOSITE = [S, W, N, E] as const;

/**
 * Reserved bit on `walls[cell]` flagging a weave / bridge cell. When set,
 * all 4 wall bits are clear (both axes have corridor) but the dot cannot
 * turn at this cell — entering on the N/S axis exits N/S, entering E/W
 * exits E/W. The two passages cross *over* without intersecting.
 */
export const BRIDGE_BIT = 1 << 4;

export interface Maze {
  size: number;
  seed: number;
  // walls[idx] = 4-bit wall mask (bit i = wall on direction i) + BRIDGE_BIT.
  walls: Uint8Array;
  start: { x: number; y: number };
  goal: { x: number; y: number };
}

export function cellIndex(size: number, x: number, y: number): number {
  return y * size + x;
}

export function hasWall(maze: Maze, x: number, y: number, dir: number): boolean {
  return (maze.walls[cellIndex(maze.size, x, y)]! & (1 << dir)) !== 0;
}

export function isBridge(maze: Maze, x: number, y: number): boolean {
  return (maze.walls[cellIndex(maze.size, x, y)]! & BRIDGE_BIT) !== 0;
}

/**
 * Recursive backtracker (iterative) carving from a random start. Produces a
 * perfect maze (exactly one path between any two cells). Start and goal are
 * placed at the two cells that are farthest apart along the carve order.
 */
export function generateMaze(size: number, seed: number): Maze {
  return generateMazeWith(size, seed, false);
}

/**
 * Variant that opts the freshly-generated tree maze into the weave post-
 * process: a fraction of straight-corridor cells become bridges (both axes
 * open, but movement constrained to the entry axis — no turning there).
 */
export function generateMazeWith(
  size: number,
  seed: number,
  weave: boolean,
): Maze {
  const rand = mulberry32(seed);
  const walls = new Uint8Array(size * size).fill(0b1111);
  const visited = new Uint8Array(size * size);

  const startX = Math.floor(rand() * size);
  const startY = Math.floor(rand() * size);
  const stack: Array<[number, number]> = [[startX, startY]];
  visited[cellIndex(size, startX, startY)] = 1;

  // Track depth-first distance from start to pick a far-away goal.
  const distance = new Int32Array(size * size).fill(-1);
  distance[cellIndex(size, startX, startY)] = 0;
  let farthest = { x: startX, y: startY, d: 0 };

  while (stack.length > 0) {
    const [x, y] = stack[stack.length - 1]!;
    const neighbours: number[] = [];
    for (let d = 0; d < 4; d++) {
      const nx = x + DIR_VEC[d]![0];
      const ny = y + DIR_VEC[d]![1];
      if (
        nx >= 0 &&
        nx < size &&
        ny >= 0 &&
        ny < size &&
        !visited[cellIndex(size, nx, ny)]
      ) {
        neighbours.push(d);
      }
    }
    if (neighbours.length === 0) {
      stack.pop();
      continue;
    }
    const d = neighbours[Math.floor(rand() * neighbours.length)]!;
    const nx = x + DIR_VEC[d]![0];
    const ny = y + DIR_VEC[d]![1];
    // Knock down the wall both sides.
    walls[cellIndex(size, x, y)]! &= ~(1 << d);
    walls[cellIndex(size, nx, ny)]! &= ~(1 << OPPOSITE[d]);
    visited[cellIndex(size, nx, ny)] = 1;
    const nd = distance[cellIndex(size, x, y)]! + 1;
    distance[cellIndex(size, nx, ny)] = nd;
    if (nd > farthest.d) farthest = { x: nx, y: ny, d: nd };
    stack.push([nx, ny]);
  }

  if (weave) {
    addBridges(walls, size, rand, startX, startY, farthest.x, farthest.y);
  }

  return {
    size,
    seed,
    walls,
    start: { x: startX, y: startY },
    goal: { x: farthest.x, y: farthest.y },
  };
}

/**
 * Post-process the tree maze to add weave bridges. Mirrors `add_bridges` in
 * `core/src/maze.rs` — same probability (22%), same eligibility rule, same
 * skip set (start, goal, neighbours of existing bridges).
 */
function addBridges(
  walls: Uint8Array,
  size: number,
  rand: () => number,
  startX: number,
  startY: number,
  goalX: number,
  goalY: number,
) {
  if (size < 4) return;
  const probability = 0.22;
  const startIdx = cellIndex(size, startX, startY);
  const goalIdx = cellIndex(size, goalX, goalY);
  for (let y = 1; y < size - 1; y++) {
    for (let x = 1; x < size - 1; x++) {
      const idx = cellIndex(size, x, y);
      if (idx === startIdx || idx === goalIdx) continue;
      const mask = walls[idx]! & 0b1111;
      const nsCorridor =
        (mask & ((1 << N) | (1 << S))) === 0 &&
        (mask & ((1 << E) | (1 << W))) === ((1 << E) | (1 << W));
      const ewCorridor =
        (mask & ((1 << E) | (1 << W))) === 0 &&
        (mask & ((1 << N) | (1 << S))) === ((1 << N) | (1 << S));
      if (!nsCorridor && !ewCorridor) continue;
      const pa = nsCorridor ? [x + 1, y] : [x, y + 1];
      const pb = nsCorridor ? [x - 1, y] : [x, y - 1];
      const paIdx = cellIndex(size, pa[0]!, pa[1]!);
      const pbIdx = cellIndex(size, pb[0]!, pb[1]!);
      if ((walls[paIdx]! & BRIDGE_BIT) !== 0 || (walls[pbIdx]! & BRIDGE_BIT) !== 0) {
        continue;
      }
      if (rand() > probability) continue;
      walls[idx] = (walls[idx]! & ~0b1111) | BRIDGE_BIT;
      if (nsCorridor) {
        walls[paIdx]! &= ~(1 << W);
        walls[pbIdx]! &= ~(1 << E);
      } else {
        walls[paIdx]! &= ~(1 << N);
        walls[pbIdx]! &= ~(1 << S);
      }
    }
  }
}

/**
 * Stable hash of a maze's structure. Used to ensure the same maze is never
 * shown twice on the same device. Includes size + start + goal + walls.
 */
export async function hashMaze(maze: Maze): Promise<string> {
  const header = new Uint8Array([
    maze.size,
    maze.start.x,
    maze.start.y,
    maze.goal.x,
    maze.goal.y,
  ]);
  const buf = new Uint8Array(header.length + maze.walls.length);
  buf.set(header, 0);
  buf.set(maze.walls, header.length);
  // `crypto.subtle` only exists in a secure context (HTTPS or localhost). When
  // the page is served over plain HTTP — e.g. a phone hitting the LAN dev
  // server by IP — it's undefined, so fall back to the JS SHA-256, which
  // returns the same digest. Without this every maze throws and the board
  // never renders.
  if (globalThis.crypto?.subtle) {
    const digest = await crypto.subtle.digest("SHA-256", buf);
    return [...new Uint8Array(digest)]
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
  }
  return sha256Hex(buf);
}

import { mulberry32 } from "./rng";

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

export interface Maze {
  size: number;
  seed: number;
  // walls[y][x] is a 4-bit mask. Bit i set = wall on direction i is present.
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

/**
 * Recursive backtracker (iterative) carving from a random start. Produces a
 * perfect maze (exactly one path between any two cells). Start and goal are
 * placed at the two cells that are farthest apart along the carve order.
 */
export function generateMaze(size: number, seed: number): Maze {
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

  return {
    size,
    seed,
    walls,
    start: { x: startX, y: startY },
    goal: { x: farthest.x, y: farthest.y },
  };
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
  const digest = await crypto.subtle.digest("SHA-256", buf);
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

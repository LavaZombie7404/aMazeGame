use crate::rng::Mulberry32;
use sha2::{Digest, Sha256};

/// Wall directions. Same numbering as `web/src/maze.ts`.
pub const N: u8 = 0;
pub const E: u8 = 1;
pub const S: u8 = 2;
pub const W: u8 = 3;

const OPPOSITE: [u8; 4] = [S, W, N, E];
const DIR_VEC: [(i32, i32); 4] = [(0, -1), (1, 0), (0, 1), (-1, 0)];

pub struct Maze {
    pub size: u32,
    pub seed: u32,
    /// Each byte is a 4-bit mask: bit `d` set = wall on direction `d`.
    pub walls: Vec<u8>,
    pub start: (u32, u32),
    pub goal: (u32, u32),
}

#[inline]
pub fn cell_index(size: u32, x: u32, y: u32) -> usize {
    (y * size + x) as usize
}

#[inline]
pub fn has_wall(walls: &[u8], size: u32, x: u32, y: u32, dir: u8) -> bool {
    walls[cell_index(size, x, y)] & (1u8 << dir) != 0
}

/// Iterative recursive backtracker. Mirrors `generateMaze` in
/// `web/src/maze.ts`; the same `(size, seed)` produces the same maze layout.
/// Start cell is chosen randomly; goal is the deepest cell reached by the DFS.
pub fn generate(size: u32, seed: u32) -> Maze {
    assert!(size >= 2);
    let mut rand = Mulberry32::new(seed);
    let n = (size * size) as usize;
    let mut walls = vec![0b1111u8; n];
    let mut visited = vec![0u8; n];

    let start_x = (rand.next_f64() * size as f64) as u32;
    let start_y = (rand.next_f64() * size as f64) as u32;
    let mut stack: Vec<(u32, u32)> = vec![(start_x, start_y)];
    visited[cell_index(size, start_x, start_y)] = 1;

    let mut distance = vec![-1i32; n];
    distance[cell_index(size, start_x, start_y)] = 0;
    let mut farthest = (start_x, start_y, 0i32);

    while let Some(&(x, y)) = stack.last() {
        let mut neighbours: [u8; 4] = [0; 4];
        let mut nn = 0usize;
        for d in 0..4u8 {
            let (vx, vy) = DIR_VEC[d as usize];
            let nx = x as i32 + vx;
            let ny = y as i32 + vy;
            if nx >= 0
                && ny >= 0
                && (nx as u32) < size
                && (ny as u32) < size
                && visited[cell_index(size, nx as u32, ny as u32)] == 0
            {
                neighbours[nn] = d;
                nn += 1;
            }
        }
        if nn == 0 {
            stack.pop();
            continue;
        }
        let pick = neighbours[(rand.next_f64() * nn as f64) as usize];
        let (vx, vy) = DIR_VEC[pick as usize];
        let nx = (x as i32 + vx) as u32;
        let ny = (y as i32 + vy) as u32;
        walls[cell_index(size, x, y)] &= !(1u8 << pick);
        walls[cell_index(size, nx, ny)] &= !(1u8 << OPPOSITE[pick as usize]);
        visited[cell_index(size, nx, ny)] = 1;
        let nd = distance[cell_index(size, x, y)] + 1;
        distance[cell_index(size, nx, ny)] = nd;
        if nd > farthest.2 {
            farthest = (nx, ny, nd);
        }
        stack.push((nx, ny));
    }

    Maze {
        size,
        seed,
        walls,
        start: (start_x, start_y),
        goal: (farthest.0, farthest.1),
    }
}

/// SHA-256 of `[size, start.x, start.y, goal.x, goal.y, walls...]`. Stable
/// per-device dedup key for "never repeat" (PRD invariant).
pub fn hash(maze: &Maze) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update([
        maze.size as u8,
        maze.start.0 as u8,
        maze.start.1 as u8,
        maze.goal.0 as u8,
        maze.goal.1 as u8,
    ]);
    h.update(&maze.walls);
    h.finalize().into()
}

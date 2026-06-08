use crate::rng::Mulberry32;
use sha2::{Digest, Sha256};

/// Wall directions. Same numbering as `web/src/maze.ts`.
pub const N: u8 = 0;
pub const E: u8 = 1;
pub const S: u8 = 2;
pub const W: u8 = 3;

const OPPOSITE: [u8; 4] = [S, W, N, E];
const DIR_VEC: [(i32, i32); 4] = [(0, -1), (1, 0), (0, 1), (-1, 0)];

/// Reserved bit on `walls[cell]` flagging a "weave" / bridge cell. When set,
/// the cell has corridor on *both* axes (all 4 wall bits clear) but the
/// dot cannot turn here — entering on the N/S axis exits on the same axis,
/// and the E/W corridor passes *under* without intersecting.
pub const BRIDGE_BIT: u8 = 1 << 4;

pub struct Maze {
    pub size: u32,
    pub seed: u32,
    /// Each byte is a 4-bit wall mask (bit `d` set = wall on direction `d`)
    /// plus bit 4 = [[BRIDGE_BIT]] (this cell is a weave bridge).
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

#[inline]
pub fn is_bridge(walls: &[u8], size: u32, x: u32, y: u32) -> bool {
    walls[cell_index(size, x, y)] & BRIDGE_BIT != 0
}

/// Iterative recursive backtracker. Mirrors `generateMaze` in
/// `web/src/maze.ts`; the same `(size, seed)` produces the same maze layout.
/// Start cell is chosen randomly; goal is the deepest cell reached by the DFS.
pub fn generate(size: u32, seed: u32) -> Maze {
    generate_with(size, seed, false)
}

/// Variant with the weave/bridges post-process opt-in.
pub fn generate_with(size: u32, seed: u32, weave: bool) -> Maze {
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

    if weave {
        add_bridges(&mut walls, size, &mut rand, start_x, start_y, farthest.0, farthest.1);
    }

    Maze {
        size,
        seed,
        walls,
        start: (start_x, start_y),
        goal: (farthest.0, farthest.1),
    }
}

/// Post-process the freshly-generated tree maze to add weave bridges.
///
/// Heuristic: for every interior cell that's a *straight corridor* (open on
/// exactly two opposite directions, walled on the perpendicular axis), with
/// ~22% probability promote it to a bridge — clear the perpendicular walls
/// and set the bridge bit. The maze stops being a perfect tree (bridges
/// introduce cycles), but the navigation rule "no turning on a bridge"
/// means players can't shortcut through one, so the gameplay invariant
/// (unique solve path on the dot's logical axis) is preserved.
///
/// Skipped: start, goal, and any cell already adjacent to a bridge (so
/// bridges don't cluster).
fn add_bridges(
    walls: &mut [u8],
    size: u32,
    rand: &mut Mulberry32,
    start_x: u32, start_y: u32,
    goal_x: u32, goal_y: u32,
) {
    if size < 4 {
        return;
    }
    let n = size;
    let probability = 0.22f64;
    let start_idx = cell_index(n, start_x, start_y);
    let goal_idx = cell_index(n, goal_x, goal_y);
    // Snapshot which cells are already bridges so we don't read+write the
    // same field while scanning.
    for y in 1..(n - 1) {
        for x in 1..(n - 1) {
            let idx = cell_index(n, x, y);
            if idx == start_idx || idx == goal_idx {
                continue;
            }
            let mask = walls[idx] & 0b1111;
            // Eligible straight: N+S open and E+W walled, OR E+W open and N+S walled.
            let ns_corridor = (mask & ((1 << N) | (1 << S))) == 0
                && (mask & ((1 << E) | (1 << W))) == ((1 << E) | (1 << W));
            let ew_corridor = (mask & ((1 << E) | (1 << W))) == 0
                && (mask & ((1 << N) | (1 << S))) == ((1 << N) | (1 << S));
            if !ns_corridor && !ew_corridor {
                continue;
            }
            // Need both perpendicular neighbours in-bounds and not bridges.
            let (pa, pb) = if ns_corridor {
                ((x + 1, y), (x - 1, y)) // east, west
            } else {
                ((x, y + 1), (x, y - 1)) // south, north
            };
            let pa_idx = cell_index(n, pa.0, pa.1);
            let pb_idx = cell_index(n, pb.0, pb.1);
            if walls[pa_idx] & BRIDGE_BIT != 0 || walls[pb_idx] & BRIDGE_BIT != 0 {
                continue;
            }
            if rand.next_f64() > probability {
                continue;
            }
            // Carve the perpendicular passage. On the bridge cell itself
            // every wall bit is cleared; the perpendicular neighbours have
            // their facing wall cleared too.
            walls[idx] = (walls[idx] & !0b1111) | BRIDGE_BIT;
            if ns_corridor {
                walls[pa_idx] &= !(1u8 << W); // east neighbour's west wall
                walls[pb_idx] &= !(1u8 << E); // west neighbour's east wall
            } else {
                walls[pa_idx] &= !(1u8 << N); // south neighbour's north wall
                walls[pb_idx] &= !(1u8 << S); // north neighbour's south wall
            }
        }
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

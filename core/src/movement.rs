use crate::maze::{cell_index, has_wall, Maze};
use std::collections::BTreeSet;

// Direction constants are exposed for clarity; the actual wall masks use
// these bit positions.
#[allow(dead_code)]
pub mod dir {
    pub const N: u8 = 0;
    pub const E: u8 = 1;
    pub const S: u8 = 2;
    pub const W: u8 = 3;
}

const VEC: [(i32, i32); 4] = [(0, -1), (1, 0), (0, 1), (-1, 0)];

/// Cells per second. Match `SPEED` in `web/src/movement.ts`.
pub const SPEED: f32 = 7.0;

/// Mirror of the TS `MovementState`. Fractional `render_*` so the WASM consumer
/// (web canvas or native Compose Canvas) can interpolate between cells.
pub struct MovementState {
    pub cell_x: u32,
    pub cell_y: u32,
    pub render_x: f32,
    pub render_y: f32,
    pub dir: i32,        // -1 = idle
    pub queued_dir: i32, // -1 = none
    pub progress: f32,
    pub visited: BTreeSet<u32>, // cell indices
    /// When true, stop at every forced turn ("corner") in addition to the
    /// usual decision cells. Straight pass-through still auto-routes. Mirrors
    /// the `legacy_movement` option on the web side.
    pub legacy_movement: bool,
    /// Optional extra wall mask, OR'd with `maze.walls` for movement-collision
    /// decisions. Empty (len 0) when no overlay is active. Renderers never
    /// see this — used by auto mode to seal every edge except the solution
    /// path, so the walker can't detour through side branches.
    pub extra_walls: Vec<u8>,
}

impl MovementState {
    pub fn new(maze: &Maze) -> Self {
        let mut visited = BTreeSet::new();
        visited.insert(cell_index(maze.size, maze.start.0, maze.start.1) as u32);
        Self {
            cell_x: maze.start.0,
            cell_y: maze.start.1,
            render_x: maze.start.0 as f32,
            render_y: maze.start.1 as f32,
            dir: -1,
            queued_dir: -1,
            progress: 0.0,
            visited,
            legacy_movement: false,
            extra_walls: Vec::new(),
        }
    }
}

pub fn can_move(maze: &Maze, x: u32, y: u32, dir: u8) -> bool {
    !has_wall(&maze.walls, maze.size, x, y, dir)
}

/// `can_move` but also honours an optional overlay wall mask on the state.
/// Used by the auto-solver to lock the dot onto the solution path while the
/// rendered maze stays intact.
fn can_move_ext(maze: &Maze, s: &MovementState, x: u32, y: u32, dir: u8) -> bool {
    if has_wall(&maze.walls, maze.size, x, y, dir) {
        return false;
    }
    if !s.extra_walls.is_empty()
        && has_wall(&s.extra_walls, maze.size, x, y, dir)
    {
        return false;
    }
    true
}

/// Decision cell = junction, dead-end, goal, or anywhere the player can't
/// keep going straight. Same logic as `web/src/movement.ts::isDecisionCell`.
pub fn is_decision_cell(maze: &Maze, x: u32, y: u32, incoming_dir: i32) -> bool {
    if x == maze.goal.0 && y == maze.goal.1 {
        return true;
    }
    let back = if incoming_dir < 0 {
        -1
    } else {
        (incoming_dir + 2) % 4
    };
    let mut exits = Vec::with_capacity(4);
    for d in 0..4i32 {
        if d == back {
            continue;
        }
        if can_move(maze, x, y, d as u8) {
            exits.push(d);
        }
    }
    if exits.is_empty() {
        return true;
    }
    // Single-exit cells (including forced turns) are auto-routed — the
    // player isn't choosing there. Only stop at real forks or dead-ends.
    exits.len() != 1
}

pub struct StepResult {
    pub reached_goal: bool,
}

pub fn step(maze: &Maze, s: &mut MovementState, dt: f32) -> StepResult {
    if s.dir < 0 {
        if s.queued_dir >= 0 && can_move_ext(maze, s, s.cell_x, s.cell_y, s.queued_dir as u8) {
            s.dir = s.queued_dir;
            s.queued_dir = -1;
        } else {
            return StepResult { reached_goal: false };
        }
    }

    s.progress += dt * SPEED;

    while s.progress >= 1.0 {
        s.progress -= 1.0;
        let (vx, vy) = VEC[s.dir as usize];
        s.cell_x = (s.cell_x as i32 + vx) as u32;
        s.cell_y = (s.cell_y as i32 + vy) as u32;
        s.render_x = s.cell_x as f32;
        s.render_y = s.cell_y as f32;
        s.visited
            .insert(cell_index(maze.size, s.cell_x, s.cell_y) as u32);

        if s.cell_x == maze.goal.0 && s.cell_y == maze.goal.1 {
            s.dir = -1;
            s.queued_dir = -1;
            s.progress = 0.0;
            return StepResult { reached_goal: true };
        }

        // Default: auto-route through any single-exit cell; only stop at
        // forks / dead-ends / goal. Legacy: also stop at a forced turn.
        let incoming = s.dir;
        let back = (incoming + 2) % 4;
        let mut single_exit: i32 = -1;
        let mut exit_count = 0u32;
        for d in 0..4i32 {
            if d == back {
                continue;
            }
            if can_move_ext(maze, s, s.cell_x, s.cell_y, d as u8) {
                exit_count += 1;
                if exit_count == 1 {
                    single_exit = d;
                }
            }
        }
        if exit_count == 0 {
            s.dir = -1;
            s.progress = 0.0;
            return StepResult { reached_goal: false };
        } else if exit_count == 1 && (!s.legacy_movement || single_exit == incoming) {
            s.dir = single_exit;
        } else if s.queued_dir >= 0
            && can_move_ext(maze, s, s.cell_x, s.cell_y, s.queued_dir as u8)
        {
            s.dir = s.queued_dir;
            s.queued_dir = -1;
        } else {
            s.dir = -1;
            s.progress = 0.0;
            return StepResult { reached_goal: false };
        }
    }

    if s.dir >= 0 {
        let (vx, vy) = VEC[s.dir as usize];
        s.render_x = s.cell_x as f32 + vx as f32 * s.progress;
        s.render_y = s.cell_y as f32 + vy as f32 * s.progress;
    }
    StepResult { reached_goal: false }
}

pub fn queue_direction(maze: &Maze, s: &mut MovementState, dir: u8) {
    if s.dir < 0 && can_move_ext(maze, s, s.cell_x, s.cell_y, dir) {
        s.dir = dir as i32;
        s.queued_dir = -1;
        s.progress = 0.0;
        return;
    }
    s.queued_dir = dir as i32;
}

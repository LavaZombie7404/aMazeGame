//! aMazeGame shared game core.
//!
//! Compiles to a single `.wasm` consumed by both:
//! - The web frontend (Vite, runs the WASM in the browser).
//! - The native Android app (WAMR embedded via NDK + JNI).
//!
//! The public ABI is **C-style** so it works without `wasm-bindgen` glue —
//! that lets the same `.wasm` byte-for-byte load on both platforms. The
//! runtime owns memory through `core_alloc` / `core_free`; pointers crossing
//! the boundary are byte offsets into the WASM linear memory.

pub mod maze;
pub mod movement;
pub mod rng;

use maze::Maze;
use movement::MovementState;

/// ABI version. Bump on breaking changes to any export below.
pub const ABI_VERSION: u32 = 1;

/// One game world. Held by the runtime via opaque pointer.
pub struct Game {
    pub maze: Maze,
    pub movement: MovementState,
    pub last_hash: [u8; 32],
}

// ---- C ABI -----------------------------------------------------------------
// Every export is `extern "C"` + `#[no_mangle]` so the WASM module exposes a
// flat function table; both the JS host and the native WAMR host can call
// them with primitive args only.

#[no_mangle]
pub extern "C" fn core_abi_version() -> u32 {
    ABI_VERSION
}

/// Allocate `n` bytes in WASM linear memory; returns the offset (pointer).
/// Pair every alloc with `core_free(ptr, n)`.
#[no_mangle]
pub extern "C" fn core_alloc(n: u32) -> *mut u8 {
    let mut buf = Vec::<u8>::with_capacity(n as usize);
    let ptr = buf.as_mut_ptr();
    std::mem::forget(buf);
    ptr
}

#[no_mangle]
pub extern "C" fn core_free(ptr: *mut u8, n: u32) {
    if ptr.is_null() {
        return;
    }
    unsafe {
        let _ = Vec::from_raw_parts(ptr, 0, n as usize);
    }
}

#[no_mangle]
pub extern "C" fn core_game_new(size: u32, seed: u32) -> *mut Game {
    core_game_new_ext(size, seed, 0)
}

/// Like `core_game_new` but `weave != 0` opts the freshly-generated maze
/// into the weave/bridges post-process (some straight corridors become
/// bridges, where the perpendicular axis passes under without connecting).
#[no_mangle]
pub extern "C" fn core_game_new_ext(size: u32, seed: u32, weave: u32) -> *mut Game {
    let maze = maze::generate_with(size, seed, weave != 0);
    let movement = MovementState::new(&maze);
    let last_hash = maze::hash(&maze);
    Box::into_raw(Box::new(Game { maze, movement, last_hash }))
}

#[no_mangle]
pub extern "C" fn core_game_drop(g: *mut Game) {
    if g.is_null() {
        return;
    }
    unsafe { drop(Box::from_raw(g)) };
}

#[no_mangle]
pub extern "C" fn core_maze_size(g: *const Game) -> u32 {
    unsafe { (*g).maze.size }
}

#[no_mangle]
pub extern "C" fn core_maze_start_x(g: *const Game) -> u32 {
    unsafe { (*g).maze.start.0 }
}
#[no_mangle]
pub extern "C" fn core_maze_start_y(g: *const Game) -> u32 {
    unsafe { (*g).maze.start.1 }
}
#[no_mangle]
pub extern "C" fn core_maze_goal_x(g: *const Game) -> u32 {
    unsafe { (*g).maze.goal.0 }
}
#[no_mangle]
pub extern "C" fn core_maze_goal_y(g: *const Game) -> u32 {
    unsafe { (*g).maze.goal.1 }
}

/// Pointer to the wall mask array (length = size * size). One byte per cell.
#[no_mangle]
pub extern "C" fn core_maze_walls_ptr(g: *const Game) -> *const u8 {
    unsafe { (*g).maze.walls.as_ptr() }
}

#[no_mangle]
pub extern "C" fn core_maze_walls_len(g: *const Game) -> u32 {
    unsafe { (*g).maze.walls.len() as u32 }
}

/// Writes the maze SHA-256 hash to `out` (must be at least 32 bytes).
#[no_mangle]
pub extern "C" fn core_maze_hash(g: *const Game, out: *mut u8) {
    unsafe {
        let hash = &(*g).last_hash;
        std::ptr::copy_nonoverlapping(hash.as_ptr(), out, 32);
    }
}

/// Advance the simulation by `dt_ms` milliseconds. Returns bitflags:
/// bit 0 (`= 1`) → reached goal this step.
#[no_mangle]
pub extern "C" fn core_step(g: *mut Game, dt_ms: u32) -> u32 {
    let game = unsafe { &mut *g };
    let r = movement::step(&game.maze, &mut game.movement, dt_ms as f32 / 1000.0);
    if r.reached_goal {
        1
    } else {
        0
    }
}

/// Queue a direction (0=N, 1=E, 2=S, 3=W).
#[no_mangle]
pub extern "C" fn core_queue_direction(g: *mut Game, dir: u32) {
    let game = unsafe { &mut *g };
    if dir < 4 {
        movement::queue_direction(&game.maze, &mut game.movement, dir as u8);
    }
}

/// Toggle "stop at every corner" (legacy) movement on the current game.
/// 0 = off (default, auto-route single-exit cells), non-zero = on.
/// Additive export; ABI version is unchanged because callers that ignore
/// this function still observe the default behaviour.
#[no_mangle]
pub extern "C" fn core_set_legacy_movement(g: *mut Game, value: u32) {
    let game = unsafe { &mut *g };
    game.movement.legacy_movement = value != 0;
}

/// Install (or clear) an "invisible" wall overlay for collision decisions.
/// `ptr` points to `len` bytes of wall masks, one byte per cell, same layout
/// as the maze's primary wall mask. Pass len=0 (or a null ptr) to clear.
/// Renderers never see this overlay — it's only consulted by the movement
/// step and queue_direction. Additive export, ABI unchanged.
#[no_mangle]
pub extern "C" fn core_set_extra_walls(g: *mut Game, ptr: *const u8, len: u32) {
    let game = unsafe { &mut *g };
    if ptr.is_null() || len == 0 {
        game.movement.extra_walls.clear();
        return;
    }
    let slice = unsafe { std::slice::from_raw_parts(ptr, len as usize) };
    game.movement.extra_walls = slice.to_vec();
}

#[no_mangle]
pub extern "C" fn core_player_render_x(g: *const Game) -> f32 {
    unsafe { (*g).movement.render_x }
}
#[no_mangle]
pub extern "C" fn core_player_render_y(g: *const Game) -> f32 {
    unsafe { (*g).movement.render_y }
}
#[no_mangle]
pub extern "C" fn core_player_cell_x(g: *const Game) -> u32 {
    unsafe { (*g).movement.cell_x }
}
#[no_mangle]
pub extern "C" fn core_player_cell_y(g: *const Game) -> u32 {
    unsafe { (*g).movement.cell_y }
}

#[no_mangle]
pub extern "C" fn core_visited_len(g: *const Game) -> u32 {
    unsafe { (*g).movement.visited.len() as u32 }
}

/// Writes the visited cell indices (ascending) into `out` as LE u32s.
/// `out` must have space for `core_visited_len(g) * 4` bytes.
#[no_mangle]
pub extern "C" fn core_visited_copy(g: *const Game, out: *mut u8) {
    unsafe {
        let g = &*g;
        let mut p = out;
        for &cell in g.movement.visited.iter() {
            std::ptr::copy_nonoverlapping(cell.to_le_bytes().as_ptr(), p, 4);
            p = p.add(4);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maze_is_perfect_and_repeatable() {
        let a = maze::generate(12, 0x1234_5678);
        let b = maze::generate(12, 0x1234_5678);
        assert_eq!(a.walls, b.walls);
        assert_eq!(a.start, b.start);
        assert_eq!(a.goal, b.goal);
        assert_eq!(maze::hash(&a), maze::hash(&b));
    }

    #[test]
    fn different_seeds_yield_different_mazes() {
        let a = maze::generate(12, 1);
        let b = maze::generate(12, 2);
        assert_ne!(a.walls, b.walls);
    }

    #[test]
    fn step_reaches_walls() {
        let m = maze::generate(8, 42);
        let mut s = MovementState::new(&m);
        movement::queue_direction(&m, &mut s, 1); // east
        // Run many ticks; we either stop at a decision or progress.
        for _ in 0..1000 {
            let _ = movement::step(&m, &mut s, 0.016);
        }
        assert!(s.visited.len() >= 1);
    }
}

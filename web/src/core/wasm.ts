// Loader + thin TypeScript wrapper around the Rust `amaze-core` WASM module.
//
// The Rust crate exposes a flat C ABI (see `core/src/lib.rs`). This module
// keeps the same shape so a future swap of the existing TS-side
// `web/src/maze.ts` + `movement.ts` for the WASM-backed equivalents is a
// one-line import change in `main.ts`.
//
// The native Android app uses the same `.wasm` via WAMR — see
// `android/app/src/main/cpp/`. Keeping the ABI here in sync with the Rust
// crate's exports is enough to share game logic across platforms.

const CORE_WASM_URL = "/core.wasm";

export interface CoreExports {
  memory: WebAssembly.Memory;

  core_abi_version(): number;
  core_alloc(n: number): number;
  core_free(ptr: number, n: number): void;

  core_game_new(size: number, seed: number): number;
  core_game_drop(g: number): void;

  core_maze_size(g: number): number;
  core_maze_start_x(g: number): number;
  core_maze_start_y(g: number): number;
  core_maze_goal_x(g: number): number;
  core_maze_goal_y(g: number): number;
  core_maze_walls_ptr(g: number): number;
  core_maze_walls_len(g: number): number;
  core_maze_hash(g: number, out: number): void;

  core_step(g: number, dtMs: number): number;
  core_queue_direction(g: number, dir: number): void;

  core_player_render_x(g: number): number;
  core_player_render_y(g: number): number;
  core_player_cell_x(g: number): number;
  core_player_cell_y(g: number): number;
  core_visited_len(g: number): number;
  core_visited_copy(g: number, out: number): void;
}

export const EXPECTED_ABI_VERSION = 1;

let cached: Promise<CoreExports> | null = null;

export function loadCore(): Promise<CoreExports> {
  if (cached) return cached;
  cached = (async () => {
    const res = await fetch(CORE_WASM_URL);
    if (!res.ok) {
      throw new Error(`Failed to fetch ${CORE_WASM_URL}: ${res.status}`);
    }
    const { instance } = await WebAssembly.instantiateStreaming(res, {});
    const x = instance.exports as unknown as CoreExports;
    const abi = x.core_abi_version();
    if (abi !== EXPECTED_ABI_VERSION) {
      throw new Error(
        `core.wasm ABI ${abi} does not match expected ${EXPECTED_ABI_VERSION}`,
      );
    }
    return x;
  })();
  return cached;
}

/** Read raw bytes from the WASM linear memory as a copy. */
export function readBytes(
  x: CoreExports,
  ptr: number,
  len: number,
): Uint8Array {
  return new Uint8Array(x.memory.buffer, ptr, len).slice();
}

/** Convenience: maze hash as a lowercase hex string. */
export function readMazeHash(x: CoreExports, g: number): string {
  const out = x.core_alloc(32);
  try {
    x.core_maze_hash(g, out);
    const bytes = new Uint8Array(x.memory.buffer, out, 32);
    return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
  } finally {
    x.core_free(out, 32);
  }
}

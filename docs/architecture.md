# Architecture

A single-page TS app. No framework. All state lives in the module that owns it; `main.ts` wires the modules together.

```
index.html
└─ src/
   ├─ main.ts        ← boot, animation loop, glue
   ├─ maze.ts        ← grid representation, generator, SHA-256 structural hash
   ├─ rng.ts         ← mulberry32 + crypto seed helpers
   ├─ renderer.ts    ← Canvas drawing (notebook paper + pen-stroke walls + dot)
   ├─ input.ts       ← swipe / pointer / keyboard listeners → Direction
   ├─ movement.ts    ← autonomous walker, decision-cell logic
   ├─ storage.ts     ← sql.js + IndexedDB persistence (player, completed_mazes)
   └─ styles.css     ← layout + dialog + HUD
```

## Module responsibilities

### `maze.ts`
The grid is a flat `Uint8Array` of length `size²`. Each byte is a 4-bit wall mask (N/E/S/W). The generator is an iterative recursive backtracker; it also tracks the deepest cell from the start to place the goal there (interesting paths, not trivial 2-step mazes).

`hashMaze` produces a stable SHA-256 from `[size, start, goal, walls]`. That hash is the dedup key.

### `rng.ts`
Mulberry32 — small, fast, no crypto cost. Seeded from `crypto.getRandomValues` so different runs differ. Maze structure is reproducible from `(size, seed)`.

### `renderer.ts`
Stateless functions. `fitMetrics` recomputes cell size from the canvas's CSS dimensions, accounting for DPR; `render` paints the paper, walls, goal, and dot each frame. Walls use a "hand-drawn" path: many small segments with perpendicular jitter, endpoint overshoot, and occasional ink pooling. Wobble is seeded per maze so it doesn't shimmer between frames.

### `input.ts`
A swipe in axis-distance > `minDistance` emits a direction. Continuous swiping is supported — after each emission, the origin resets so a finger sliding "down then left without lifting" yields two events. Keyboard arrows + WASD work for desktop dev.

### `movement.ts`
The walker holds `{cellX, cellY, dir, queuedDir, progress}`. `step(maze, state, dt)` advances `progress` and rolls integer cells over. At every newly-entered cell it checks `isDecisionCell` — straight corridors are skipped, junctions/dead-ends/goal stop the walker. A queued direction is taken whenever it's a valid exit from the current cell, either at a decision or while idle.

### `storage.ts`
On first `getDb()` call, sql.js is loaded (WASM blob is in the bundle), and any existing DB blob in IndexedDB is restored. Every write call `persist()`s the DB back to IndexedDB. The schema is intentionally tiny:

```sql
CREATE TABLE player (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  name TEXT NOT NULL,
  mazes_completed INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE completed_mazes (
  hash TEXT PRIMARY KEY,
  size INTEGER NOT NULL,
  seed INTEGER NOT NULL,
  completed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

A SQLite database for this is overkill *today*, but the user wanted sql.js so future stats queries (heatmaps, time-per-cell, etc.) are trivially "just SQL".

### `main.ts`
Boot order:
1. Prompt for player name if not set.
2. Generate a unique maze, fit the canvas, attach swipe input, start `requestAnimationFrame`.
3. Each completion: hash the maze, record it, generate a new one.

## Render → input → simulate loop

The loop is the standard `requestAnimationFrame` with a clamped `dt`. Input does *not* mutate position; it only sets `queuedDir`. The simulator owns position transitions, so input order can never "skip" a wall.

## Future: WASM render layer

The renderer is one module with a small surface (`fitMetrics`, `render`). To swap in a WASM/Rust renderer for Geometry-Dash aesthetics, replace this module (or have it forward to a WASM instance). The maze/movement/storage layers don't need to change.

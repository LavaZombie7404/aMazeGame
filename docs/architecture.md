# Architecture

aMazeGame ships on **two platforms** (web + native Android) running **one shared game core** (Rust → WASM). The repo is a monorepo:

```
.
├─ core/      ── Rust crate (lib + cdylib). Generates mazes, steps the
│                autonomous walker, hashes mazes. Compiles to a single
│                amaze_core.wasm consumed by both platforms.
├─ web/       ── Vanilla TypeScript + HTML5 Canvas + Vite. The original
│                playable artefact. Currently runs the gameplay logic
│                in TS; the WASM core is loaded for ABI handshake and
│                will progressively take over.
├─ android/   ── Native Kotlin + Jetpack Compose. Loads the same
│                amaze_core.wasm via WAMR (WebAssembly Micro Runtime)
│                in a JNI bridge — no WebView.
├─ docs/      ── This file, requirements.md, android-debug.md,
│                deployment.md.
├─ scripts/   ── build-core.mjs (Rust → wasm → both platforms),
│                drive-phone.mjs, inspect-phone.mjs.
├─ tests/     ── Playwright e2e for the web app.
└─ Cargo.toml + Cargo workspace at the root.
```

## The shared core (`core/`)

A `cdylib` Rust crate compiled with `cargo build --release --target wasm32-unknown-unknown -p amaze-core`. Produces `target/wasm32-unknown-unknown/release/amaze_core.wasm` (~36 KB).

### Surface

Public exports in `core/src/lib.rs` are **C ABI** — `extern "C"` + `#[no_mangle]` only — so the same `.wasm` runs in:

- The browser, instantiated via `WebAssembly.instantiateStreaming` (no wasm-bindgen glue needed).
- The native Android app, instantiated by WAMR's `wasm_runtime_load` / `wasm_runtime_instantiate`.

The ABI is versioned (`core_abi_version()` → `u32`); hosts handshake the version on load.

```
core_alloc(n)            → ptr            allocate linear memory
core_free(ptr, n)                         free it
core_game_new(size,seed) → *Game          start a new round
core_game_drop(g)                         release
core_maze_size/start_x/start_y/goal_x/goal_y/walls_*  read maze
core_maze_hash(g, out)                    write 32-byte SHA-256 to out
core_step(g, dt_ms)      → flags          advance; bit 0 = goal reached
core_queue_direction(g, dir)              0=N 1=E 2=S 3=W
core_player_render_x/y/cell_x/y          read walker
core_visited_len(g) + core_visited_copy   read trail
```

`core_abi_version` is `1` today. Bump on any breaking change.

### Modules

| File              | Purpose                                                             |
|-------------------|---------------------------------------------------------------------|
| `core/src/rng.rs` | Mulberry32 — byte-for-byte the same as `web/src/rng.ts`.            |
| `core/src/maze.rs`| Iterative recursive-backtracker generator + structural SHA-256 hash.|
| `core/src/movement.rs` | Autonomous walker + decision-cell detection + visited set.     |
| `core/src/lib.rs` | C ABI exports; `Game` opaque type held across host calls.           |

In-tree unit tests cover repeatability of `(size, seed)`, that distinct seeds yield distinct mazes, and that the walker visits cells. `cargo test -p amaze-core` runs them.

### Why a C ABI (no wasm-bindgen)

`wasm-bindgen` emits JavaScript glue, which would not be usable from a native Android host. A pure C ABI keeps the *same* `.wasm` byte-identical across both consumers, and is what WAMR can call directly via `wasm_runtime_call_wasm`. The cost is a more manual host side — `core_alloc`/`core_free` for buffers — which both hosts wrap thinly.

## Web (`web/`)

```
web/
├─ index.html
└─ src/
   ├─ main.ts        ← boot, animation loop, glue
   ├─ core/wasm.ts   ← fetch + instantiate amaze_core.wasm + typed handle
   ├─ maze.ts        ← original TS generator (mirrors core/src/maze.rs)
   ├─ rng.ts         ← original TS Mulberry32 (mirrors core/src/rng.rs)
   ├─ renderer.ts    ← Canvas drawing (notebook paper + pen walls + dot)
   ├─ input.ts       ← swipe / pointer / keyboard → Direction
   ├─ movement.ts    ← original TS walker (mirrors core/src/movement.rs)
   ├─ storage.ts     ← sql.js + IndexedDB (player, completed_mazes)
   ├─ skins/         ← skin definitions; math-textbook is the default
   ├─ shapes/        ← shape library (dot, ring, square, …)
   └─ styles.css     ← layout + dialog + HUD
```

The TS gameplay path (`maze.ts`, `movement.ts`, `rng.ts`) and the Rust core are deliberately kept in sync — same algorithms, same seeds, same outputs. The current web app still runs the TS path for gameplay; `core/wasm.ts` loads the WASM core and logs the ABI version as a smoke test. Swapping the TS path for WASM is one import-shape change in `main.ts` and removes the TS duplicates.

### Render → input → simulate loop

`requestAnimationFrame` with a clamped `dt`. Input only sets `queuedDir`; the simulator owns position transitions, so input order can never "skip" a wall.

### Build

```
npm run core:build   # cargo build → web/public/core.wasm
npm run dev          # core:build + Vite
npm run build        # core:build + tsc + Vite build → dist/
npm run test:e2e     # Playwright (2 projects: desktop-chrome + pixel-7)
```

Vite is configured with `root: "./web"` and `outDir: "./dist"` so CI/CD and GitHub Pages keep working unchanged.

## Android (`android/`)

Standalone Gradle project — open it on its own or run `./gradlew :app:assembleDebug` from `android/`. See [`android/README.md`](../android/README.md) for the full build recipe.

```
android/
└─ app/src/main/
   ├─ AndroidManifest.xml
   ├─ assets/core.wasm           ← staged by Gradle task stageCoreWasm
   ├─ kotlin/com/lavazombie/amazegame/
   │  ├─ MainActivity.kt         ← Compose host; wires runtime + puppet
   │  ├─ GameRuntime.kt          ← high-level facade; StateFlow<GameState>
   │  ├─ CoreBridge.kt           ← typed wrapper over JNI exports
   │  ├─ ui/MazeCanvas.kt        ← Compose Canvas renderer
   │  └─ PuppetServer.kt         ← Ktor on 127.0.0.1:8088
   └─ cpp/
      ├─ CMakeLists.txt          ← FetchContent WAMR, build libamaze_native.so
      └─ core_bridge.cc          ← JNI ↔ WAMR ↔ amaze_core.wasm
```

### WASM hosting

WAMR is fetched by CMake at configure time (pinned to a release tag), built into the same shared library as the JNI bridge (`libamaze_native.so`). The `.wasm` is packaged as an APK asset and read via `AAssetManager`. No WebView, no JS engine — Compose talks straight to WASM via JNI.

### Puppet HTTP API

`PuppetServer` is a Ktor/CIO embedded server bound to `127.0.0.1:8088`. With `adb forward tcp:8088 tcp:8088` you can drive the game from Claude Code on the laptop:

| Method | Path                | Body / Effect                                                |
|--------|---------------------|--------------------------------------------------------------|
| GET    | `/health`           | `{ ok, abiVersion, platform: "android" }`                    |
| GET    | `/state`            | `{ mazeSize, start[], goal[], player[], visitedLen, hash }`  |
| POST   | `/control/swipe`    | `{ direction: "N"\|"E"\|"S"\|"W" }` — queues a direction     |
| POST   | `/control/reset`    | `{ size?, seed? }` — abandons current maze, starts a new one |
| POST   | `/scenarios`        | (Stubbed) scenario playback + assertions                     |

This mirrors the CDP-style workflow we already use against Chrome on the phone (see `docs/android-debug.md` §5), with the same `adb forward` ergonomics. From WSL:

```bash
adb forward tcp:8088 tcp:8088
curl -s http://localhost:8088/state | jq .
```

## What's still in flight

- Android JNI bridge implements only the ABI-handshake call today; per-function bridges (`nativeGameNew`, `nativeStep`, …) land alongside the live Compose renderer.
- Web still gameplays from TS — flipping it to the WASM core is a one-line import change once we want.
- Hand-drawn pen walls, trail, character shape library: web has them; Android scaffolds the renderer path, full parity comes next.
- Puppet API `/scenarios` is a stub.

## Future: replace TS gameplay logic with WASM

Once the Android side proves the WASM core fits the gameplay needs, drop `web/src/maze.ts`, `movement.ts`, `rng.ts`. `main.ts` imports `loadCore` and uses the same handles as Android. Render-only code stays TS.

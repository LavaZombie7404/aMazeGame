# aMazeGame — Android native app

A native Android app (Kotlin + Jetpack Compose) that runs the **same Rust WASM core** (`core/`) as the web app. The shared `.wasm` is hosted inside the app via [WAMR](https://github.com/bytecodealliance/wasm-micro-runtime) (WebAssembly Micro Runtime), not in a WebView.

## Architecture

```
core/  ──→  amaze_core.wasm  ─┬─→  web/   (instantiated in browser)
                              └─→  android/app/src/main/assets/core.wasm
                                            │
                                  WAMR + JNI bridge (cpp/)
                                            │
                                  CoreBridge.kt
                                            │
                                  GameRuntime + Compose UI + PuppetServer
```

The same `core/src/lib.rs` exports the C ABI both consumers call. ABI version is handshaken on init.

## What's in the scaffold

- `app/src/main/kotlin/com/lavazombie/amazegame/`
  - `MainActivity.kt` — Compose host + lifecycle of the runtime and puppet server.
  - `GameRuntime.kt` — façade owning the WASM game instance, exposes a `StateFlow<GameState>`.
  - `CoreBridge.kt` — typed Kotlin wrapper over the JNI bridge.
  - `ui/MazeCanvas.kt` — Compose Canvas renderer for the math-textbook skin.
  - `PuppetServer.kt` — embedded Ktor HTTP server on `127.0.0.1:8088`.
- `app/src/main/cpp/`
  - `CMakeLists.txt` — fetches WAMR at configure time and builds it into `libamaze_native.so`.
  - `core_bridge.cc` — JNI entrypoints, WAMR module load/init, ABI handshake. Function-by-function exports are filled in alongside the renderer in a follow-up.
- `app/src/main/assets/core.wasm` — staged by the Gradle `stageCoreWasm` task before each build, identical bytes to `web/public/core.wasm`.

## Puppet HTTP API

`PuppetServer` binds `127.0.0.1:8088`. Forward it onto the dev machine:

```bash
adb forward tcp:8088 tcp:8088
curl -s http://localhost:8088/health
# { "ok": true, "abiVersion": 1, "platform": "android" }

curl -s http://localhost:8088/state
# Maze size, start/goal, current player position, visited count, hash.

curl -s -X POST http://localhost:8088/control/swipe \
     -H content-type:application/json \
     -d '{"direction":"E"}'

curl -s -X POST http://localhost:8088/control/reset \
     -H content-type:application/json \
     -d '{"size":14}'
```

Future: `POST /scenarios` to play back a sequence of inputs with assertions.

## Building

The build is from-scratch idempotent — Gradle, the NDK + CMake, WAMR (via `FetchContent`), and the Rust `wasm32-unknown-unknown` core all wire together.

Prereqs on the workstation:

- **Android SDK** (cmdline-tools) with `compileSdk 35`, `build-tools 35.0.x`.
- **Android NDK** r26d+ (`ndkVersion` is left default; install via `sdkmanager "ndk;26.3.11579264"`).
- **CMake 3.22.1+** via `sdkmanager "cmake;3.22.1"`.
- **Rust** with `rustup target add wasm32-unknown-unknown`.
- **Node.js** (the Gradle task delegates the WASM build to `scripts/build-core.mjs`).

From `android/`:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.lavazombie.amazegame/.MainActivity
```

## Status

Scaffold: builds end-to-end on a machine with the prereqs, the runtime initialises and the ABI handshake passes. Filling in the per-function JNI exports + tying the renderer to the live WASM state is the next iteration.

See the root [`docs/architecture.md`](../docs/architecture.md) for the full system view and [`docs/android-debug.md`](../docs/android-debug.md) for the adb workflow we already have for the web app — it applies verbatim here.

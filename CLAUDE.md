# CLAUDE.md

Read this before changing anything in this repo. The detailed source of truth lives in `docs/` — this file is a fast pointer + the conventions specific to working with Claude in this project.

## Project at a glance

aMazeGame is a mobile-first browser maze game, deployed to GitHub Pages. Player is a dot; corridors are 1 cell wide; the dot moves autonomously until a decision cell; the player swipes to set the next direction. Score = number of mazes completed. Maze size 6–12, randomly generated, never repeats per device.

The current aesthetic is **school math textbook + pen-drawn walls**. A later iteration will skin the game in a Geometry Dash style — leave the renderer swappable for that.

## Read first

- [`docs/requirements.md`](docs/requirements.md) — scope and non-goals
- [`docs/architecture.md`](docs/architecture.md) — module layout
- [`docs/android-debug.md`](docs/android-debug.md) — adb + chrome://inspect workflow
- [`docs/deployment.md`](docs/deployment.md) — GitHub Pages CI/CD

If a behavior you're being asked to change is documented in `docs/`, update the doc in the same change.

## Stack

- **Vanilla TypeScript** (no React, no framework) + **Vite**.
- **HTML5 Canvas** rendering — kept in a single module (`src/renderer.ts`) so a WASM rendering layer can replace it cleanly later.
- **sql.js** (SQLite-WASM) for player + completed-maze tables, persisted to **IndexedDB** in `src/storage.ts`.
- **GitHub Actions** → GitHub Pages.

## Run

```bash
npm install
npm run dev        # Vite on 0.0.0.0:5173 — phone can hit it on the LAN
npm run typecheck  # tsc --noEmit
npm run build      # production build into dist/
npm run preview    # serve dist/ locally
```

## Conventions

- **Mobile first.** Test layout in a narrow viewport and on a real Android device (see `docs/android-debug.md`) before declaring a UI change done.
- **No comments on the obvious.** Only document the *why* — invariants, surprises, hidden constraints.
- **One-cell corridors are an invariant.** Don't widen them, don't add rooms, don't add multi-cell hazards. If you need a richer space, talk to the user first.
- **Never break "no maze repeats per device."** Anything that adds a maze must go through `generateUniqueMaze` and end up in `completed_mazes` (or `markGenerated` for in-session state). If you add maze parameters (e.g. theme), include them in `hashMaze` so the dedup key reflects the new state space.
- **Renderer is swappable.** Keep maze/movement/storage free of canvas-specific types.
- **Don't add dependencies casually.** Current direct deps: `sql.js` (runtime) + `typescript`, `vite` (dev). Bundle size matters on mobile.
- **Performance baseline.** 60fps on a mid-range Android phone with a 12×12 maze. If you touch the render loop or the wall generator, sanity-check it.

## Doing tasks safely

- Use `gh` for any GitHub action (issues, PRs, Pages config, secrets).
- Don't push to `main` without the user asking. The CI deploys `main` to production GitHub Pages.
- Don't bypass CI (no `--no-verify`, no skipping the build).

## Memory of the user

The user is a Geometry Dash fan; the planned Phase-2 aesthetic is Geometry Dash style. Don't suggest aesthetic directions that conflict with that long-term plan when the user is talking about polish.

## Logging lessons learned (durable)

Whenever you (or the user) hit a non-obvious bug, gotcha, or workflow that would have saved the previous session 20+ minutes, **write it down** in the doc closest to the topic. Future-Claude lands cold and has to re-learn anything that isn't written.

Where to put what:
- Platform/debug workflow gotchas → `docs/android-debug.md`
- Architecture, module-shape, or rendering patterns → `docs/architecture.md`
- PRD-level facts → `docs/requirements.md`
- Cross-cutting meta-rules + a one-line index of the lessons themselves → here, below.
- Persistent user/project context across sessions → the memory system Claude Code maintains at `~/.claude/projects/<slug>/memory/` (the slug is derived from the absolute path of this checkout).

### Lesson log (one-liners, link out for detail)

- **Android APK build broke on NDK after a runner image rotation.** AGP 8.7 defaults `ndkVersion` to 27.x and tries to auto-install it (fails on the GitHub runner); the workflow installs r26d via `setup-ndk` and exports `ANDROID_NDK_HOME`, but **AGP ignores that env var** for `externalNativeBuild`. The old build only passed because the runner happened to preinstall NDK 27. Fix in `android/app/build.gradle.kts`: `ndkVersion = "26.3.11579264"` (r26d) **and** `System.getenv("ANDROID_NDK_HOME")?.let { ndkPath = it }`. Both must agree or AGP errors `CXX1100`. When the APK build fails before compiling Kotlin, suspect NDK resolution first.
- **Web features ≠ native features.** The web app (`web/src`, TS) and the native Android app (`android/`, Kotlin/Compose) are separate frontends over the same Rust core. A feature added to one must be ported to the other by hand — the renderer/HUD/input are not shared. Today's timer + 50×50-daily + camera (zoom/pan + culling) + info page were ported across both; keep them in sync.

- **Blank maze when testing the phone over the LAN dev server (HTTP by IP).** `crypto.subtle` is `undefined` outside a secure context (HTTPS or `localhost`), so `hashMaze`'s SHA-256 threw and *every* maze failed to generate → blank page. Production (GitHub Pages, HTTPS) is fine; only `http://<lan-ip>:5173` breaks. Fix: pure-JS SHA-256 fallback in `web/src/sha256.ts`, used by `hashMaze` when `crypto.subtle` is missing — byte-identical digest, so dedup hashes are unchanged. `crypto.getRandomValues` (rng.ts) is *not* secure-context-gated, so it was fine. When something works at `localhost` but blanks on the phone-by-IP, suspect a secure-context-only API first.

- **Canvas runaway-growth on the phone** — `#maze` had no CSS width; `fitMetrics` writing the backing-store size leaked back into CSS layout and OOM'd the tab after a few frames. Fix: pin the canvas to `position:absolute; inset:0; width:100%; height:100%`. Reach for this pattern any time something works on desktop Chrome but breaks on the phone. See `docs/android-debug.md` §6.
- **Phone Chrome DevTools socket isn't bound until there's a foreground tab.** `curl http://localhost:9222/json/version` returns nothing if no tab is open. Open any tab first. See `docs/android-debug.md` §5.
- **sql.js dev import** — Vite dev served the pre-built browser entry which has no default ESM export. Fix: `optimizeDeps.include: ["sql.js"]` (not `exclude`). Commit `ced3fee`.
- **USB phone-not-detected diagnosis** — when `usbipd list` shows nothing new on plug-in (or shows "Port Reset Failed"), Windows can't see the data lines. Cable is the #1 cause; second is dirty USB port on the phone. Use the polling loop in `docs/android-debug.md` §6 to bisect cable vs port without trial-and-error.
- **Default debug technique on the phone** = `scripts/inspect-phone.mjs` (connect over CDP, capture all console + pageerror + request failures, dump runtime state in one shot). Use it first when behavior differs across platforms.
- **WAMR + FetchContent + Android: variable ordering.** WAMR's root `CMakeLists.txt` defaults `WAMR_BUILD_PLATFORM` to `CMAKE_HOST_SYSTEM_NAME` when *not* defined. With `FetchContent_MakeAvailable(wamr)`, WAMR's CMakeLists runs *as a subproject*, so anything you set after FetchContent is too late — WAMR has already locked in the host (Linux) platform. **Always** set `WAMR_BUILD_PLATFORM`, `WAMR_BUILD_TARGET`, and the build-feature flags via `CACHE … FORCE` *before* `FetchContent_MakeAvailable`. See `android/app/src/main/cpp/CMakeLists.txt`.
- **WAMR symbol visibility on Android.** WAMR forces `-fvisibility=hidden` on its sources and only marks public API with `__declspec(dllexport)` on Windows. On Android the `iwasm_shared` SO exports nothing — linking against it gives "undefined symbol: wasm_runtime_*" at our SO's link step. Link against **`iwasm_static`** instead, and disable `WAMR_BUILD_SHARED` to skip the broken target.
- **REDMAGIC 10 Pro (and likely other gaming phones) returns empty `adb logcat`.** Default ship state has `settings get global vendor_log_status` = `0`, which throttles logd's read side. Fix from CLI: `adb shell settings put global vendor_log_status 1`, then **kill the offending app and relaunch** — the existing process keeps inheriting the old gate. After that, logcat returns the same data as on a stock phone. No reboot needed, no UI toggle.
- **When logcat is muted, the next debug channels are `dumpsys dropbox --print` and `/data/tombstones/`.** Dropbox returns the full Java stack trace of every app crash (`data_app_crash`) and a header for every native crash (`data_app_native_crash`). The corresponding tombstone in `/data/tombstones/tombstone_NN` has the full backtrace with frame offsets into our .so. Use this as the recovery path, not as a last resort — on phones with vendor-throttled logd, it's the only thing that works.
- **WAMR + Android HW bounds check.** WAMR defaults to `WASM_DISABLE_HW_BOUND_CHECK=0`, which installs a SIGSEGV handler to recover from WASM out-of-bounds memory reads. On Android, debuggerd installs its own SIGSEGV handler for tombstone capture and gets the signal first, so WAMR's recovery never runs and the runtime SEGVs during init while probing memory. Fix: `set(WAMR_DISABLE_HW_BOUND_CHECK 1)` + `set(WAMR_DISABLE_STACK_HW_BOUND_CHECK 1)` in CMake. The software-bounds-check path costs a few percent of perf, no signal handler conflict.
- **WAMR + Rust WASM "zero byte expected"** at module-load time = the WASM uses opcodes WAMR isn't built for. Rust 1.82+ for `wasm32-unknown-unknown` emits reference-types and tail-call by default. Enable them in WAMR: `set(WAMR_BUILD_REF_TYPES 1)` + `set(WAMR_BUILD_TAIL_CALL 1)`. Cheaper than telling rustc to suppress them, and keeps the WASM portable.
- **Android INTERNET permission is required to bind even `127.0.0.1`.** Without `<uses-permission android:name="android.permission.INTERNET" />` in the manifest, *any* socket creation throws `java.net.SocketException: Operation not permitted` at startup. Ktor's localhost-only puppet server is not an exception. Cleartext is fine for loopback, no `usesCleartextTraffic` needed.
- **kotlinx-serialization needs the compiler plugin, not just the runtime libs.** Adding `io.ktor:ktor-serialization-kotlinx-json` to dependencies is not enough — `@Serializable` data classes have no generated serializer at runtime, and Ktor's `ContentNegotiation` returns 500 with an empty body and *no logcat trace* (Ktor swallows handler exceptions by default). Fix: add `id("org.jetbrains.kotlin.plugin.serialization") version "<kotlin-version>" apply false` to the root plugins block AND `id("org.jetbrains.kotlin.plugin.serialization")` to the app's plugins block. When debugging "Ktor returns 500 on all routes", suspect the missing plugin first.

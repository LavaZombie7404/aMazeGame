# Requirements

This document is the source of truth for what aMazeGame should do. Update it whenever scope changes.

## 1. Platform

- **Mobile-first** web app. Primary target: Android Chrome (touch input, portrait orientation). Desktop is supported as a development fallback.
- Static site, hosted on **GitHub Pages**.

## 2. Tech stack (current)

- **Vanilla TypeScript** (no UI framework).
- **HTML5 Canvas** for rendering.
- **sql.js** (SQLite compiled to WebAssembly) for player name + statistics, persisted to **IndexedDB** so it survives reloads.
- **Vite** as the dev server and bundler.
- **GitHub Actions** for CI/CD deployment to GitHub Pages.

## 3. Tech stack (planned, not yet built)

- A **WASM game engine layer** (TBD: Rust + wasm-bindgen, or a small custom engine) to host advanced rendering once we move toward Geometry Dash-style aesthetics. The current Canvas renderer is the placeholder.

## 4. Aesthetic

### Phase 1 — school math textbook (current)
- Cream paper background (`#f7f3e8`) with faint blue ruled lines and a red left margin.
- Maze walls drawn as **hand-drawn pen strokes**: line-width variance, perpendicular wobble, endpoint overshoot, occasional ink pooling.
- Player character: a **red ink dot**.
- Goal: a blue ringed target.

### Phase 2 — Geometry Dash style (future)
- Neon palette, glow shaders, beat-synced pulsing. Will live in the WASM rendering layer.

## 5. Gameplay

- The player is a **single dot** at the maze's start cell.
- The corridor is **exactly one cell wide** (perfect maze; no rooms).
- Maze size is randomly chosen in `[6×6, 12×12]` each round.
- The maze is randomly generated **at runtime** (recursive backtracker).
- A maze never repeats on the same device. We track a SHA-256 hash of the maze structure in SQLite and reject collisions when generating.

### Movement

- The dot moves **autonomously** in its current direction.
- It keeps going through straight corridors without input.
- It stops at **decision cells**: junctions (≥ 2 exits ignoring the back direction), dead-ends, the goal, or any turn the player did not pre-queue.
- The player **swipes** up/down/left/right to set the next direction.
  - A swipe that crosses an axis emits a new directional intent — without lifting the finger ("up, down, up, left" all count).
  - The latest swipe is queued and applied at the next decision cell, or immediately if the player is idle.
- Reaching the goal counts as **completion**; a new maze is generated immediately.

### Scoring

- Score = **number of completed mazes**. That's it for now.
- Player name is captured once on first run, stored in SQLite.

## 6. Developer experience

- `npm run dev` starts Vite bound to `0.0.0.0` so a phone on the same Wi-Fi can hit it.
- `npm run build` outputs to `dist/`. `npm run preview` serves that locally.
- Android debugging uses **adb + chrome://inspect** — see [`android-debug.md`](android-debug.md).

## 7. Deployment

- A push to `main` triggers a GitHub Actions workflow that builds the site and publishes `dist/` to GitHub Pages. See [`deployment.md`](deployment.md).

## 8. Non-goals (for now)

- Multiplayer / leaderboards.
- Sound / music.
- Levels, hazards, power-ups.
- Server-side persistence.

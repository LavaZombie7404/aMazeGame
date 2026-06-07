# Requirements

This document is the source of truth for what aMazeGame should do. Update it whenever scope changes.

Status tags used below: **[done]** shipped to `main`; **[planned]** agreed, not built; **[future]** intent only, may evolve.

## 1. Platform

- **Mobile-first** web app. Primary target: Android Chrome (touch input, portrait orientation). Desktop is supported as a development fallback. **[done]**
- Static site, hosted on **GitHub Pages**. **[done]**

## 2. Tech stack (current)

- **Vanilla TypeScript** (no UI framework). **[done]**
- **HTML5 Canvas** for rendering. **[done]**
- **sql.js** (SQLite compiled to WebAssembly) for player + statistics, persisted to **IndexedDB**. **[done]**
- **Vite** as the dev server and bundler. **[done]**
- **GitHub Actions** for CI/CD deployment to GitHub Pages. **[done]**

## 3. Tech stack (planned, not yet built)

- A **WASM game engine layer** (TBD: Rust + wasm-bindgen, or a small custom engine) to host advanced rendering once the skins library outgrows what's comfortable in plain Canvas. **[future]**

## 4. Skins system

The visual look of the game is a **skin**. The "school math textbook" look is one skin; it must not be hard-coded into the renderer.

### 4.1 What a skin defines

A skin is a single declarative object that fully specifies the look. Fields it must cover:

| Field             | Purpose                                                                                  |
|-------------------|------------------------------------------------------------------------------------------|
| `id`, `name`      | Stable identifier and human label.                                                       |
| `palette`         | Named colors (ink, paper/background, accent, character, start, goal, trail, …).          |
| `background`      | How to paint the canvas behind the maze (solid, ruled paper, gradient, …).               |
| `wallStyle`       | How to draw a wall segment (e.g. `pen-stroke`, `clean-line`, `neon`).                    |
| `characterShape`  | Reference into the shape library (see §5) + size and styling overrides.                  |
| `startShape`      | Reference into the shape library for the start marker.                                   |
| `goalShape`       | Reference into the shape library for the goal marker.                                    |
| `trail`           | Trail styling (see §6.2). Color, width, style (`dotted`, `solid`, `inked`), fade rules.  |
| `font`            | Font stack for the HUD when using this skin.                                             |

Skins are pure data + a tiny set of pluggable drawing functions; they don't keep game state.

### 4.2 Built-in skins

| Skin id           | Status      | Notes                                                                                     |
|-------------------|-------------|-------------------------------------------------------------------------------------------|
| `math-textbook`   | **[done]**    | Cream paper, ruled lines, red margin, hand-drawn pen walls, red-ink dot character, blue ringed goal. Lives in `src/skins/math-textbook.ts`. |
| `geometry-dash`   | **[future]**  | Neon palette, glow, pulsing beat, vibrant gradients. Will likely require the WASM layer.   |
| (others)          | **[future]**  | Open list — anything that satisfies §4.1 qualifies.                                        |

### 4.3 Skin selection

- The current skin is part of player preferences and persisted in SQLite (`player.skin_id` column, migration v2). **[done]**
- The player can switch skins from a settings menu (⚙ button in the HUD) without losing progress; the next render frame uses the new skin. The skin row is auto-hidden when only one skin is registered, so the dialog stays clean. **[done]**
- A future "shop" / "unlocks" model is out of scope for now. **[future]**

## 5. Shape & marker library

The shapes used for the character, the start marker, and the goal marker are not hard-coded — they're picked from a **shape library**.

### 5.1 What a shape is

A shape is a tiny module that exposes:
- A canonical name (`"dot"`, `"square"`, `"star-5"`, `"diamond"`, `"flag"`, …).
- A draw function: `(ctx, x, y, size, styling) => void`.
- Optional metadata (recommended size range, whether it has a "fill" vs "stroke" variant, etc.).

A shape doesn't know what it's representing — the same shape can be used for the character, start, or goal.

### 5.2 What ships in the library (initial set)

All in `src/shapes/primitives.ts`, registered in `src/shapes/index.ts`:

- `dot` (filled circle) — current character. **[done]**
- `ring` (stroked circle, optional inner fill) — current goal. **[done]**
- `square`, `diamond`, `triangle`, `star-5`, `flag`, `arrow`. **[done]**

The library is **extensible at build time** for now: drop a new shape file into `src/shapes/`, register it, and any skin can reference it by name. A runtime / user-provided shape system is **[future]**.

### 5.3 Configurability

- Every skin specifies which shape is used for character / start / goal. **[done]**
- A skin can override a shape's color and size without redefining the shape itself (via `ShapeRef.style` and `sizeFactor`). **[done]**
- The player can change the character / start / goal shape *without changing the skin* (e.g. for accessibility) via the same settings dialog as skin selection. Overrides persist in SQLite (`player.character_shape`, `start_shape`, `goal_shape`); a slot left as "skin default" stores `NULL`. **[done]**

## 6. Gameplay

- The player character is at the maze's start cell. **[done]**
- The corridor is **exactly one cell wide** (perfect maze; no rooms). **[done]**
- The maze is randomly generated **at runtime** (recursive backtracker). **[done]**
- A maze never repeats on the same device. We track a SHA-256 hash of the maze structure in SQLite and reject collisions when generating. **[done]**

### 6.0 Maze size & difficulty cadence

To keep the game from grinding the player down with a streak of huge mazes, sizes alternate between a **simple** and a **complex** bucket from one round to the next. **[done]**

- **Simple bucket:** size randomly chosen in `[12×12, 18×18]`. **[done]**
- **Complex bucket:** size randomly chosen in `[24×24, 30×30]`. **[done]**
- Sizes `19×19` through `23×23` are intentionally unused — the gap is what makes the cadence feel like a breather, not just "slightly easier". **[done]**
- **Cadence:** the first round of a new player is **simple**; every subsequent round flips the bucket. The parity is derived from `player.mazes_completed` so it survives reloads (`even → simple, odd → complex`). **[done]**
- The bucket is recorded on every row of `completed_mazes` (`bucket` column, migration v2) so we can analyse difficulty curves later. **[done]**

A round's bucket overrides any single-round "random size" intuition — the randomness is *within* the bucket, not across both.

### 6.1 Movement

- The character moves **autonomously** in its current direction. **[done]**
- It keeps going through straight corridors without input. **[done]**
- It stops at **decision cells**: junctions (≥ 2 exits ignoring the back direction), dead-ends, the goal, or any turn the player did not pre-queue. **[done]**
- Player inputs accepted (all set the next direction):
  - **Touch swipe** up / down / left / right. A swipe that crosses an axis emits a new directional intent without the finger being lifted ("up, down, up, left" all count). **[done]**
  - **Arrow keys** (↑ ↓ ← →) on a connected keyboard. **[done]**
  - **WASD** keys on a connected keyboard. **[done]**
- The latest input is queued and applied at the next decision cell, or immediately if the character is idle. **[done]**
- Reaching the goal counts as **completion**; a new maze is generated immediately. **[done]**

### 6.2 Trail

The character leaves a **trail** behind it showing the path it has walked through the current maze.

- The trail is rendered along the cells the character has actually traversed (not the optimal path). Implementation: the renderer walks the maze and draws `skin.trail` strokes between pairs of adjacent visited cells that share an open passage. **[done]**
- Backtracking through a cell does **not** add a second layer of trail — `MovementState.visited` is a `Set<cellIndex>`, so re-entering a cell is a no-op. **[done]**
- The trail resets when a new maze begins (`createMovementState` allocates a fresh `visited` set). It does **not** persist across mazes. **[done]**
- The exact visual (color, width, dotted vs solid, glow, etc.) is **owned by the active skin** (§4.1, `trail` field). The math-textbook skin uses a dotted soft-blue stroke at 55% alpha. **[done]**
- The trail is purely decorative — it doesn't affect movement, collision, or scoring. **[done]**

## 7. Scoring

- Score = **number of completed mazes**. **[done]**
- Player name is captured once on first run, stored in SQLite. **[done]**

## 8. Developer experience

- `npm run dev` starts Vite bound to `0.0.0.0` so a phone on the same Wi-Fi can hit it. **[done]**
- `npm run build` outputs to `dist/`. `npm run preview` serves that locally. **[done]**
- Android debugging uses **adb + chrome://inspect** — see [`android-debug.md`](android-debug.md). **[done]**

## 9. Deployment

- A push to `main` triggers a GitHub Actions workflow that builds the site and publishes `dist/` to GitHub Pages. See [`deployment.md`](deployment.md). **[done]**

## 10. Non-goals (for now)

- Multiplayer / leaderboards.
- Sound / music.
- Levels, hazards, power-ups.
- Server-side persistence.
- User-uploaded skins or shapes (file-format / sandboxing complexity).

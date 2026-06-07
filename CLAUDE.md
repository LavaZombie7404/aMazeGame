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

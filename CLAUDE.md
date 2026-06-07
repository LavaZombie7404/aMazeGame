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
- Persistent user/project context across sessions → the memory system in `~/.claude/projects/-home-lavazombie-aMazeGame/memory/`.

### Lesson log (one-liners, link out for detail)

- **Canvas runaway-growth on the phone** — `#maze` had no CSS width; `fitMetrics` writing the backing-store size leaked back into CSS layout and OOM'd the tab after a few frames. Fix: pin the canvas to `position:absolute; inset:0; width:100%; height:100%`. Reach for this pattern any time something works on desktop Chrome but breaks on the phone. See `docs/android-debug.md` §6.
- **Phone Chrome DevTools socket isn't bound until there's a foreground tab.** `curl http://localhost:9222/json/version` returns nothing if no tab is open. Open any tab first. See `docs/android-debug.md` §5.
- **sql.js dev import** — Vite dev served the pre-built browser entry which has no default ESM export. Fix: `optimizeDeps.include: ["sql.js"]` (not `exclude`). Commit `ced3fee`.
- **USB phone-not-detected diagnosis** — when `usbipd list` shows nothing new on plug-in (or shows "Port Reset Failed"), Windows can't see the data lines. Cable is the #1 cause; second is dirty USB port on the phone. Use the polling loop in `docs/android-debug.md` §6 to bisect cable vs port without trial-and-error.
- **Default debug technique on the phone** = `scripts/inspect-phone.mjs` (connect over CDP, capture all console + pageerror + request failures, dump runtime state in one shot). Use it first when behavior differs across platforms.

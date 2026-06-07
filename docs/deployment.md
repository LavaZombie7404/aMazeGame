# Deployment

aMazeGame is a static site. Every push to `main` builds and publishes to GitHub Pages.

## Workflow

`.github/workflows/deploy.yml`:

1. Checks out the repo.
2. Sets up Node 20 + caches `~/.npm`.
3. Runs `npm ci`.
4. Runs `npm run build` with `VITE_BASE=/<repo-name>/` so Vite emits the right asset paths for `https://<user>.github.io/<repo-name>/`.
5. Uploads `dist/` as a Pages artifact via `actions/upload-pages-artifact`.
6. A second `deploy` job promotes the artifact via `actions/deploy-pages`.

## One-time setup after the repo exists

Enable Pages with GitHub Actions as the source. With the `gh` CLI:

```bash
gh api -X POST "repos/$OWNER/$REPO/pages" \
  -f build_type=workflow \
  -f source[branch]=main \
  -f source[path]=/ || true
# Or, simpler:
gh api -X POST "repos/$OWNER/$REPO/pages" -f build_type=workflow
```

If a `gh-pages` branch already exists from a previous deploy method, you may need to switch the Pages source: *Settings → Pages → Source → GitHub Actions*.

## Local preview of the production build

```bash
VITE_BASE=/ npm run build
npm run preview
```

`preview` defaults to `http://localhost:4173`.

## Custom domain

Drop a `public/CNAME` containing the domain. Vite copies `public/*` verbatim into `dist/`, so it survives the build. Then set the domain in *Settings → Pages → Custom domain*.

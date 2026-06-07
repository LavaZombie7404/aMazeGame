import { defineConfig } from "vite";
import { fileURLToPath } from "node:url";

// Repo name is injected by CI; for local dev the base is "/".
const base = process.env.VITE_BASE ?? "/";

export default defineConfig({
  // Web sources live under web/. Build output stays at root-level dist/ so the
  // existing CI workflow and GitHub Pages config keep working unchanged.
  root: fileURLToPath(new URL("./web", import.meta.url)),
  base,
  build: {
    outDir: fileURLToPath(new URL("./dist", import.meta.url)),
    emptyOutDir: true,
    target: "es2022",
    sourcemap: true,
  },
  optimizeDeps: {
    include: ["sql.js"],
  },
  server: {
    port: 5173,
  },
});

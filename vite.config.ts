import { defineConfig } from "vite";

// Repo name is injected by CI; for local dev the base is "/".
const base = process.env.VITE_BASE ?? "/";

export default defineConfig({
  base,
  build: {
    target: "es2022",
    sourcemap: true,
  },
  optimizeDeps: {
    // Pre-bundle sql.js so its CommonJS-style export comes out with a real
    // ESM default in dev mode. Without this, Vite serves the raw browser
    // entry which does not provide a `default` export and main.ts crashes
    // on import.
    include: ["sql.js"],
  },
  server: {
    port: 5173,
  },
});

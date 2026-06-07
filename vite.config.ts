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
    exclude: ["sql.js"],
  },
  server: {
    port: 5173,
  },
});

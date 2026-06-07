// Build the Rust core to WASM and stage it in web/public/ so Vite picks it up
// at /core.wasm. Run as part of `npm run build` and `npm run dev`.
//
// Why a Node script instead of a Vite plugin: keeping it standalone means the
// Android build can call the same Rust crate without going through Vite.
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const OUT_DIR = resolve(ROOT, "web", "public");
const OUT = resolve(OUT_DIR, "core.wasm");
const BUILT = resolve(
  ROOT,
  "target/wasm32-unknown-unknown/release/amaze_core.wasm",
);

function sh(cmd, args) {
  const r = spawnSync(cmd, args, {
    cwd: ROOT,
    stdio: "inherit",
    env: { ...process.env, PATH: `${process.env.HOME}/.cargo/bin:${process.env.PATH}` },
  });
  if (r.status !== 0) {
    process.exit(r.status ?? 1);
  }
}

sh("cargo", [
  "build",
  "--release",
  "--target",
  "wasm32-unknown-unknown",
  "-p",
  "amaze-core",
]);

if (!existsSync(BUILT)) {
  console.error("expected", BUILT, "after cargo build, not found");
  process.exit(1);
}

mkdirSync(OUT_DIR, { recursive: true });
copyFileSync(BUILT, OUT);
console.log(`copied ${BUILT} -> ${OUT}`);

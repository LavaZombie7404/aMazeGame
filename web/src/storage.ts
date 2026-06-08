import initSqlJs, { type Database, type SqlJsStatic } from "sql.js";
import wasmUrl from "sql.js/dist/sql-wasm.wasm?url";

const DB_KEY = "amazegame.sqlite";
const IDB_NAME = "amazegame";
const IDB_STORE = "kv";

export type DifficultyBucket = "simple" | "complex";
export type ShapeSlot = "character" | "start" | "goal";

export interface PlayerRecord {
  name: string;
  mazesCompleted: number;
  skinId: string;
  characterOverride: string | null;
  startOverride: string | null;
  goalOverride: string | null;
  legacyMovement: boolean;
  speedMultiplier: number;
  characterColor: string | null;
  startColor: string | null;
  goalColor: string | null;
}

let SQL: SqlJsStatic | null = null;
let dbInstance: Database | null = null;

async function getSql(): Promise<SqlJsStatic> {
  if (SQL) return SQL;
  SQL = await initSqlJs({ locateFile: () => wasmUrl });
  return SQL!;
}

function idb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(IDB_NAME, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(IDB_STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function readDbBlob(): Promise<Uint8Array | null> {
  const db = await idb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(IDB_STORE, "readonly");
    const req = tx.objectStore(IDB_STORE).get(DB_KEY);
    req.onsuccess = () =>
      resolve((req.result as Uint8Array | undefined) ?? null);
    req.onerror = () => reject(req.error);
  });
}

async function writeDbBlob(bytes: Uint8Array): Promise<void> {
  const db = await idb();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(IDB_STORE, "readwrite");
    tx.objectStore(IDB_STORE).put(bytes, DB_KEY);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

/**
 * Idempotent forward-only migrations keyed off SQLite's PRAGMA user_version.
 * Add new entries to the end — never reorder or remove.
 */
const migrations: Array<(db: Database) => void> = [
  // v1 — initial schema.
  (db) => {
    db.run(`
      CREATE TABLE IF NOT EXISTS player (
        id INTEGER PRIMARY KEY CHECK (id = 1),
        name TEXT NOT NULL,
        mazes_completed INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      );
      CREATE TABLE IF NOT EXISTS completed_mazes (
        hash TEXT PRIMARY KEY,
        size INTEGER NOT NULL,
        seed INTEGER NOT NULL,
        completed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      );
    `);
  },
  // v2 — skin selection + shape overrides + difficulty bucket on completions.
  (db) => {
    db.run(
      `ALTER TABLE player ADD COLUMN skin_id TEXT NOT NULL DEFAULT 'math-textbook'`,
    );
    db.run(`ALTER TABLE player ADD COLUMN character_shape TEXT`);
    db.run(`ALTER TABLE player ADD COLUMN start_shape TEXT`);
    db.run(`ALTER TABLE player ADD COLUMN goal_shape TEXT`);
    db.run(
      `ALTER TABLE completed_mazes ADD COLUMN bucket TEXT NOT NULL DEFAULT 'simple'`,
    );
  },
  // v3 — opt-in "legacy" movement (stop at every corner, not just real forks).
  (db) => {
    db.run(
      `ALTER TABLE player ADD COLUMN legacy_movement INTEGER NOT NULL DEFAULT 0`,
    );
  },
  // v4 — per-player speed multiplier (0.5x .. 3x, default 1x).
  (db) => {
    db.run(
      `ALTER TABLE player ADD COLUMN speed_multiplier REAL NOT NULL DEFAULT 1.0`,
    );
  },
  // v5 — per-slot color overrides; null = inherit from skin.
  (db) => {
    db.run(`ALTER TABLE player ADD COLUMN character_color TEXT`);
    db.run(`ALTER TABLE player ADD COLUMN start_color TEXT`);
    db.run(`ALTER TABLE player ADD COLUMN goal_color TEXT`);
  },
];

function migrate(db: Database): void {
  const res = db.exec("PRAGMA user_version");
  const current = Number(res[0]?.values[0]?.[0] ?? 0);
  for (let v = current; v < migrations.length; v++) {
    migrations[v]!(db);
  }
  db.run(`PRAGMA user_version = ${migrations.length}`);
}

async function getDb(): Promise<Database> {
  if (dbInstance) return dbInstance;
  const sql = await getSql();
  const existing = await readDbBlob();
  dbInstance = existing ? new sql.Database(existing) : new sql.Database();
  migrate(dbInstance);
  return dbInstance;
}

async function persist(): Promise<void> {
  if (!dbInstance) return;
  await writeDbBlob(dbInstance.export());
}

export async function getPlayer(): Promise<PlayerRecord | null> {
  const db = await getDb();
  const res = db.exec(
    `SELECT name, mazes_completed, skin_id,
            character_shape, start_shape, goal_shape,
            legacy_movement, speed_multiplier,
            character_color, start_color, goal_color
       FROM player WHERE id = 1`,
  );
  if (res.length === 0 || res[0]!.values.length === 0) return null;
  const row = res[0]!.values[0]!;
  return {
    name: String(row[0]),
    mazesCompleted: Number(row[1]),
    skinId: String(row[2]),
    characterOverride: row[3] === null ? null : String(row[3]),
    startOverride: row[4] === null ? null : String(row[4]),
    goalOverride: row[5] === null ? null : String(row[5]),
    legacyMovement: Number(row[6]) !== 0,
    speedMultiplier: Number(row[7]),
    characterColor: row[8] === null ? null : String(row[8]),
    startColor: row[9] === null ? null : String(row[9]),
    goalColor: row[10] === null ? null : String(row[10]),
  };
}

export async function setPlayerName(name: string): Promise<void> {
  const db = await getDb();
  db.run(
    `INSERT INTO player (id, name) VALUES (1, ?)
     ON CONFLICT(id) DO UPDATE SET name = excluded.name`,
    [name],
  );
  await persist();
}

export async function setSkinId(skinId: string): Promise<void> {
  const db = await getDb();
  db.run(`UPDATE player SET skin_id = ? WHERE id = 1`, [skinId]);
  await persist();
}

export async function setLegacyMovement(value: boolean): Promise<void> {
  const db = await getDb();
  db.run(`UPDATE player SET legacy_movement = ? WHERE id = 1`, [value ? 1 : 0]);
  await persist();
}

export async function setSpeedMultiplier(value: number): Promise<void> {
  const db = await getDb();
  db.run(`UPDATE player SET speed_multiplier = ? WHERE id = 1`, [value]);
  await persist();
}

export async function setShapeOverride(
  slot: ShapeSlot,
  shape: string | null,
): Promise<void> {
  const column =
    slot === "character"
      ? "character_shape"
      : slot === "start"
        ? "start_shape"
        : "goal_shape";
  const db = await getDb();
  db.run(`UPDATE player SET ${column} = ? WHERE id = 1`, [shape]);
  await persist();
}

export async function setColorOverride(
  slot: ShapeSlot,
  color: string | null,
): Promise<void> {
  const column =
    slot === "character"
      ? "character_color"
      : slot === "start"
        ? "start_color"
        : "goal_color";
  const db = await getDb();
  db.run(`UPDATE player SET ${column} = ? WHERE id = 1`, [color]);
  await persist();
}

export async function recordCompletion(
  hash: string,
  size: number,
  seed: number,
  bucket: DifficultyBucket,
): Promise<number> {
  const db = await getDb();
  db.run(
    `INSERT OR IGNORE INTO completed_mazes (hash, size, seed, bucket)
     VALUES (?, ?, ?, ?)`,
    [hash, size, seed, bucket],
  );
  db.run(
    `INSERT INTO player (id, name, mazes_completed)
       VALUES (1, COALESCE((SELECT name FROM player WHERE id = 1), 'Player'), 1)
     ON CONFLICT(id) DO UPDATE SET mazes_completed = player.mazes_completed + 1`,
  );
  await persist();
  const res = db.exec("SELECT mazes_completed FROM player WHERE id = 1");
  return Number(res[0]!.values[0]![0]);
}

export async function hasSeenMaze(hash: string): Promise<boolean> {
  const db = await getDb();
  const res = db.exec("SELECT 1 FROM completed_mazes WHERE hash = ? LIMIT 1", [
    hash,
  ]);
  return res.length > 0 && res[0]!.values.length > 0;
}

/** In-memory set of mazes generated this session (not necessarily completed). */
const sessionGenerated = new Set<string>();
export function markGenerated(hash: string): void {
  sessionGenerated.add(hash);
}
export function wasGeneratedThisSession(hash: string): boolean {
  return sessionGenerated.has(hash);
}

import initSqlJs, { type Database, type SqlJsStatic } from "sql.js";
// Vite serves the wasm file via ?url so the worker fetches it from a known path.
import wasmUrl from "sql.js/dist/sql-wasm.wasm?url";

const DB_KEY = "amazegame.sqlite";
const IDB_NAME = "amazegame";
const IDB_STORE = "kv";

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

async function getDb(): Promise<Database> {
  if (dbInstance) return dbInstance;
  const sql = await getSql();
  const existing = await readDbBlob();
  dbInstance = existing ? new sql.Database(existing) : new sql.Database();
  dbInstance.run(`
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
  return dbInstance;
}

async function persist(): Promise<void> {
  if (!dbInstance) return;
  await writeDbBlob(dbInstance.export());
}

export async function getPlayer(): Promise<{
  name: string;
  mazesCompleted: number;
} | null> {
  const db = await getDb();
  const res = db.exec("SELECT name, mazes_completed FROM player WHERE id = 1");
  if (res.length === 0 || res[0]!.values.length === 0) return null;
  const row = res[0]!.values[0]!;
  return { name: String(row[0]), mazesCompleted: Number(row[1]) };
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

export async function recordCompletion(
  hash: string,
  size: number,
  seed: number,
): Promise<number> {
  const db = await getDb();
  db.run(
    "INSERT OR IGNORE INTO completed_mazes (hash, size, seed) VALUES (?, ?, ?)",
    [hash, size, seed],
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

/**
 * Also remember mazes that were *generated* this session even if not completed
 * — prevents the same layout reappearing if the player resets mid-run.
 */
const sessionGenerated = new Set<string>();
export function markGenerated(hash: string): void {
  sessionGenerated.add(hash);
}
export function wasGeneratedThisSession(hash: string): boolean {
  return sessionGenerated.has(hash);
}

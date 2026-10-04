// Small filesystem helpers: atomic writes that survive crashes mid-write (and Windows AV locks).
import { randomBytes } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

function sleepSync(ms: number): void {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

/**
 * Write to a temp file in the same directory, fsync, then rename over the target. The temp file has
 * an unguessable name and is created exclusively (O_CREAT|O_EXCL: an existing file or link there
 * is never opened or followed). Its mode: `mode` (e.g. 0o600 for secrets), else the existing
 * target's (a file the user restricted stays restricted), else the umask default. A failed write
 * leaves no temp file behind.
 */
export function writeFileAtomic(file: string, data: string | Uint8Array, opts: { mode?: number } = {}): void {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  let mode = opts.mode;
  if (mode === undefined) {
    try {
      mode = fs.statSync(file).mode & 0o777;
    } catch {
      /* new file */
    }
  }
  const tmp = `${file}.${randomBytes(12).toString('hex')}.tmp`;
  const fd = fs.openSync(tmp, 'wx', mode ?? 0o666);
  try {
    // exactly that mode, whatever the umask
    if (mode !== undefined && process.platform !== 'win32') fs.fchmodSync(fd, mode);
    fs.writeSync(fd, typeof data === 'string' ? Buffer.from(data, 'utf8') : data);
    fs.fsyncSync(fd);
  } catch (e) {
    try {
      fs.closeSync(fd);
    } catch {
      /* ignore */
    }
    fs.rmSync(tmp, { force: true });
    throw e;
  }
  fs.closeSync(fd);
  // On Windows a rename can fail transiently (EPERM/EBUSY) if a scanner holds the target open.
  for (let attempt = 0; ; attempt++) {
    try {
      fs.renameSync(tmp, file);
      return;
    } catch (e) {
      const code = (e as NodeJS.ErrnoException).code;
      if (attempt < 8 && (code === 'EPERM' || code === 'EBUSY' || code === 'EACCES')) {
        sleepSync(25 * (attempt + 1));
        continue;
      }
      try {
        fs.rmSync(tmp, { force: true });
      } catch {
        /* ignore */
      }
      throw e;
    }
  }
}

export function writeJsonAtomic(file: string, value: unknown): void {
  writeFileAtomic(file, JSON.stringify(value, null, 2) + '\n');
}

export function readJson<T>(file: string): T | undefined {
  if (!fs.existsSync(file)) return undefined;
  const raw = fs.readFileSync(file, 'utf8');
  if (!raw.trim()) return undefined;
  return JSON.parse(raw) as T;
}

export function ensureDir(dir: string): string {
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

/** True if `child` is `parent` or inside it (case-insensitive on Windows). */
export function isInsideOrEqual(child: string, parent: string): boolean {
  const norm = (p: string) => {
    let r = path.resolve(p);
    if (process.platform === 'win32') r = r.toLowerCase();
    // (a filesystem root keeps its separator: "/" stripped to "" would not contain anything)
    return r.length > path.parse(r).root.length ? r.replace(/[\\/]+$/, '') : r;
  };
  const c = norm(child);
  const p = norm(parent);
  if (c === p) return true;
  const rel = path.relative(p, c);
  return rel !== '' && !rel.startsWith('..') && !path.isAbsolute(rel);
}

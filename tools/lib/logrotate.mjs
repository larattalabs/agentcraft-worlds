// Size-based log rotation for long-lived append logs (tools/unix.mjs, tools/foreman-daemon.mjs).
//
// Copy-truncate: the current file is copied to <file>.1 (older copies shift to .2 ... .keep) and then
// truncated in place. A process that still has the file open with O_APPEND (a Foreman started by an
// earlier launch, a Foreman restarted from the hub) keeps writing to the same inode, so nothing is
// lost and nobody has to reopen the file. Lines written between the copy and the truncate are lost;
// that window is a few milliseconds and only happens at launch time.
import fs from 'node:fs';

export const DEFAULT_MAX_BYTES = 5 * 1024 * 1024;
export const DEFAULT_KEEP = 3;

/**
 * Rotate `file` when it is larger than `maxBytes`. Returns true when it rotated. Never throws: a
 * log that cannot be rotated is not a reason to fail a launch.
 */
export function rotateLog(file, { maxBytes = DEFAULT_MAX_BYTES, keep = DEFAULT_KEEP } = {}) {
  try {
    const size = fs.statSync(file).size;
    if (size <= maxBytes) return false;
    for (let i = keep - 1; i >= 1; i--) {
      const from = `${file}.${i}`;
      if (fs.existsSync(from)) fs.renameSync(from, `${file}.${i + 1}`);
    }
    if (keep >= 1) fs.copyFileSync(file, `${file}.1`);
    fs.truncateSync(file, 0);
    return true;
  } catch {
    return false;
  }
}

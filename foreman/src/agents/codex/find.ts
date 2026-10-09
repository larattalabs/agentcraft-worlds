// Where is the Codex CLI? An explicit path (--codex-path / AGENTCRAFT_CODEX_PATH), then `codex` on
// PATH (npm i -g @openai/codex, Homebrew...), then the CLI bundled with the Codex desktop app.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

function onPath(name: string): string | undefined {
  try {
    const out = execFileSync(process.platform === 'win32' ? 'where' : 'which', [name], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], windowsHide: true, timeout: 5000 });
    const hits = out.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
    // Windows: prefer a real executable over npm's extension-less shell script
    return process.platform === 'win32' ? (hits.find((h) => /\.(exe|cmd|bat)$/i.test(h)) ?? hits[0]) : hits[0];
  } catch {
    return undefined;
  }
}

/** The CLI inside the Codex desktop app (Windows: Microsoft Store package; macOS: the app bundle). */
function inDesktopApp(): string | undefined {
  if (process.platform === 'win32') {
    try {
      const loc = execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', '(Get-AppxPackage -Name OpenAI.Codex | Select-Object -First 1).InstallLocation'], {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
        windowsHide: true,
        timeout: 15_000,
      }).trim();
      if (loc) {
        const exe = path.join(loc, 'app', 'resources', 'codex.exe');
        if (fs.existsSync(exe)) return exe;
      }
    } catch {
      /* no app */
    }
    return undefined;
  }
  if (process.platform === 'darwin') {
    for (const p of ['/Applications/Codex.app/Contents/Resources/codex', path.join(process.env.HOME ?? '', 'Applications/Codex.app/Contents/Resources/codex')]) {
      if (fs.existsSync(p)) return p;
    }
  }
  return undefined;
}

let cached: { key: string; bin: string | undefined } | undefined;

export function findCodex(explicit?: string): string | undefined {
  const key = explicit ?? '';
  if (cached?.key === key) return cached.bin;
  const bin = explicit ? (fs.existsSync(explicit) ? explicit : onPath(explicit)) : (onPath('codex') ?? inDesktopApp());
  cached = { key, bin };
  return bin;
}

export const CODEX_NOT_FOUND =
  'Codex CLI not found. Install it (npm i -g @openai/codex) or the Codex desktop app, log in once (`codex login`), or pass --codex-path <path to codex>.';

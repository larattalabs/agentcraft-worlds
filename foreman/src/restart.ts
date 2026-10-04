// foreman.restart: the Foreman starts itself again (same node, flags, script, arguments, environment
// and working directory), detached, then exits. main.ts closes the server and the Foreman first
// (state saved, running turns interrupted; resumeOnStart picks them up), so the new process can take
// the same port and profile.
import { spawn } from 'node:child_process';
import { scrubEnv } from './util/env.js';

export interface RestartCommand {
  command: string;
  args: string[];
  cwd: string;
  env: NodeJS.ProcessEnv;
}

/**
 * Arguments that act once, at the first start, and must not run again on a restart: --reset would
 * wipe the profile, --goal would submit the goal again, --autostart would start the sim scenario
 * again.
 */
const ONE_SHOT = new Set(['reset', 'goal', 'autostart']);

/** `argv` without the one-shot arguments (parsed the way config.ts parseFlags reads them). */
export function restartArgs(argv: string[]): string[] {
  const out: string[] = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!;
    if (a === '--') {
      out.push(...argv.slice(i));
      break;
    }
    if (!a.startsWith('--')) {
      out.push(a);
      continue;
    }
    const eq = a.indexOf('=');
    const name = (eq > 0 ? a.slice(2, eq) : a.slice(2)).replace(/^no-/, '');
    if (!ONE_SHOT.has(name)) {
      out.push(a);
      continue;
    }
    // a separate value belongs to the flag (as parseFlags reads it): drop it too
    const next = argv[i + 1];
    if (eq < 0 && !a.startsWith('--no-') && next !== undefined && !next.startsWith('--')) i++;
  }
  return out;
}

export function restartCommand(p: { execPath: string; execArgv: string[]; script: string; argv: string[]; cwd: string; env: NodeJS.ProcessEnv }): RestartCommand {
  return { command: p.execPath, args: [...p.execArgv, p.script, ...restartArgs(p.argv)], cwd: p.cwd, env: scrubEnv(p.env) };
}

/** This process's restart command (main.ts). */
export function currentRestartCommand(argv: string[]): RestartCommand {
  return restartCommand({ execPath: process.execPath, execArgv: process.execArgv, script: process.argv[1]!, argv, cwd: process.cwd(), env: process.env });
}

/** Start it detached (its own process group; output goes where ours goes). Returns its pid. */
export function spawnRestart(cmd: RestartCommand): number {
  const child = spawn(cmd.command, cmd.args, { cwd: cmd.cwd, env: scrubEnv(cmd.env), detached: true, stdio: ['ignore', 'inherit', 'inherit'], windowsHide: true });
  child.on('error', () => undefined);
  child.unref();
  if (!child.pid) throw new Error(`could not start ${cmd.command}`);
  return child.pid;
}

// The sim's pull request demo repo (--sim-pr / AGENTCRAFT_SIM_PR / config.json sim.prDemo):
// pocket-api, a copy of the demo repo whose approved work lands as pull requests on the sim's fake
// host (prhost.ts). Its "server" is a local bare repository next to it, so landing pushes for real
// (the push lease, added commits for review fixes) without any network: the repo's own git config
// allows git's file transport for it alone (protocol.file.allow; the Foreman's pushes allow only
// https and ssh otherwise).
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { Config, RepoSettings } from '../../config.js';

export const SIM_PR_REPO = 'pocket-api';
/** the goal --autostart submits for the PR repo */
export const SIM_PR_GOAL = 'Document the notes export endpoint';

/** Where the PR demo repo lives for a profile (next to the main demo repo). */
export function simPrRepoDir(cfg: Pick<Config, 'projectRoot' | 'profile'>): string {
  return path.join(cfg.projectRoot, 'sandbox', cfg.profile === 'sim' ? SIM_PR_REPO : `${SIM_PR_REPO}-${cfg.profile}`);
}

/** Its local bare "server". */
export function simPrServerDir(repoDir: string): string {
  return `${repoDir}.server.git`;
}

const gitIn = (cwd: string, ...args: string[]) => execFileSync('git', ['-C', cwd, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();

/**
 * (Re)create the PR demo repo at `dir` with `createDemo` (sandbox/create-demo.mjs) and a fresh bare
 * server it pushes to. The server is recreated too: a branch left from an earlier run would refuse
 * the first push (the push expects the branch to be absent).
 */
export function createSimPrRepo(dir: string, createDemo: (o: { dir: string; force?: boolean; quiet?: boolean }) => unknown): { repo: string; server: string } {
  const server = simPrServerDir(dir);
  // only ever delete our own bare server next to the demo repo
  if (path.basename(server) !== `${path.basename(dir)}.server.git`) throw new Error(`refusing to replace ${server}`);
  createDemo({ dir, force: true, quiet: true });
  fs.rmSync(server, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  execFileSync('git', ['init', '-q', '--bare', '-b', 'main', server], { stdio: 'ignore' });
  gitIn(dir, 'remote', 'add', 'origin', server);
  gitIn(dir, 'config', '--local', 'protocol.file.allow', 'always');
  gitIn(dir, 'push', '-q', 'origin', 'main:refs/heads/main');
  gitIn(dir, 'fetch', '-q', 'origin');
  return { repo: dir, server };
}

/** The demo repo lands as pull requests (its config.json repoSettings, if any, keep their other keys). */
export function simPrRepoSettings(cfg: Pick<Config, 'repoSettings'>, dir: string): RepoSettings {
  const s: RepoSettings = { ...(cfg.repoSettings[dir] ?? {}), land: 'pr' };
  cfg.repoSettings[dir] = s;
  return s;
}

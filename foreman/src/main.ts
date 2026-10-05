// Foreman entry point: `npm run start -- --backend sim|claude [--repo <path>] [--speed N] ...`
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { ClaudeBackend } from './agents/claude/index.js';
import { SimBackend } from './agents/sim/index.js';
import { DEFAULT_SIM_GOAL } from './agents/sim/scenario.js';
import { createSimPrRepo, simPrRepoDir, simPrRepoSettings } from './agents/sim/prdemo.js';
import { FOREMAN_VERSION, HELP, loadConfig, type Config } from './config.js';
import { consoleLogger } from './context.js';
import { Foreman } from './foreman.js';
import { ForemanServer } from './server.js';
import { createClientToken, removeClientToken } from './clienttoken.js';
import { currentRestartCommand, spawnRestart } from './restart.js';
import { claimRunFiles, homeRunFile, liveOwner, profileRunFile, releaseRunFiles, type RunInfo } from './runfile.js';
import { isInsideOrEqual, writeJsonAtomic } from './util/fsx.js';

type CreateDemo = (o: { dir: string; force?: boolean; quiet?: boolean }) => { dir: string; head: string };

async function demoCreator(cfg: Config): Promise<CreateDemo> {
  const mod = (await import(pathToFileURL(path.join(cfg.projectRoot, 'sandbox', 'create-demo.mjs')).href)) as { createDemo: CreateDemo };
  return mod.createDemo;
}

async function createDemoRepo(cfg: Config, dir: string): Promise<void> {
  (await demoCreator(cfg))({ dir, force: true, quiet: true });
}

/** The checkout's commit (for the run file: launchers tell a stale Foreman by it), or undefined without git. */
function gitHead(root: string): string | undefined {
  const r = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8', timeout: 5000 });
  return r.status === 0 ? r.stdout.trim() || undefined : undefined;
}

function wipeProfile(cfg: Config): void {
  // only ever delete inside the configured home
  if (!isInsideOrEqual(cfg.dataDir, cfg.home) || path.resolve(cfg.dataDir) === path.resolve(cfg.home)) {
    throw new Error(`refusing to wipe ${cfg.dataDir}`);
  }
  fs.rmSync(cfg.dataDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
}

export async function main(argv: string[]): Promise<void> {
  if (argv.includes('--help') || argv.includes('-h')) {
    console.log(HELP);
    return;
  }
  const cfg = loadConfig(argv);
  const log = consoleLogger('foreman', { debug: cfg.debug, quiet: cfg.quiet });

  // one Foreman per profile (two would both write its state.json) - checked before --reset wipes it
  const owner = await liveOwner(profileRunFile(cfg.dataDir));
  if (owner) {
    log.error(`profile "${cfg.profile}" is in use by the Foreman pid ${owner.pid} (ws://${owner.host}:${owner.port}). Stop it first, or use another --profile.`);
    process.exitCode = 1;
    return;
  }

  if (cfg.reset) {
    log.info(`reset: wiping ${cfg.dataDir}`);
    wipeProfile(cfg);
  }
  const fresh = !fs.existsSync(path.join(cfg.dataDir, 'state.json'));

  // sim: default to a fresh copy of the demo repo the scenario knows how to edit
  // (one per profile, so e.g. a live `sim` run and a `showcase` hold never share a repo)
  if (cfg.backend === 'sim' && cfg.repos.length === 0) {
    const demo = path.join(cfg.projectRoot, 'sandbox', cfg.profile === 'sim' ? 'sim-demo' : `sim-demo-${cfg.profile}`);
    if (fresh || !fs.existsSync(demo)) {
      log.info(`sim: creating fresh demo repo at ${demo}`);
      await createDemoRepo(cfg, demo);
    }
    // --sim-pr: a second demo repo whose work lands as pull requests on the sim's fake host. It is
    // registered first, so the scripted demo repo stays the default (the last one registered).
    if (cfg.sim.prDemo) {
      const api = simPrRepoDir(cfg);
      if (fresh || !fs.existsSync(api)) {
        log.info(`sim: creating fresh pull request demo repo at ${api}`);
        createSimPrRepo(api, await demoCreator(cfg));
      }
      cfg.repos.push(api);
      simPrRepoSettings(cfg, api);
    }
    cfg.repos.push(demo);
  }

  const foreman = new Foreman({ config: cfg, logger: log });
  const backend = cfg.backend === 'sim' ? new SimBackend(foreman, cfg.sim) : new ClaudeBackend(foreman, cfg.claude);
  // a new client token every start (after --reset wiped the profile); never logged
  const client = cfg.clientToken ? createClientToken(cfg.dataDir) : undefined;
  if (client) foreman.addSecrets([client.token]);
  if (!client) log.warn('--no-client-token: every local WebSocket client may drive the Foreman (dev only)');
  const server = new ForemanServer(foreman, { host: cfg.host, port: cfg.port, allowBrowserOrigins: cfg.allowBrowserOrigins, validateOutbound: cfg.debug, ...(client ? { token: client.token } : {}), log: foreman.log });

  try {
    await server.start();
  } catch (e) {
    const code = (e as NodeJS.ErrnoException).code;
    if (code === 'EADDRINUSE') {
      log.error(`port ${cfg.port} is already in use - is another Foreman running? (see ${homeRunFile(cfg.home)} and <home>/<profile>/foreman.json) Use --port or AGENTCRAFT_PORT.`);
    } else log.error(`could not listen on ${cfg.host}:${cfg.port}: ${(e as Error).message}`);
    await foreman.close();
    if (client) removeClientToken(client.file, client.token);
    process.exitCode = 1;
    return;
  }
  foreman.endpoint = { port: server.port, ...(client ? { tokenFile: client.file } : {}) };

  const commit = gitHead(cfg.projectRoot);
  const runInfo: RunInfo = { pid: process.pid, port: server.port, host: cfg.host, backend: cfg.backend, profile: cfg.profile, version: FOREMAN_VERSION, startedAt: new Date().toISOString(), ...(client ? { tokenFile: client.file } : {}), root: cfg.projectRoot, ...(commit ? { commit } : {}) };
  await claimRunFiles(cfg.home, cfg.dataDir, runInfo);

  log.info(`AgentCraft Foreman ${FOREMAN_VERSION} | backend ${cfg.backend} | ws://${cfg.host}:${server.port} | state ${cfg.dataDir}`);

  let shuttingDown = false;
  /** Close the server (frees the port) and the Foreman (state saved), drop our run files and token. */
  const stopAll = async () => {
    await server.stop();
    await foreman.close();
    await releaseRunFiles(cfg.home, cfg.dataDir).catch(() => undefined);
    if (client) removeClientToken(client.file, client.token);
  };
  const shutdown = async (sig: string) => {
    if (shuttingDown) return;
    shuttingDown = true;
    log.info(`${sig}: shutting down (state is saved; agents resume on next start)`);
    const force = setTimeout(() => process.exit(0), 8000);
    force.unref();
    await stopAll();
    clearTimeout(force);
    process.exit(0);
  };
  // foreman.restart: the same command again, detached; the new process takes over port and profile
  foreman.restarter = () => {
    if (shuttingDown) return;
    shuttingDown = true;
    log.info('foreman.restart: restarting (state is saved; agents resume on start)');
    void (async () => {
      const force = setTimeout(() => process.exit(1), 15_000);
      force.unref();
      await stopAll();
      try {
        const pid = spawnRestart(currentRestartCommand(cfg.argv));
        // the run file names the new process until it claims it itself (launchers find it there).
        // tokenFile stays: the path is the same every start, and the new process writes its token
        // there before it listens, so whoever reads this file once the port answers gets the new one
        writeJsonAtomic(profileRunFile(cfg.dataDir), { ...runInfo, pid, startedAt: new Date().toISOString() });
        log.info(`restarted as pid ${pid}`);
      } catch (e) {
        log.error(`restart failed: ${(e as Error).message}; start the Foreman again by hand`);
        process.exitCode = 1;
      }
      clearTimeout(force);
      process.exit();
    })();
  };
  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
  process.on('SIGBREAK', () => void shutdown('SIGBREAK'));
  // stdin "q" + Enter also quits cleanly (handy on Windows where signals to children are awkward)
  if (process.stdin.isTTY) {
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (d: string) => {
      if (d.trim() === 'q') void shutdown('quit');
    });
  }
  // through the redacting logger: an error can quote a secret
  process.on('uncaughtException', (e) => foreman.log.error(`uncaught: ${e.stack ?? e}`));
  process.on('unhandledRejection', (e) => foreman.log.error(`unhandled rejection: ${(e as Error)?.stack ?? e}`));

  await foreman.start(backend);
  if (backend instanceof SimBackend && (cfg.autostart || cfg.goal)) {
    await backend.autostart(cfg.goal ?? DEFAULT_SIM_GOAL);
  } else if (cfg.goal) {
    await foreman.submitGoal(cfg.goal).catch((e) => log.error(`goal: ${(e as Error).message}`));
  }
}

main(process.argv.slice(2)).catch((e) => {
  console.error(e instanceof Error ? e.message : e);
  process.exit(1);
});

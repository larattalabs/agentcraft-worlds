#!/usr/bin/env node
// Set up a Prism Launcher instance (the Hardcore world) to play with AgentCraft from a STABLE
// checkout, with its own Foreman profile and port. Dry run by default: prints exactly what it would
// change. --apply does it. See tools/README.md "Playing in a Hardcore world".
//
// Steps (each can be skipped, see --help):
//   1. checks: instance, game dir, Minecraft version, Fabric API, Prism not running (for --apply),
//      the instance's current PreLaunchCommand is empty or ours
//   2. stable checkout: clone (or fetch) --source into --stable and check out --ref, detached
//   3. npm dependencies in <stable>/foreman and <stable>/tools
//   4. build the mod jar in <stable>/mod
//   5. backups: the world saves (backup-world.sh), and instance.cfg + mods/ into
//      <backup-dir>/agentcraft-setup-<stamp>/
//   6. mods/: older agentcraft*.jar out, the new jar in
//   7. instance.cfg: PreLaunchCommand -> <stable>/tools/foreman-daemon.sh start ...;
//      JvmArgs += -Dagentcraft.port/profile (and the DevBridge with --devbridge);
//      PostExitCommand kept (with --stop-on-exit: the backup first, then the Foreman is stopped)
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { devCheckoutConflict, expandHome } from './lib/daemonplan.mjs';
import {
  readGeneral, setGeneral, preLaunchCommand, isOurPreLaunch, wrapPostExit, unwrapPostExit,
  isWrappedPostExit, mergeJvmArgs,
} from './lib/prismcfg.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const thisRoot = path.resolve(here, '..');
const HOME = os.homedir();

export const DEFAULTS = {
  instance: path.join(HOME, 'Library', 'Application Support', 'PrismLauncher', 'instances', 'Hardcore-World'),
  stable: path.join(HOME, 'Developer', 'agentcraft-stable'),
  ref: 'main',
  profile: 'hardcore',
  port: 7880,
  devPort: 7881,
  home: path.join(HOME, '.agentcraft'),
  backupScript: path.join(HOME, 'bin', 'backup-world.sh'),
  java: '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home',
};
const DEV_PORTS = new Set([7878, 7879]);

function usage(code = 0) {
  console.log(`AgentCraft Hardcore setup (dry run unless --apply)
  node tools/hardcore-setup.mjs [--apply] [options]

  --instance PATH       Prism instance dir (default ${DEFAULTS.instance})
  --stable PATH         stable checkout (default ${DEFAULTS.stable})
  --source REPO         where the stable checkout clones/fetches from (default: the repository this
                        script runs from, ${defaultSource()}; run from the stable checkout itself:
                        its origin)
  --ref REF             branch, tag or commit to run (default ${DEFAULTS.ref})
  --profile NAME        Foreman profile (default ${DEFAULTS.profile})
  --port N              Foreman port (default ${DEFAULTS.port}; 7878/7879 belong to dev runs)
  --home PATH           Foreman home (default ~/.agentcraft)
  --backup-dir PATH     where backups go (default ~/MinecraftBackups/<instance dir name>)
  --backup-script PATH  world backup script (default ${DEFAULTS.backupScript})
  --stop-on-exit        stop the Foreman when the game exits (after the backup). Default: it keeps
                        running (PR polling continues); running again without it undoes it.
  --devbridge [--dev-port N]  enable the DevBridge in this instance on port N (default ${DEFAULTS.devPort})
                        for scripted checks; running again without it turns it off
  --jar PATH            install this jar instead of building one
  --skip-checkout       use --stable as it is (no clone/fetch/checkout)
  --skip-deps           do not run npm ci
  --apply               make the changes (Prism must be closed: it rewrites instance.cfg)`);
  process.exit(code);
}

/**
 * Where the stable checkout comes from: --source; else, when this script runs from the stable
 * checkout itself, that checkout's origin (so an update fetches new commits instead of resolving
 * the ref against itself); else the repository this script runs from.
 */
export function chooseSource({ explicit, thisRoot, stable, stableOrigin, fallback }) {
  if (explicit) return explicit;
  if (realpath(thisRoot) === realpath(stable)) {
    if (!stableOrigin) throw new Error(`running from the stable checkout ${stable}, which has no origin remote; pass --source`);
    return stableOrigin;
  }
  return fallback;
}

/**
 * The commit `ref` names in `source`, from `git ls-remote` output (read-only, works for a path or
 * a URL): a branch, then a tag (peeled), then an exact ref name. A full SHA is taken as is.
 */
export function resolveRemoteRef(lsRemote, ref) {
  if (/^[0-9a-f]{40}$/i.test(ref)) return ref.toLowerCase();
  const refs = new Map();
  for (const line of String(lsRemote ?? '').split('\n')) {
    const m = /^([0-9a-f]{40})\s+(\S+)$/.exec(line.trim());
    if (m) refs.set(m[2], m[1]);
  }
  for (const name of [`refs/heads/${ref}`, `refs/tags/${ref}^{}`, `refs/tags/${ref}`, ref]) {
    if (refs.has(name)) return refs.get(name);
  }
  return null;
}

function defaultSource() {
  const r = spawnSync('git', ['rev-parse', '--path-format=absolute', '--git-common-dir'], { cwd: thisRoot, encoding: 'utf8' });
  if (r.status !== 0) return thisRoot;
  const common = r.stdout.trim();
  return path.basename(common) === '.git' ? path.dirname(common) : common;
}

export function parseArgs(argv) {
  const out = { ...DEFAULTS, apply: false };
  const flags = new Set(['apply', 'stop-on-exit', 'devbridge', 'skip-checkout', 'skip-deps']);
  const values = new Set(['instance', 'stable', 'source', 'ref', 'profile', 'port', 'home', 'backup-dir', 'backup-script', 'dev-port', 'jar']);
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--help' || a === '-h') return { help: true };
    const key = a.replace(/^--/, '');
    if (!a.startsWith('--')) throw new Error(`unexpected argument: ${a}`);
    if (flags.has(key)) out[camel(key)] = true;
    else if (values.has(key)) {
      if (argv[i + 1] === undefined) throw new Error(`${a} needs a value`);
      out[camel(key)] = argv[++i];
    } else throw new Error(`unknown option: ${a}`);
  }
  for (const k of ['instance', 'stable', 'home', 'backupDir', 'backupScript', 'jar']) {
    if (out[k]) out[k] = path.resolve(expandHome(out[k]));
  }
  out.port = Number(out.port);
  out.devPort = Number(out.devPort);
  for (const p of [out.port, out.devPort]) if (!Number.isInteger(p) || p < 1 || p > 65535) throw new Error(`invalid port: ${p}`);
  if (DEV_PORTS.has(out.port)) throw new Error(`port ${out.port} is used by dev runs (tools/mac.mjs); pick another, e.g. ${DEFAULTS.port}`);
  if (out.devbridge && (DEV_PORTS.has(out.devPort) || out.devPort === out.port)) throw new Error(`--dev-port ${out.devPort} collides with the Foreman port or dev runs`);
  if (!/^[\w-]+$/.test(out.profile)) throw new Error('profile must contain only letters, digits, _ or -');
  if (/\s/.test(out.home)) throw new Error(`--home cannot contain spaces (it goes into JvmArgs): ${out.home}`);
  out.backupDir ??= path.join(HOME, 'MinecraftBackups', path.basename(out.instance));
  if (!out.source) {
    const origin = spawnSync('git', ['remote', 'get-url', 'origin'], { cwd: out.stable, encoding: 'utf8' });
    out.source = chooseSource({ explicit: null, thisRoot, stable: out.stable, stableOrigin: origin.status === 0 ? origin.stdout.trim() : null, fallback: defaultSource() });
  }
  return out;
}
function camel(k) { return k.replace(/-(\w)/g, (_, c) => c.toUpperCase()); }

const realpath = (p) => { try { return fs.realpathSync(p); } catch { return path.resolve(p); } };

/** Real side effects; tests replace them. */
export const realDeps = {
  /** read-only command: stdout (trimmed) or null */
  query(cmd, args, opts = {}) {
    const r = spawnSync(cmd, args, { encoding: 'utf8', ...opts });
    return r.status === 0 ? r.stdout.trim() : null;
  },
  /** mutating command: throws on failure */
  exec(cmd, args, opts = {}) {
    const r = spawnSync(cmd, args, { stdio: 'inherit', ...opts });
    if (r.status !== 0) throw new Error(`${cmd} ${args.join(' ')} failed (exit ${r.status})`);
  },
  prismRunning() {
    const r = spawnSync('pgrep', ['-f', 'Prism Launcher.app/Contents/MacOS/|/prismlauncher( |$)'], { encoding: 'utf8' });
    return r.status === 0;
  },
  now: () => new Date(),
  log: (line) => console.log(line),
};

function stampOf(date) {
  const p = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}${p(date.getMonth() + 1)}${p(date.getDate())}-${p(date.getHours())}${p(date.getMinutes())}${p(date.getSeconds())}`;
}

function gameDirOf(instance) {
  for (const name of ['.minecraft', 'minecraft']) {
    const d = path.join(instance, name);
    if (fs.existsSync(d)) return d;
  }
  return path.join(instance, '.minecraft');
}

const isAgentcraftJar = (name) => /^agentcraft[-_.].*\.jar$/i.test(name) || /^agentcraft\.jar$/i.test(name);

function builtJar(stable) {
  const dir = path.join(stable, 'mod', 'build', 'libs');
  const jars = fs.existsSync(dir) ? fs.readdirSync(dir).filter((n) => /^agentcraft-.*\.jar$/.test(n) && !/-(sources|dev|javadoc)\.jar$/.test(n)) : [];
  return jars.length ? path.join(dir, jars.sort().at(-1)) : null;
}

/**
 * Plan (and with opt.apply, perform) the setup. Returns { changes: string[], warnings: string[],
 * backup: dir|null, cfg: new instance.cfg text|null }. Throws on a blocking problem.
 */
export async function setup(opt, deps = realDeps) {
  const say = deps.log;
  const apply = opt.apply;
  const warnings = [];
  const changes = [];
  const warn = (m) => { warnings.push(m); say(`  ! ${m}`); };
  const plan = (m) => { changes.push(m); say(`  ${apply ? '-' : 'would'} ${m}`); };

  say(`AgentCraft Hardcore setup ${apply ? '(APPLY)' : '(dry run: nothing is changed; add --apply)'}`);

  // 1. checks
  say('\n1. checks');
  const cfgFile = path.join(opt.instance, 'instance.cfg');
  if (!fs.existsSync(cfgFile)) throw new Error(`no instance.cfg in ${opt.instance}`);
  const gameDir = gameDirOf(opt.instance);
  const modsDir = path.join(gameDir, 'mods');
  if (!fs.existsSync(modsDir)) throw new Error(`no mods folder at ${modsDir}`);
  say(`  instance ${opt.instance}`);
  say(`  game dir ${gameDir}`);
  const cfgText = fs.readFileSync(cfgFile, 'utf8');
  const general = readGeneral(cfgText);
  const pack = (() => { try { return JSON.parse(fs.readFileSync(path.join(opt.instance, 'mmc-pack.json'), 'utf8')); } catch { return null; } })();
  const mc = pack?.components?.find((c) => c.uid === 'net.minecraft')?.version;
  const modMc = (() => {
    // the version at --ref (what will be built), or the checkout as it is with --skip-checkout
    let text = opt.skipCheckout ? null : deps.query('git', ['show', `${opt.ref}:mod/gradle.properties`], { cwd: opt.source });
    if (text === null) { try { text = fs.readFileSync(path.join(opt.stable, 'mod', 'gradle.properties'), 'utf8'); } catch { /* none */ } }
    return text ? /^minecraft_version=(.+)$/m.exec(text)?.[1]?.trim() ?? null : null;
  })();
  if (mc && modMc && mc !== modMc) throw new Error(`the instance runs Minecraft ${mc}, the mod is built for ${modMc}`);
  say(`  Minecraft ${mc ?? 'unknown'}${modMc ? ` (mod: ${modMc})` : ''}`);
  const mods = fs.readdirSync(modsDir);
  if (!mods.some((n) => /^fabric-api-.*\.jar$/.test(n))) warn('no fabric-api jar in mods/: AgentCraft needs Fabric API');
  const pre = general.PreLaunchCommand ?? '';
  if (pre.trim() && !isOurPreLaunch(pre)) throw new Error(`the instance already has a PreLaunchCommand that is not ours: ${pre}\nRemove it in Prism (Edit instance > Settings > Custom commands) or fold it in by hand.`);
  if (general.WrapperCommand?.trim()) warn(`the instance has a WrapperCommand (${general.WrapperCommand}); it is kept`);
  const prism = deps.prismRunning();
  if (prism) {
    if (apply) throw new Error('Prism Launcher is running. Quit it first (it keeps instance settings in memory and would overwrite instance.cfg).');
    warn('Prism Launcher is running: quit it before --apply');
  }
  const config = (() => { try { return JSON.parse(fs.readFileSync(path.join(opt.home, 'config.json'), 'utf8')); } catch { return null; } })();
  const repos = (Array.isArray(config?.repos) ? config.repos : []).map((r) => realpath(expandHome(String(r))));
  const clash = devCheckoutConflict({ root: realpath(opt.stable), commonDir: null, repos });
  if (clash) throw new Error(`--stable ${opt.stable} is (inside) ${clash}, a repository the Foreman works on; use a separate directory`);

  // 2. stable checkout
  say(`\n2. stable checkout ${opt.stable}`);
  let commit = null;
  if (opt.skipCheckout) {
    if (!fs.existsSync(opt.stable)) throw new Error(`--skip-checkout: ${opt.stable} does not exist`);
    commit = deps.query('git', ['rev-parse', 'HEAD'], { cwd: opt.stable });
    say(`  using it as it is (${commit ? commit.slice(0, 9) : 'not a git checkout'})`);
  } else {
    // ls-remote: read-only, current (no stale remote-tracking refs), works for a path or a URL;
    // an abbreviated SHA is resolved in a local source
    let target = resolveRemoteRef(deps.query('git', ['ls-remote', opt.source]), opt.ref);
    if (!target && /^[0-9a-f]{4,39}$/i.test(opt.ref)) target = deps.query('git', ['rev-parse', '--verify', `${opt.ref}^{commit}`], { cwd: opt.source });
    if (!target) throw new Error(`cannot resolve ${opt.ref} in ${opt.source}`);
    const exists = fs.existsSync(path.join(opt.stable, '.git'));
    if (exists) {
      const dirty = deps.query('git', ['status', '--porcelain', '--untracked-files=no'], { cwd: opt.stable });
      if (dirty) throw new Error(`${opt.stable} has local changes; commit or discard them first:\n${dirty}`);
      const current = deps.query('git', ['rev-parse', 'HEAD'], { cwd: opt.stable });
      if (current === target) say(`  already at ${opt.ref} (${target.slice(0, 9)})`);
      else plan(`fetch ${opt.source} and check out ${opt.ref} (${target.slice(0, 9)}, was ${current?.slice(0, 9) ?? '?'}) detached`);
      if (apply && current !== target) {
        deps.exec('git', ['fetch', '--quiet', opt.source, '+refs/heads/*:refs/remotes/origin/*', '--tags'], { cwd: opt.stable });
        deps.exec('git', ['checkout', '--quiet', '--detach', target], { cwd: opt.stable });
      }
    } else {
      if (fs.existsSync(opt.stable) && fs.readdirSync(opt.stable).length) throw new Error(`${opt.stable} exists and is not a git checkout`);
      plan(`clone ${opt.source} into ${opt.stable} and check out ${opt.ref} (${target.slice(0, 9)}) detached`);
      if (apply) {
        deps.exec('git', ['clone', '--quiet', opt.source, opt.stable]);
        deps.exec('git', ['checkout', '--quiet', '--detach', target], { cwd: opt.stable });
      }
    }
    commit = target;
  }
  const daemon = path.join(opt.stable, 'tools', 'foreman-daemon.sh');

  // 3. deps
  say('\n3. npm dependencies');
  if (opt.skipDeps) say('  skipped (--skip-deps)');
  else for (const dir of ['foreman', 'tools']) {
    plan(`npm ci in ${path.join(opt.stable, dir)}`);
    if (apply) deps.exec('npm', ['ci', '--no-audit', '--no-fund'], { cwd: path.join(opt.stable, dir) });
  }

  // 4. jar
  say('\n4. mod jar');
  let jar = opt.jar ?? null;
  if (jar) {
    if (!fs.existsSync(jar)) throw new Error(`--jar ${jar} does not exist`);
    say(`  using ${jar}`);
  } else {
    plan(`build the mod in ${path.join(opt.stable, 'mod')} (gradlew build, Java 25)`);
    if (apply) {
      const javaHome = process.env.JAVA_HOME && /25/.test(process.env.JAVA_HOME) ? process.env.JAVA_HOME : DEFAULTS.java;
      deps.exec('/bin/bash', ['gradlew', 'build', '--console=plain', '-q'], { cwd: path.join(opt.stable, 'mod'), env: { ...process.env, JAVA_HOME: javaHome } });
      jar = builtJar(opt.stable);
      if (!jar) throw new Error(`the build produced no agentcraft-*.jar in ${path.join(opt.stable, 'mod', 'build', 'libs')}`);
    }
  }
  const jarName = jar ? path.basename(jar) : 'agentcraft-<version>.jar';

  // 5. backups
  const backup = path.join(opt.backupDir, `agentcraft-setup-${stampOf(deps.now())}`);
  say('\n5. backups');
  if (fs.existsSync(opt.backupScript)) {
    plan(`back up the world saves: ${opt.backupScript} ${gameDir} ${opt.backupDir}`);
    if (apply) deps.exec('/bin/bash', [opt.backupScript, gameDir, opt.backupDir]);
  } else warn(`no world backup script at ${opt.backupScript}; saves are NOT backed up`);
  // the instance as it was before AgentCraft, kept once: the rollback target (later runs' backups
  // already contain our settings)
  const original = path.join(opt.backupDir, 'agentcraft-setup-original');
  const snapshot = (dir) => {
    fs.mkdirSync(dir, { recursive: true });
    fs.copyFileSync(cfgFile, path.join(dir, 'instance.cfg'));
    fs.cpSync(modsDir, path.join(dir, 'mods'), { recursive: true });
  };
  if (!isOurPreLaunch(pre) && !fs.existsSync(original)) {
    plan(`copy instance.cfg and mods/ to ${original} (the pre-AgentCraft state, kept for rollback)`);
    if (apply) snapshot(original);
  }
  plan(`copy instance.cfg and mods/ to ${backup}`);
  if (apply) snapshot(backup);

  // 6. mods
  say('\n6. mods/');
  const old = mods.filter(isAgentcraftJar);
  for (const name of old) {
    if (name === jarName) continue;
    plan(`remove mods/${name} (a copy is in the backup)`);
    if (apply) fs.rmSync(path.join(modsDir, name));
  }
  plan(`${old.includes(jarName) ? 'replace' : 'add'} mods/${jarName}`);
  if (apply) fs.copyFileSync(jar, path.join(modsDir, jarName));

  // 7. instance.cfg
  say('\n7. instance.cfg');
  const updates = {
    PreLaunchCommand: preLaunchCommand({ daemon, profile: opt.profile, port: opt.port, home: opt.home }),
    JvmArgs: mergeJvmArgs(general.JvmArgs ?? '', {
      'agentcraft.port': opt.port,
      'agentcraft.profile': opt.profile,
      'agentcraft.home': path.resolve(opt.home) === path.join(HOME, '.agentcraft') ? null : opt.home,
      'agentcraft.dev': opt.devbridge ? 1 : null,
      'agentcraft.dev.port': opt.devbridge ? opt.devPort : null,
    }),
    OverrideCommands: 'true',
    OverrideJavaArgs: 'true',
  };
  const post = general.PostExitCommand ?? '';
  updates.PostExitCommand = opt.stopOnExit ? wrapPostExit(post, { daemon, profile: opt.profile, home: opt.home }) : unwrapPostExit(post);
  if (!post.trim()) warn('the instance has no PostExitCommand (no world backup after each session)');
  if (general.OverrideJavaArgs !== 'true') warn('OverrideJavaArgs was off: Prism\'s global JVM arguments stop applying to this instance');
  if (general.OverrideCommands !== 'true' && !pre.trim()) warn('OverrideCommands was off: Prism\'s global custom commands stop applying to this instance');
  const newText = setGeneral(cfgText, updates);
  for (const [key, value] of Object.entries(updates)) {
    const before = general[key] ?? '';
    if (before !== value) plan(`set ${key}: ${JSON.stringify(before)} -> ${JSON.stringify(value)}`);
  }
  if (isWrappedPostExit(post) && !opt.stopOnExit) say('  (PostExitCommand: --stop-on-exit removed; the backup command is kept as it was)');
  if (newText === cfgText) say('  instance.cfg already up to date');
  else if (apply) {
    const tmp = `${cfgFile}.agentcraft-tmp`;
    fs.writeFileSync(tmp, newText);
    fs.renameSync(tmp, cfgFile);
  }

  say(`\nForeman: profile ${opt.profile}, port ${opt.port}, home ${opt.home}${commit ? `, commit ${commit.slice(0, 9)}` : ''}`);
  say(`Log: ${path.join(opt.stable, 'artifacts', 'logs', `foreman-daemon-${opt.profile}.log`)}`);
  const rollbackFrom = fs.existsSync(original) || !isOurPreLaunch(pre) ? original : backup;
  say(`Rollback (Prism closed): cp "${path.join(rollbackFrom, 'instance.cfg')}" "${cfgFile}"; rm "${modsDir}"/agentcraft*.jar; cp "${path.join(rollbackFrom, 'mods')}"/agentcraft*.jar "${modsDir}"/ (only if there were any); then "${daemon}" stop`);
  if (!apply) say('\nDry run: nothing was changed. Re-run with --apply (Prism closed).');
  return { changes, warnings, backup: apply ? backup : null, original, cfg: newText };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const opt = parseArgs(process.argv.slice(2));
    if (opt.help) usage(0);
    await setup(opt);
  } catch (e) {
    console.error(`hardcore-setup: ${e.message}`);
    process.exitCode = 1;
  }
}

// Claude backend: building design jobs (docs/HUB.md "The design job").
//
// One job at a time, queued in request order. A job:
//   1. prepares <profile>/designs/<id>/ (repo-shaped scratch dir): a fresh copy of tools/blueprints,
//      docs/BUILDINGS.md, BRIEF.md written from the request, the remix source if any
//   2. runs a design agent turn there (claude_code preset, cwd = the scratch dir, the workers'
//      permission machinery but no prompts: whatever the policy would ask about is refused; no
//      network, no subagents; a tiny `design_status` tool reports progress)
//   3. re-checks the result itself with a PRISTINE kit (build.mjs + checker, size/kind/wings
//      against the request); a failed check goes back to the agent (same session), up to
//      MAX_ROUNDS turns in all
//   4. renders previews (if the kit has render.mjs) and installs the blueprint into the request's
//      outDir under a fresh id (never overwriting), then reports done
// A usage limit puts the job back at the front of the queue (it resumes the same session once the
// limit resets); a Foreman shutdown leaves it unfinished and it is picked up on the next start.
import fs from 'node:fs';
import path from 'node:path';
import { createSdkMcpServer, tool, type EffortLevel, type McpServerConfig, type SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { z } from 'zod';
import type { ClaudeConfig } from '../../config.js';
import type { Foreman } from '../../foreman.js';
import type { Design, DesignRequest } from '../../protocol.js';
import { checkDesign, designBaseId, freeBlueprintId, installBlueprint, isFinalDesign, KIT_DIR, BUILT_NBT_DIR, PREVIEW_DIR, refreshKit, renderPreviews, rendererIn } from '../../designs.js';
import { truncate } from '../../util/text.js';
import type { TurnStats } from './stream.js';
import { MCP_SERVER } from './tools.js';

/** design agent turns per job (the first + follow-ups after a failed check) */
export const MAX_DESIGN_ROUNDS = 4;
const DESIGN_TURN_TIMEOUT_MS = 30 * 60_000;
/** the id design turns log and record sessions under (not a roster agent) */
export const DESIGNER = 'designer';

export type AuxAbortReason = 'pause' | 'stop' | 'shutdown' | 'cancel' | 'timeout';

/** One SDK turn outside the agent roster (ClaudeBackend.runAuxTurn). */
export interface AuxTurnSpec {
  /** id for logs, sessions and the git identity (not a roster agent) */
  logId: string;
  sessionKey: string;
  cwd: string;
  prompt: string;
  systemAppend: string;
  model: string;
  effort: EffortLevel;
  maxTurns: number;
  mcpServers: Record<string, McpServerConfig>;
  resume?: string;
  timeoutMs: number;
  onMessage?(msg: SDKMessage): void;
}

export interface AuxTurn {
  abort(reason: AuxAbortReason): void;
  done: Promise<{ stats: TurnStats; reason?: AuxAbortReason }>;
}

export interface DesignHost {
  fm: Foreman;
  cfg: ClaudeConfig;
  /** a turn may start now (auth ok, not stopping, no usage limit in force) */
  canStart(): boolean;
  /** a limited turn: make sure the limit is in force; returns when it resets (epoch ms) */
  holdForLimit(stats: TurnStats): number | undefined;
  runTurn(spec: AuxTurnSpec): AuxTurn;
  /** a job finished or was dropped: let the scheduler run */
  tick(): void;
}

interface DesignWork {
  /** the blueprint id the agent builds under (the installed id may get a suffix) */
  bp: string;
  /** turns started so far */
  round: number;
  /** the next turn's prompt (a follow-up after a failed check) */
  pending?: string;
}

const STYLE_GUIDE: Record<DesignRequest['style'], string> = {
  modern:
    'Modern: crisp plaster walls (B.plaster = smooth_quartz) framed with B.plasterFrame (calcite), large glass panes, a flat roof or a low hip roof (smooth_stone_slab, waxed_cut_copper / waxed_cut_copper_slab edges), froglight panels (B.glowPanel) for light, oak plank or terracotta floors. Clean lines, few ornaments.',
  cabin:
    'Cabin: log walls (minecraft:stripped_dark_oak_log, posts at the corners), a stone-brick foundation and a stone-brick chimney, a steep gable roof of dark_oak_stairs with a slab ridge, small paned windows, lanterns and candles, walnut panels and bookshelves inside. Warm and cosy.',
  townhouse:
    'Townhouse: a brick facade (minecraft:bricks, brick_stairs / brick_slab trim), a gable roof (dark_oak_stairs) with the ridge parallel to the front, tall paned windows in a regular rhythm with white_terracotta sills and lintels, a raised entrance with a step, plaster (smooth_quartz) inside.',
  workshop:
    "Workshop: the look of the bundled workshop (tools/blueprints/designs/workshop.mjs): plaster and walnut walls (B.plaster / B.walnut: smooth quartz, stripped dark oak), oak plank floors, a shallow dark-oak gable roof with an overhang and gable windows, a porch awning over the door, a stone-brick path.",
  campus:
    'Campus: a central hall (entrance, decision podium, goal atrium, meeting) with one wing per repo, each wing with its own task wall (task_wall@<n>) and desks. tools/blueprints/lib/campus.mjs and designs/campus2..5.mjs show how; you may call buildCampus or adapt it.',
  custom: 'Custom: no preset; the notes describe the building.',
};

const FEATURE_GUIDE: Record<DesignRequest['features'][number], string> = {
  porch: 'a covered porch at the entrance',
  skylights: 'skylights (glass in the roof) over the main room',
  courtyard: 'an open-air courtyard inside the footprint',
  big_windows: 'big windows: floor-to-ceiling glass on the long walls',
  garden: 'a small garden on the plot (moss_block beds, potted plants), inside the maximum size',
};

/** BRIEF.md for a request: what the design agent reads first. */
export function designBrief(req: DesignRequest, bp: string, opts: { renderer: boolean; remix?: string }): string {
  const m = req.maxSize;
  const wings =
    req.kind === 'single'
      ? '- kind: `single` (one repo), wings: 1. One task wall: `task_wall@1`.'
      : `- kind: \`group\`, wings: ${req.wings} (wing n = the n-th repo). One task wall per wing: ${Array.from({ length: req.wings }, (_x, i) => `\`task_wall@${i + 1}\``).join(', ')}; wing placeholders \`repo:#<n>\` / \`ci:#<n>\` on the wing's task wall / CI lamp. Shared rooms (entrance, podium, goal atrium, meeting, lounge) belong to the hall.`;
  const lines = [
    `# Design brief: ${req.name ?? `a ${req.style} building`}`,
    '',
    `Blueprint id: \`${bp}\`. Write it as \`tools/blueprints/designs/${bp}.mjs\` (\`export const id = '${bp}'\` and a default export that returns the Blueprint; the file name must match the id).`,
    '',
    '## Request',
    '',
    wings,
    `- style: \`${req.style}\`. ${STYLE_GUIDE[req.style]}`,
    `- materials: \`${req.materials}\`. ${
      req.materials === 'agentcraft'
        ? 'the AgentCraft look built from vanilla blocks: the kit palette `B` in lib/kit.mjs (plaster = smooth_quartz, plaster frame = calcite, walnut = stripped_dark_oak_log, walnut trim = dark_oak_planks, tile = terracotta, parquet = oak_planks, glow panel = ochre_froglight).'
        : 'any vanilla look.'
    } Either way structure, floors, roofs, trim and light are vanilla blocks; AgentCraft blocks only for the station blocks (monitor, task_board, decision_podium, merge_station, memory_archive / memory_catalog, console_terminal, status_lamp); the checker refuses the decorative ones (plaster_panel, walnut_panel, glow_panel, ...). Set \`materials: '${req.materials}'\` on the Blueprint.`,
    `- features: ${req.features.length ? req.features.map((f) => `${f} (${FEATURE_GUIDE[f]})`).join('; ') : 'none requested'}`,
    `- maximum size (the template, including roof overhangs, porch and garden): x <= ${m.x}, y <= ${m.y}, z <= ${m.z}. Use the space well, but never exceed it.`,
    req.name ? `- name: "${req.name}" (the sidecar's name)` : '- name: pick a short, fitting display name',
    req.notes ? `- notes from the player: ${req.notes}` : '',
    req.remix ? `- remix: start from the blueprint \`${req.remix}\`. ${opts.remix ?? 'Its source was not found; design from scratch in its spirit.'}` : '',
    '',
    '## The contract (docs/BUILDINGS.md has the full text: read it)',
    '',
    '- Coordinates are relative to the template origin (minimum corner); +x east, +y up, +z south. `front` is the side the entrance faces (keep `south` unless the notes say otherwise); the front door is written closed.',
    '- `groundY` is the feet row; row groundY-1 is the floor; the whole `walk` region is written (interior air included).',
    '- Required anchors: `desk_<id>`, `seat_<id>` (if a chair) and `monitor_<id>` for juniper, kit, wren, rowan, tove; `meeting`, `lounge` (+ `lounge_2..`), `library`, `terminal`, `testbench`, `mergestation`, `user`; `task_wall@<n>` per wing, `decision_podium`, `goal_atrium`, `entrance`, `spawn`; `cam_overview`. Seats have a stair block under the feet, two free cells above and a free neighbour cell.',
    '- Station blocks carry their binding (`{"binding": "..."}`); multi-block monitors / task boards: same block, same facing, adjacent.',
    '- Hardcore-safe (the checker enforces it): every outside door is an iron door with a stone button on both sides (the kit\'s `door()` writes that); no AgentCraft block in the outer shell without a solid vanilla block behind it on the outside (`wallLamp()` for lamps in walls); every walk cell lit (block light >= 1) by vanilla sources alone (froglights, sea lanterns, lanterns, lit candles; not monitors, not `minecraft:light`): mind corners and rooms under skylights.',
    '- Set `foundationBlock` on the Blueprint (a vanilla block the mod fills below the floor down to the ground; default `minecraft:stone_bricks`), matching the style.',
    '- Only blocks listed in `tools/blueprints/lib/blocks.mjs` can be used (the checker refuses others, and the table cannot be extended here). When the style wants a block that is not there, use the closest one and say so in your summary.',
    '',
    '## How you work',
    '',
    `- Only \`tools/blueprints/designs/${bp}.mjs\` is yours. Edits anywhere else in tools/blueprints are discarded: the Foreman re-checks your design with a fresh copy of the kit.`,
    '- Build it semantically with the kit (rooms, walls, roof, stations, anchors through lib/kit.mjs helpers), not as a dump of raw coordinates. The other designs in tools/blueprints/designs/ are good examples (workshop.mjs: one repo; campus*.mjs + lib/campus.mjs: groups).',
    `- Build and check: \`node tools/blueprints/build.mjs ${bp}\` (writes ${path.posix.join(BUILT_NBT_DIR.split(path.sep).join('/'), `${bp}.nbt`)} and the sidecar, then runs the checker; "check: OK" is required).`,
    opts.renderer
      ? `- Look at it: \`node tools/blueprints/render.mjs ${BUILT_NBT_DIR.split(path.sep).join('/')}/${bp}.nbt --out ${PREVIEW_DIR}\` writes ${bp}.preview-iso.png / -top.png / -front.png into ${PREVIEW_DIR}/; Read the PNGs and fix what looks wrong (holes, floating blocks, a missing roof, the entrance not on the front, stations hidden or blocked). Iterate.`
      : '- There is no renderer in this kit: check the layout by reasoning about the code and the checker output (no previews).',
    '- Report progress with the `design_status` tool (one short line, e.g. "roof done, checking").',
    '- No network, no installs, no git; nobody can answer questions or permission prompts during the job. Decide yourself and mention assumptions in your summary.',
    '- Finish with ONE line: a summary of the design (style, footprint, highlights).',
    '',
  ];
  return lines.filter((l, i, a) => !(l === '' && a[i - 1] === '') && l !== undefined).join('\n');
}

export function designSystemPrompt(): string {
  return [
    "# You are AgentCraft's building designer",
    'You design Minecraft buildings for AgentCraft (offices where AI agents work) as parametric code with the blueprint kit.',
    'Your working directory is a scratch folder, not a git repository and not anyone\'s project. Everything you need is in it: BRIEF.md (the request: read it first), docs/BUILDINGS.md (the blueprint contract), tools/blueprints/ (the kit: lib/kit.mjs, lib/blocks.mjs, lib/campus.mjs, the checker lib/check.mjs, build.mjs and example designs).',
    'Work only inside this folder. Your turn ends when the design passes the checker, fits the size limits and looks right.',
  ].join('\n');
}

export function designPrompt(bp: string): string {
  return `Design the building described in BRIEF.md as tools/blueprints/designs/${bp}.mjs. Read BRIEF.md and docs/BUILDINGS.md, look at lib/kit.mjs and the closest example design, write the design, then build, check, look at the renders and iterate as BRIEF.md says until the checker passes and it looks right. End with a one-line summary.`;
}

export function designFixPrompt(bp: string, problem: string, round: number): string {
  return `The Foreman re-checked your design with a fresh copy of the kit and it did not pass (round ${round} of ${MAX_DESIGN_ROUNDS}):\n${problem}\n\nFix tools/blueprints/designs/${bp}.mjs (only that file counts), run \`node tools/blueprints/build.mjs ${bp}\` until the check is OK and the size fits BRIEF.md, look at the renders again if there is a renderer, then end with a one-line summary.`;
}

const RESTART_PROMPT = 'The Foreman restarted while you were working on this design. Re-check where you were (tools/blueprints/designs/, BRIEF.md) and continue until the checker passes and it looks right, then end with a one-line summary.';

/** One line of progress from a design turn's tool calls. */
export function designStepFor(msg: SDKMessage, bp: string): string | undefined {
  if (msg.type !== 'assistant' || msg.parent_tool_use_id) return undefined;
  let step: string | undefined;
  for (const b of (msg.message?.content ?? []) as Array<{ type: string; name?: string; input?: Record<string, unknown> }>) {
    if (b.type !== 'tool_use' || !b.name) continue;
    const input = b.input ?? {};
    const cmd = typeof input.command === 'string' ? input.command : '';
    const file = typeof input.file_path === 'string' ? input.file_path : '';
    if (b.name === 'Bash' && /build\.mjs/.test(cmd)) step = 'running the checker';
    else if (b.name === 'Bash' && /render\.mjs/.test(cmd)) step = 'rendering previews';
    else if (b.name === 'Read' && /\.png$/i.test(file)) step = 'looking at the renders';
    else if ((b.name === 'Write' || b.name === 'Edit' || b.name === 'MultiEdit') && file.includes(`${bp}.mjs`)) step = 'writing the design';
    else if (b.name === 'Read' && /BRIEF\.md|BUILDINGS\.md/.test(file)) step = 'reading the brief';
    else if (b.name === 'Read' || b.name === 'Grep' || b.name === 'Glob') step ??= 'studying the kit';
  }
  return step;
}

export class DesignJobs {
  private queue: string[] = [];
  private current: { id: string; turn?: AuxTurn; done: Promise<void> } | undefined;
  private stopping = false;

  constructor(private host: DesignHost) {}

  private get fm(): Foreman {
    return this.host.fm;
  }

  private get work(): Record<string, DesignWork> {
    const b = this.fm.store.data.backend as { designs?: Record<string, DesignWork> };
    return (b.designs ??= {});
  }

  scratchDir(id: string): string {
    return path.join(this.fm.config.dataDir, 'designs', id);
  }

  get runningId(): string | undefined {
    return this.current?.id;
  }

  enqueue(id: string): void {
    if (this.queue.includes(id) || this.current?.id === id) return;
    this.queue.push(id);
    this.kick();
  }

  cancel(id: string): void {
    this.queue = this.queue.filter((x) => x !== id);
    if (this.current?.id === id) this.current.turn?.abort('cancel');
  }

  /** Start the next queued job if nothing runs and turns may start. */
  kick(): void {
    if (this.current || this.stopping || !this.queue.length || !this.host.canStart()) return;
    const id = this.queue.shift()!;
    const cur: { id: string; turn?: AuxTurn; done: Promise<void> } = { id, done: Promise.resolve() };
    this.current = cur;
    cur.done = this.run(cur)
      .catch((e) => {
        this.fm.log.error(`design ${id}: ${(e as Error).stack ?? e}`);
        this.fm.designFailed(id, (e as Error).message);
      })
      .finally(() => {
        if (this.current === cur) this.current = undefined;
        if (!this.stopping) {
          this.kick();
          this.host.tick();
        }
      });
  }

  async stop(): Promise<void> {
    this.stopping = true;
    const cur = this.current;
    if (!cur) return;
    cur.turn?.abort('shutdown');
    await cur.done.catch(() => undefined);
  }

  private takenIds(except: string): Set<string> {
    return new Set(Object.entries(this.work).filter(([k]) => k !== except).map(([, w]) => w.bp));
  }

  /** The design's scratch dir, fresh kit, BRIEF.md and remix source. */
  private prepare(d: Design, w: DesignWork): string {
    const root = this.fm.config.projectRoot;
    const scratch = this.scratchDir(d.id);
    fs.mkdirSync(scratch, { recursive: true });
    refreshKit(root, scratch, [w.bp]);
    fs.mkdirSync(path.join(scratch, 'docs'), { recursive: true });
    fs.copyFileSync(path.join(root, 'docs', 'BUILDINGS.md'), path.join(scratch, 'docs', 'BUILDINGS.md'));
    let remix: string | undefined;
    const r = d.request.remix;
    if (r) {
      if (fs.existsSync(path.join(scratch, KIT_DIR, 'designs', `${r}.mjs`))) remix = `Its source is tools/blueprints/designs/${r}.mjs: copy it into your file and change it.`;
      else {
        // a blueprint an earlier design job made: its module is in that job's scratch dir
        const prev = this.fm.designs.list().find((x) => x.status === 'done' && x.blueprintId === r);
        const prevWork = prev ? this.work[prev.id] : undefined;
        const src = prev && prevWork ? path.join(this.scratchDir(prev.id), KIT_DIR, 'designs', `${prevWork.bp}.mjs`) : undefined;
        const sidecar = path.join(d.request.outDir, `${r}.blueprint.json`);
        fs.mkdirSync(path.join(scratch, 'remix'), { recursive: true });
        if (src && fs.existsSync(src)) {
          fs.copyFileSync(src, path.join(scratch, 'remix', `${r}.mjs`));
          remix = `Its source is remix/${r}.mjs (an earlier generated design, id ${prevWork!.bp}): copy it into your file, set the new id and change it.`;
        } else if (fs.existsSync(sidecar)) {
          fs.copyFileSync(sidecar, path.join(scratch, 'remix', `${r}.blueprint.json`));
          remix = `Only its sidecar is available (remix/${r}.blueprint.json: size, anchors); design in its spirit.`;
        }
      }
    }
    fs.writeFileSync(path.join(scratch, 'BRIEF.md'), designBrief(d.request, w.bp, { renderer: !!rendererIn(scratch), ...(remix ? { remix } : {}) }));
    return scratch;
  }

  private designMcp(id: string): McpServerConfig {
    // the AgentCraft server name, so the policy's own-server rule covers it
    return createSdkMcpServer({
      name: MCP_SERVER,
      version: '0.1.0',
      tools: [
        tool('design_status', 'Report one short line of progress on the design (shown to the player in the hub).', { step: z.string().min(1).max(200) }, async ({ step }) => {
          this.fm.designStep(id, 'designing', step);
          return { content: [{ type: 'text' as const, text: 'ok' }] };
        }),
      ],
    });
  }

  private cancelled(id: string): boolean {
    const d = this.fm.designs.get(id);
    return !d || d.status === 'cancelled';
  }

  private async run(cur: { id: string; turn?: AuxTurn }): Promise<void> {
    const fm = this.fm;
    const id = cur.id;
    const d = fm.designs.get(id);
    if (!d || isFinalDesign(d)) return;
    const req = d.request;
    const work = this.work;
    const w = (work[id] ??= { bp: freeBlueprintId(req.outDir, designBaseId(req), this.takenIds(id)), round: 0 });
    fm.store.markDirty();
    const scratch = this.prepare(d, w);
    const sessionKey = `design:${id}`;
    const cfg = this.host.cfg;
    for (;;) {
      if (this.cancelled(id)) return;
      const resume = fm.store.data.sessions[sessionKey]?.sessionId;
      const prompt = w.pending ?? (resume ? RESTART_PROMPT : designPrompt(w.bp));
      w.round++;
      fm.store.markDirty();
      fm.designStep(id, 'designing', w.round === 1 ? 'the designer is reading the brief' : `revising the design (round ${w.round} of ${MAX_DESIGN_ROUNDS})`);
      const turn = this.host.runTurn({
        logId: DESIGNER,
        sessionKey,
        cwd: scratch,
        prompt,
        systemAppend: designSystemPrompt(),
        model: cfg.designModel,
        effort: cfg.effort,
        maxTurns: cfg.maxTurnsWorker,
        mcpServers: { [MCP_SERVER]: this.designMcp(id) },
        ...(resume ? { resume } : {}),
        timeoutMs: DESIGN_TURN_TIMEOUT_MS,
        onMessage: (msg) => {
          const step = designStepFor(msg, w.bp);
          if (step) fm.designStep(id, 'designing', step);
        },
      });
      cur.turn = turn;
      const { stats, reason } = await turn.done;
      cur.turn = undefined;
      if (reason === 'cancel' || this.cancelled(id)) return;
      if (reason === 'shutdown' || this.stopping) {
        // picked up again on the next start (resuming this session)
        w.round--;
        fm.store.markDirty();
        return;
      }
      if (stats.limited) {
        w.round--;
        fm.store.markDirty();
        const until = this.host.holdForLimit(stats);
        fm.designStep(id, 'queued', `usage limit - resumes ${until ? new Date(until).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'later'}`);
        this.queue.unshift(id);
        return;
      }
      if (stats.authFailed) {
        fm.designFailed(id, `Claude authentication failed (${stats.authFailed})`);
        return;
      }
      delete w.pending;
      fm.designStep(id, 'checking', `checking the design (round ${w.round})`);
      const res = await checkDesign(fm.config.projectRoot, scratch, w.bp, req);
      if (this.cancelled(id)) return;
      if (!res.ok) {
        const problem = res.problem ?? 'the check failed';
        if (w.round < MAX_DESIGN_ROUNDS) {
          w.pending = designFixPrompt(w.bp, problem, w.round + 1);
          fm.store.markDirty();
          fm.designStep(id, 'designing', `check failed: ${truncate(problem.split('\n')[0] ?? problem, 80)}`);
          continue;
        }
        const ended = stats.isError ? ` (the designer's last turn ended: ${stats.subtype ?? stats.errors[0] ?? 'error'})` : '';
        fm.designFailed(id, `${problem}${ended}`);
        return;
      }
      fm.designStep(id, 'rendering', 'rendering previews');
      const r = await renderPreviews(scratch, res.nbt!);
      if (this.cancelled(id)) return;
      const installed = installBlueprint({
        nbt: res.nbt!,
        sidecar: res.sidecar!,
        previews: r.files,
        outDir: req.outDir,
        baseId: designBaseId(req),
        taken: this.takenIds(id),
        ...(req.name ? { meta: { name: req.name } } : {}),
      });
      const s = res.sidecar!.size!;
      fm.designDone(id, installed, { x: s.x, y: s.y, z: s.z }, r.skipped ? 'no renderer' : r.error ? `previews: ${truncate(r.error, 80)}` : '');
      return;
    }
  }
}

// The all-Claude team (`--backend claude`, the default): the agent team (../team.ts) with the Claude
// engine for every lead, every worker and the building design jobs.
import type { query } from '@anthropic-ai/claude-agent-sdk';
import type { ClaudeConfig } from '../../config.js';
import type { Foreman } from '../../foreman.js';
import { TeamBackend } from '../team.js';
import type { TeamOptions } from '../team/core.js';
import { ClaudeEngine } from './engine.js';

export { agentEnv, rotationDue, TeamBackend, type PullFetcher } from '../team.js';
export { NO_ATTRIBUTION } from './engine.js';

/** The team's options (tests: auth probe, PR runner, timings), plus `queryFn`, injectable for tests. */
export type ClaudeBackendOptions = Partial<Omit<TeamOptions, 'name' | 'engines'>> & { queryFn?: typeof query };

export class ClaudeBackend extends TeamBackend {
  constructor(fm: Foreman, cfg: ClaudeConfig, opts: ClaudeBackendOptions = {}) {
    const { queryFn, ...rest } = opts;
    const claude = new ClaudeEngine(fm, cfg, queryFn);
    super(fm, cfg, { ...rest, name: 'claude', engines: { lead: claude, worker: claude, design: claude } });
  }
}

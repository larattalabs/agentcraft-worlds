// Build the agent team for `--backend claude|codex` with the engines the config picks per role
// and per agent (e.g. a Claude lead with Codex workers).
import type { Config, EngineName } from '../config.js';
import type { Foreman } from '../foreman.js';
import { ClaudeEngine } from './claude/engine.js';
import { CodexEngine } from './codex/engine.js';
import type { Engine } from './engine.js';
import { TeamBackend } from './team.js';

export function createTeam(fm: Foreman, cfg: Config): TeamBackend {
  let claude: ClaudeEngine | undefined;
  let codex: CodexEngine | undefined;
  const engine = (n: EngineName): Engine => (n === 'codex' ? (codex ??= new CodexEngine(fm, cfg.codex)) : (claude ??= new ClaudeEngine(fm, cfg.claude)));
  const byAgent = Object.fromEntries(Object.entries(cfg.engines.byAgent).map(([agent, n]) => [agent, engine(n)]));
  return new TeamBackend(fm, cfg.claude, {
    name: cfg.backend === 'codex' ? 'codex' : 'claude',
    engines: { lead: engine(cfg.engines.lead), worker: engine(cfg.engines.worker), byAgent },
  });
}

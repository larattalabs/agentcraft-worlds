// The team backend is one class assembled in layers (src/agents/team/core.ts lists them; upstream's
// agents/team.ts is the top layer, claude/index.ts the all-Claude team). Every state field must live
// in core.ts: with ES2022 class fields, a field declared in a later layer is re-initialised after the
// base constructor ran (losing hooks, designs, prs, ...).
import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import * as claude from '../src/agents/claude/index.js';

const dir = path.join(import.meta.dirname, '..', 'src', 'agents', 'team');
const layers = ['holds.ts', 'turnSetup.ts', 'sessions.ts', 'outcomes.ts', 'recovery.ts', '../team.ts', '../claude/index.ts', ...fs.readdirSync(path.join(dir, 'jobs')).map((f) => `jobs/${f}`)];

describe('team backend layers', () => {
  it('declare no fields outside core.ts', () => {
    for (const f of layers) {
      const text = fs.readFileSync(path.join(dir, f), 'utf8');
      const fields = text.split('\n').filter((l) => /^ {2}(?:(?:private|protected|public|readonly|static|declare|override) )*[A-Za-z_]\w*\??\s*(?::[^(]*)?(?:=.*)?;$/.test(l));
      expect(fields, f).toEqual([]);
    }
  });

  it('index.ts keeps its exports', () => {
    expect(Object.keys(claude).sort()).toEqual(['ClaudeBackend', 'NO_ATTRIBUTION', 'TeamBackend', 'agentEnv', 'rotationDue']);
  });
});

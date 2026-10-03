// Subagents for AgentCraft agents (config.json claude.subagents). Off by default: each agent then
// works alone. When enabled, agents may start Claude Code subagents (built-in ones such as Explore,
// plus the definitions listed here). Subagent tool calls go through the same canUseTool policy and
// PreToolUse guardrails as the agent's own, with its tools and in its worktree; a subagent can
// never get a separate git worktree (`isolation: "worktree"` is refused by the policy).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { AgentDefinition, EffortLevel } from '@anthropic-ai/claude-agent-sdk';

export interface SubagentsConfig {
  enabled: boolean;
  /** agent definition files: names under ~/.claude/agents (".md" optional) or paths */
  agents: string[];
}

export const DEFAULT_SUBAGENTS: SubagentsConfig = { enabled: false, agents: [] };

function frontMatter(text: string): { fields: Record<string, string>; body: string } {
  const t = text.replace(/\r\n/g, '\n');
  if (!t.startsWith('---\n')) return { fields: {}, body: t };
  const end = t.indexOf('\n---', 4);
  if (end < 0) return { fields: {}, body: t };
  const fields: Record<string, string> = {};
  let key = '';
  for (const line of t.slice(4, end).split('\n')) {
    const m = /^([\w-]+):\s*(.*)$/.exec(line);
    if (m) {
      key = m[1]!;
      fields[key] = m[2]!.replace(/^>-?\s*$|^\|-?\s*$/, '').replace(/^["']|["']$/g, '');
    } else if (key && /^\s+\S/.test(line)) {
      // folded / literal block continuation
      fields[key] = `${fields[key] ? `${fields[key]} ` : ''}${line.trim()}`;
    }
  }
  return { fields, body: t.slice(end + 4).replace(/^\n/, '') };
}

/** A Claude Code agent file (`.claude/agents/<name>.md`): front matter + prompt. */
export interface AgentFile {
  name: string;
  description: string;
  prompt: string;
  tools?: string[];
  model?: string;
  effort?: EffortLevel;
}

const EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max'];

/** Parse one agent file; a string is the problem when it is unreadable or incomplete. */
export function readAgentFile(file: string): AgentFile | string {
  let text: string;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch {
    return `cannot read ${file}`;
  }
  const { fields, body } = frontMatter(text);
  if (!fields.description || !body.trim()) return `${file} needs a description and a prompt`;
  const a: AgentFile = { name: fields.name || path.basename(file, '.md'), description: fields.description, prompt: body.trim() };
  const tools = fields.tools?.split(',').map((s) => s.trim()).filter(Boolean);
  if (tools?.length) a.tools = tools;
  if (fields.model && fields.model !== 'inherit') a.model = fields.model;
  if (fields.effort && EFFORTS.includes(fields.effort)) a.effort = fields.effort as EffortLevel;
  return a;
}

/** The SDK definition for an agent file. Never a permissionMode: subagents run under the agent's mode and guardrails. */
export function toDefinition(a: AgentFile): AgentDefinition {
  const def: AgentDefinition = { description: a.description, prompt: a.prompt };
  if (a.tools) def.tools = a.tools;
  if (a.model) def.model = a.model;
  if (a.effort) def.effort = a.effort;
  return def;
}

/** Where an agent spec points: a name under `dir`, else a path (~, absolute, or relative to `base`). */
export function agentFilePath(spec: string, dir: string, base = dir, home = os.homedir()): string {
  if (!/[\\/]/.test(spec)) return path.join(dir, spec.endsWith('.md') ? spec : `${spec}.md`);
  return path.resolve(base, spec.replace(/^~(?=$|[\\/])/, home));
}

/** Read Claude Code agent definition files into SDK AgentDefinitions (keyed by agent name). */
export function loadSubagents(specs: string[], home = os.homedir()): { agents: Record<string, AgentDefinition>; problems: string[] } {
  const agents: Record<string, AgentDefinition> = {};
  const problems: string[] = [];
  for (const spec of specs) {
    const a = readAgentFile(agentFilePath(spec, path.join(home, '.claude', 'agents'), process.cwd(), home));
    if (typeof a === 'string') problems.push(`subagent ${spec}: ${a}`);
    else agents[a.name] = toDefinition(a);
  }
  return { agents, problems };
}

/** A repository's own agent files (`<dir>/.claude/agents/*.md`), minus those used as roles. */
export function loadRepoAgents(dir: string, exclude: Set<string> = new Set()): Record<string, AgentDefinition> {
  const agentsDir = path.join(dir, '.claude', 'agents');
  const out: Record<string, AgentDefinition> = {};
  let files: string[];
  try {
    files = fs.readdirSync(agentsDir).filter((f) => f.endsWith('.md')).sort();
  } catch {
    return out;
  }
  for (const f of files) {
    const a = readAgentFile(path.join(agentsDir, f));
    if (typeof a !== 'string' && !exclude.has(a.name) && !exclude.has(path.basename(f, '.md'))) out[a.name] = toDefinition(a);
  }
  return out;
}

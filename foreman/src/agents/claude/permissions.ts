// How permissive the agents are (config.json claude.permissions).
//
//   mode "policy" (default)  every tool call goes through AgentCraft's policy (policy.ts): what it
//                            can verify is safe runs, everything else asks the user in-world.
//   mode "auto"              Claude Code's auto mode: a classifier decides the calls AgentCraft
//                            would have asked about. A PreToolUse hook keeps a few guardrails that
//                            hold regardless of the classifier, the user's rules or subagents:
//                              - whatever the policy denies (git push, git safety tampering, the
//                                lead editing files, disabled tools)
//                              - git internals: .git links, GIT_DIR / --git-dir redirects, edits
//                                through links that leave the worktree
//                              - (protectCheckouts) writes into the user's checkouts of registered
//                                repos and into AgentCraft's own state (other agents' worktrees):
//                                approved merges are the only way work reaches a checkout
//                            Those still ask in-world; everything the policy allows runs at once.
//
// allow / deny / ask are Claude Code permission rules ("Bash(codex exec:*)", "WebFetch(domain:x)")
// applied in both modes, after the guardrails: an allow rule skips AgentCraft's policy for what it
// matches.
import type { HookCallback } from '@anthropic-ai/claude-agent-sdk';
import path from 'node:path';
import type { Verdict } from '../../policy.js';

export type PermissionMode = 'policy' | 'auto';

export interface PermissionsConfig {
  mode: PermissionMode;
  allow: string[];
  deny: string[];
  ask: string[];
  /** let agents use WebFetch / WebSearch (otherwise removed from their tools) */
  webTools: boolean;
  /** auto mode: writes into registered checkouts and AgentCraft's state still ask */
  protectCheckouts: boolean;
}

export const DEFAULT_PERMISSIONS: PermissionsConfig = { mode: 'policy', allow: [], deny: [], ask: [], webTools: false, protectCheckouts: true };

/** Policy asks that stay asks in auto mode: git internals and redirections, a lead's non-read command, code from a contributor's pull request. */
const STRUCTURAL = /git internals \(\.git\)|points git at another repository|--git-dir\/--work-tree|edit through a link that leads outside|GIT_DIR|protected file|contributor's pull request/i;

const norm = (p: string) => path.resolve(p).replace(/[\\/]+$/, '').toLowerCase();
const inside = (p: string, root: string) => p === root || p.startsWith(`${root}${path.sep}`) || p.startsWith(`${root}/`);

/** Paths a policy ask would write to (from its rule keys). */
export function writeTargets(ruleKeys: string[]): string[] {
  const out: string[] = [];
  for (const k of ruleKeys) {
    let m = /^Bash:outside:[^:]+:(?:w|wtree):(.+)$/.exec(k);
    if (m) {
      out.push(m[1]!);
      continue;
    }
    m = /^(?:Edit|Write|MultiEdit|NotebookEdit):(?!nopath$|link:|\.git:)(.+)$/.exec(k);
    if (m) out.push(m[1]!);
  }
  return out;
}

/**
 * Auto mode: what the guardrail hook decides for a policy verdict. undefined = no decision (the
 * classifier and the user's rules decide).
 */
export function guardrail(v: Verdict, protectedRoots: string[]): { decision: 'allow' | 'deny' | 'ask'; reason: string } | undefined {
  if (v.action === 'deny') return { decision: 'deny', reason: v.reason };
  if (v.action === 'allow') return { decision: 'allow', reason: v.reason };
  if (STRUCTURAL.test(v.reason)) return { decision: 'ask', reason: v.reason };
  const roots = protectedRoots.map(norm);
  const hit = writeTargets(v.ruleKeys).find((t) => roots.some((r) => inside(norm(t), r)));
  if (hit) return { decision: 'ask', reason: `writes into a protected checkout or AgentCraft's state: ${hit}` };
  return undefined;
}

/** Is this claude.ai connector (by name) one the user allowed (claude.context.connectors)? */
export function connectorAllowed(name: string, allowed: string[]): boolean {
  const n = name.toLowerCase();
  return allowed.some((a) => n.includes(a.toLowerCase()));
}

/** Where an MCP tool comes from, as the CLI reports it on hook input (`mcp_server`). */
export interface McpProvenance {
  name: string;
  source: string;
}

export interface McpGate {
  /** claude.ai connectors the user enabled (claude.context.connectors, matched by name) */
  connectors: string[];
  /** MCP servers the Foreman configured for the session: "agentcraft" and claude.context.mcpServers */
  servers: string[];
}

/**
 * Fail-closed gate for MCP tools (every turn, both permission modes). An `mcp__*` tool runs only when
 * it belongs to a server the Foreman configured itself (the team tools server or one of
 * claude.context.mcpServers, never with claude.ai provenance), or to a claude.ai connector the user
 * listed, with matching provenance. Anything else is refused: a connector that loaded although it
 * is not listed, a server from a plugin or the user's own settings, and any MCP tool whose
 * provenance is missing and whose name is not one of the configured servers.
 */
export function mcpGate(toolName: string, prov: McpProvenance | undefined, gate: McpGate): { allow: true } | { allow: false; server: string; reason: string } {
  if (!toolName.startsWith('mcp__')) return { allow: true };
  const named = gate.servers.find((s) => toolName.startsWith(`mcp__${s}__`));
  if (prov) {
    if (prov.source === 'claudeai') {
      if (connectorAllowed(prov.name, gate.connectors)) return { allow: true };
      return { allow: false, server: prov.name, reason: `The claude.ai connector "${prov.name}" is not enabled for AgentCraft agents (claude.context.connectors).` };
    }
    if (gate.servers.includes(prov.name) && (!named || named === prov.name)) return { allow: true };
    return { allow: false, server: prov.name, reason: `The MCP server "${prov.name}" (${prov.source}) is not configured for AgentCraft agents (claude.context.mcpServers).` };
  }
  if (named) return { allow: true };
  const server = toolName.slice(5).split('__')[0] || toolName;
  return { allow: false, server, reason: `The MCP tool ${toolName} has no known origin and is not from a server AgentCraft configured, so it is refused.` };
}

/**
 * PreToolUse hook (every turn, both permission modes): mcpGate. Keyed on the server's provenance
 * (`source`), not its name, and fail-closed when provenance is missing.
 */
export function connectorHook(gate: McpGate | (() => McpGate), report: (toolName: string, server: string) => void): HookCallback {
  return async (input) => {
    if (input.hook_event_name !== 'PreToolUse') return {};
    const prov = (input as { mcp_server?: McpProvenance }).mcp_server;
    const v = mcpGate(input.tool_name, prov, typeof gate === 'function' ? gate() : gate);
    if (v.allow) return {};
    report(input.tool_name, v.server);
    return { hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'deny', permissionDecisionReason: v.reason } };
  };
}

/**
 * The PreToolUse hook for auto mode. `classify` runs the AgentCraft policy for this agent; denials
 * and forced asks are reported through `report`.
 */
export function guardrailHook(
  classify: (toolName: string, input: Record<string, unknown>) => Verdict,
  protectedRoots: () => string[],
  report: (toolName: string, decision: 'deny' | 'ask', reason: string, subagent?: string) => void,
): HookCallback {
  return async (input) => {
    if (input.hook_event_name !== 'PreToolUse') return {};
    const toolInput = (input.tool_input && typeof input.tool_input === 'object' ? input.tool_input : {}) as Record<string, unknown>;
    const g = guardrail(classify(input.tool_name, toolInput), protectedRoots());
    if (!g) return {};
    if (g.decision !== 'allow') report(input.tool_name, g.decision, g.reason, input.agent_id);
    return { hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: g.decision, permissionDecisionReason: g.reason } };
  };
}

/**
 * PreToolUse hook (every turn, both modes): the Foreman's own files, token and port stay off
 * limits (policy.ts foremanPrivateVerdict), also for calls the user's allow rules would let through
 * without asking, and for subagents.
 */
export function foremanGuardHook(
  check: (toolName: string, input: Record<string, unknown>) => Verdict | undefined,
  report: (toolName: string, reason: string, subagent?: string) => void,
): HookCallback {
  return async (input) => {
    if (input.hook_event_name !== 'PreToolUse') return {};
    const toolInput = (input.tool_input && typeof input.tool_input === 'object' ? input.tool_input : {}) as Record<string, unknown>;
    const v = check(input.tool_name, toolInput);
    if (!v || v.action !== 'deny') return {};
    report(input.tool_name, v.reason, input.agent_id);
    return { hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'deny', permissionDecisionReason: v.reason } };
  };
}

/**
 * PreToolUse hook for a lead's turns (every turn, both permission modes): the lead stays read-only.
 * Whatever the policy denies is denied, and a Bash/PowerShell command the policy cannot verify as a
 * read (policy.ts isReadOnlyCommand, plus the user's declared lead read commands) is forced to an
 * in-world ask, so neither auto mode's classifier nor a Claude Code allow rule
 * (claude.permissions.allow, e.g. `Bash(codex exec:*)`) runs it unseen. An approval covers exactly
 * that command (rule key `lead:<command>`, policy.ts). Subagents of a lead are held to the same.
 */
export function leadReadOnlyHook(
  classify: (toolName: string, input: Record<string, unknown>) => Verdict,
  report: (toolName: string, decision: 'deny' | 'ask', reason: string, subagent?: string) => void,
): HookCallback {
  return async (input) => {
    if (input.hook_event_name !== 'PreToolUse') return {};
    const toolInput = (input.tool_input && typeof input.tool_input === 'object' ? input.tool_input : {}) as Record<string, unknown>;
    const v = classify(input.tool_name, toolInput);
    const shell = input.tool_name === 'Bash' || input.tool_name === 'PowerShell';
    if (v.action === 'deny' || (v.action === 'ask' && shell)) {
      report(input.tool_name, v.action, v.reason, input.agent_id);
      return { hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: v.action, permissionDecisionReason: v.reason } };
    }
    return {};
  };
}

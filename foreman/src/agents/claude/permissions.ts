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

/** Policy asks that stay asks in auto mode: git internals and redirections. */
const STRUCTURAL = /git internals \(\.git\)|points git at another repository|--git-dir\/--work-tree|edit through a link that leads outside|GIT_DIR|protected file/i;

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

/**
 * PreToolUse hook (both permission modes, when claude.context.connectors is set): tools of claude.ai
 * connectors the user did not list are refused. Keyed on the server's `source`, not its name.
 */
export function connectorHook(allowed: string[], report: (toolName: string, server: string) => void): HookCallback {
  return async (input) => {
    if (input.hook_event_name !== 'PreToolUse') return {};
    const server = (input as { mcp_server?: { name: string; source: string } }).mcp_server;
    if (!server || server.source !== 'claudeai' || connectorAllowed(server.name, allowed)) return {};
    report(input.tool_name, server.name);
    const reason = `The claude.ai connector "${server.name}" is not enabled for AgentCraft agents (claude.context.connectors).`;
    return { hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'deny', permissionDecisionReason: reason } };
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

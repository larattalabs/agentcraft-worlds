// The team tools (../tools.ts) as an in-process MCP server for the Claude Agent SDK ("agentcraft").
import { createSdkMcpServer, tool, type McpSdkServerConfigWithInstance } from '@anthropic-ai/claude-agent-sdk';
import type { Foreman } from '../../foreman.js';
import type { SessionHistory } from '../../history.js';
import { userName } from '../../user.js';
import { agentTools, TOOL_NAMES, type AgentTool, type ToolHooks, type TurnHandle } from '../tools.js';

export { closeIfNoChanges, type ToolHooks, type TurnHandle } from '../tools.js';

export const MCP_SERVER = 'agentcraft';

export function toolNames(role: 'lead' | 'worker'): string[] {
  return [...TOOL_NAMES.common, ...(role === 'lead' ? TOOL_NAMES.lead : [])].map((n) => `mcp__${MCP_SERVER}__${n}`);
}

/** The team tools as the "agentcraft" MCP server of one turn. */
export function mcpServer(tools: AgentTool[]): McpSdkServerConfigWithInstance {
  const defs = tools.map((t) => tool(t.name, t.description, t.shape, (args) => t.handler(args)));
  // alwaysLoad: never hide our tools behind tool search
  return createSdkMcpServer({ name: MCP_SERVER, version: '0.1.0', tools: defs, alwaysLoad: true, instructions: `AgentCraft team tools: coordinate with teammates, ask ${userName()}, keep memory and the task board up to date.` });
}

export function buildMcpServer(fm: Foreman, agentId: string, role: 'lead' | 'worker', hooks: ToolHooks, turn?: TurnHandle, history?: SessionHistory): McpSdkServerConfigWithInstance {
  return mcpServer(agentTools(fm, agentId, role, hooks, turn, history));
}

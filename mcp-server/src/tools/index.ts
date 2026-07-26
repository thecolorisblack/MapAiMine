import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { Context } from '../context.js';
import { registerInspectTools } from './inspect.js';
import { registerWorldTools } from './world.js';
import { registerBuildTools } from './build.js';
import { registerGenerateTools } from './generate.js';

export function registerAllTools(server: McpServer, ctx: Context): void {
  registerInspectTools(server, ctx);
  registerWorldTools(server, ctx);
  registerBuildTools(server, ctx);
  registerGenerateTools(server, ctx);
}

#!/usr/bin/env node
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';

import { loadConfig, makeLogger } from './config.js';
import { loadContent } from './content/loader.js';
import { Context, toolError, toolText } from './context.js';
import { createBridge, type Bridge } from './transport/index.js';
import { DisconnectedBridge } from './transport/offline.js';
import { registerAllTools } from './tools/index.js';
import { WORKFLOW_GUIDE } from './guide.js';

async function main(): Promise<void> {
  const cfg = loadConfig();
  const log = makeLogger(cfg.debug);

  const library = loadContent([cfg.contentDir, ...cfg.extraContentDirs]);
  log.info(
    `content: ${library.styles.size} styles, ${library.blueprints.size} blueprints, ` +
      `${library.props.size} props from ${library.dirs.join(', ') || '(none)'}`,
  );
  for (const w of library.warnings.slice(0, 10)) log.warn(w);

  let bridge: Bridge;
  let note: string;
  try {
    const connected = await createBridge(cfg, log);
    bridge = connected.bridge;
    note = connected.note;
    log.info(`connected: ${bridge.describe()}`);
  } catch (err) {
    const message = (err as Error).message;
    log.warn(`starting without a Minecraft connection: ${message}`);
    bridge = new DisconnectedBridge(message, (err as { hint?: string }).hint);
    note = 'Minecraft-сервер недоступен — инструменты вернут понятную ошибку, вызови reconnect после запуска сервера.';
  }

  const ctx = new Context(cfg, log, bridge, library, note);

  const server = new McpServer(
    { name: 'mapaimine', version: '0.1.0' },
    {
      instructions:
        'MapAiMine строит миры, спавны, деревни и здания в Minecraft. ' +
        'Порядок работы: mapaimine_status → list_styles → survey_area → build/generate → set_spawn. ' +
        'Подробный регламент — в инструменте mapaimine_guide.',
    },
  );

  registerAllTools(server, ctx);

  server.registerTool(
    'mapaimine_guide',
    {
      title: 'Регламент работы',
      description:
        'Инструкция для агента: как правильно строить в Minecraft через MapAiMine — порядок действий, ' +
        'типовые сценарии (деревня, спавн, зимний вайб), правила безопасности и частые ошибки. ' +
        'Прочитай это, если задача сложнее одного здания.',
      inputSchema: {},
    },
    async () => toolText(WORKFLOW_GUIDE),
  );

  // Mutable transport swap: lets the agent recover without restarting the MCP client.
  server.registerTool(
    'reconnect',
    {
      title: 'Переподключиться',
      description:
        'Переподключиться к Minecraft-серверу и перечитать библиотеку стилей/построек. ' +
        'Вызывай, если сервер был перезапущен или ты добавил свои стили в content/.',
      inputSchema: { reloadContent: z.boolean().optional() },
    },
    async ({ reloadContent }) => {
      try {
        const connected = await createBridge(cfg, log);
        Object.assign(ctx, { bridge: connected.bridge, transportNote: connected.note });
        let extra = '';
        if (reloadContent !== false) {
          const fresh = loadContent([cfg.contentDir, ...cfg.extraContentDirs]);
          ctx.library.styles.clear();
          ctx.library.blueprints.clear();
          ctx.library.props.clear();
          for (const [k, v] of fresh.styles) ctx.library.styles.set(k, v);
          for (const [k, v] of fresh.blueprints) ctx.library.blueprints.set(k, v);
          for (const [k, v] of fresh.props) ctx.library.props.set(k, v);
          ctx.library.warnings.length = 0;
          ctx.library.warnings.push(...fresh.warnings);
          extra = ` Библиотека перечитана: ${fresh.styles.size} стилей, ${fresh.blueprints.size} построек.`;
        }
        return toolText(`Подключено: ${connected.bridge.describe()}. ${connected.note}.${extra}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  const transport = new StdioServerTransport();
  await server.connect(transport);
  log.info('MapAiMine MCP server ready on stdio');
}

main().catch((err) => {
  console.error('[mapaimine][fatal]', err);
  process.exit(1);
});

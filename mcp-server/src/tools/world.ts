import { z } from 'zod';
import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { Context, toolError, toolText } from '../context.js';
import { BridgeError } from '../protocol.js';
import { normalizeWeather } from '../build/flora.js';

export function registerWorldTools(server: McpServer, ctx: Context): void {
  server.registerTool(
    'create_world',
    {
      title: 'Создать мир',
      description:
        'Создать и загрузить новый мир на сервере. worldType="void" даёт пустой холст (идеально для ' +
        'билд-мира или мини-игры), "flat" — суперплоскость. Требует плагин.',
      inputSchema: {
        name: z.string().regex(/^[a-z0-9_-]+$/i).describe('имя мира (папка на сервере)'),
        environment: z.enum(['normal', 'nether', 'the_end']).optional(),
        worldType: z.enum(['normal', 'flat', 'large_biomes', 'amplified', 'void']).optional(),
        seed: z.number().int().optional(),
        generateStructures: z.boolean().optional(),
        flatPreset: z.string().optional().describe('пресет суперплоскости, например "minecraft:bedrock,3*minecraft:stone,minecraft:grass_block;minecraft:plains"'),
        biome: z.string().optional(),
      },
    },
    async (args) => {
      try {
        const w = await ctx.bridge.createWorld(args);
        return toolText(
          `Мир "${w.name}" создан: ${w.environment}, тип ${w.worldType ?? '—'}, seed ${w.seed ?? '—'}, ` +
            `спавн ${w.spawn.join(',')}, Y ${w.minY}..${w.maxY}`,
        );
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'configure_world',
    {
      title: 'Настроить мир',
      description:
        'Время суток, погода, сложность, gamerule-ы, PvP. Для показа построек полезно: time=6000 + timeLock, ' +
        'weather=clear + weatherLock, doMobSpawning=false.',
      inputSchema: {
        world: z.string().optional(),
        time: z.number().int().min(0).max(24000).optional().describe('0=рассвет, 6000=полдень, 18000=полночь'),
        timeLock: z.boolean().optional(),
        weather: z.enum(['clear', 'rain', 'thunder']).optional(),
        weatherLock: z.boolean().optional(),
        difficulty: z.enum(['peaceful', 'easy', 'normal', 'hard']).optional(),
        pvp: z.boolean().optional(),
        spawnRadius: z.number().int().optional(),
        keepLoaded: z.boolean().optional().describe('держать спавн-чанки в памяти (для долгих строек)'),
        gameRules: z.record(z.string(), z.union([z.string(), z.number(), z.boolean()])).optional(),
        applyStyleEnvironment: z.string().optional().describe('id стиля — применить его время/погоду'),
      },
    },
    async ({ world, applyStyleEnvironment, ...rest }) => {
      try {
        const w = ctx.resolveWorld(world);
        const payload = { ...rest };
        if (applyStyleEnvironment) {
          const env = ctx.style(applyStyleEnvironment).environment ?? {};
          if (env.time !== undefined && payload.time === undefined) payload.time = env.time;
          if (env.timeLock !== undefined && payload.timeLock === undefined) payload.timeLock = env.timeLock;
          if (env.weather && payload.weather === undefined) payload.weather = normalizeWeather(env.weather);
        }
        await ctx.bridge.worldSettings(w, payload);
        return toolText(`Мир ${w} настроен: ${JSON.stringify(payload)}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'set_spawn',
    {
      title: 'Поставить спавн',
      description:
        'Задать точку спавна мира. safe=true (по умолчанию) поднимет точку на поверхность и подложит платформу, ' +
        'чтобы игроки не падали в пустоту.',
      inputSchema: {
        world: z.string().optional(),
        x: z.number().int(),
        y: z.number().int().optional().describe('если не задан — определится по рельефу'),
        z: z.number().int(),
        yaw: z.number().optional(),
        pitch: z.number().optional(),
        safe: z.boolean().optional(),
      },
    },
    async ({ world, x, y, z, yaw, pitch, safe }) => {
      try {
        const w = ctx.resolveWorld(world);
        const finalY = y ?? (await ctx.groundHeight(w, x, z)) + 1;
        await ctx.bridge.setSpawn(w, { x, y: finalY, z, yaw, pitch, safe: safe ?? true });
        return toolText(`Спавн мира ${w} → ${x}, ${finalY}, ${z}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'set_world_border',
    {
      title: 'Границы мира',
      description: 'Задать центр и размер world border — удобно, чтобы запереть игроков в построенной зоне.',
      inputSchema: {
        world: z.string().optional(),
        centerX: z.number().int(),
        centerZ: z.number().int(),
        size: z.number().positive().describe('диаметр в блоках'),
        warningDistance: z.number().int().optional(),
      },
    },
    async ({ world, centerX, centerZ, size, warningDistance }) => {
      try {
        const w = ctx.resolveWorld(world);
        await ctx.bridge.setBorder(w, { center: [centerX, centerZ], size, warningDistance });
        return toolText(`Граница мира ${w}: центр ${centerX},${centerZ}, размер ${size}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'undo_last',
    {
      title: 'Отменить постройку',
      description:
        'Откатить последнюю (или указанную) операцию постройки. Это главная страховка: если результат не понравился, ' +
        'откатывай и пробуй другой стиль/место, а не чини вручную. Требует плагин.',
      inputSchema: { undoId: z.string().optional().describe('id из отчёта о постройке; по умолчанию — последняя') },
    },
    async ({ undoId }) => {
      try {
        const res = await ctx.bridge.undo(undoId);
        return toolText(`Откачено, восстановлено блоков: ${res.blocksRestored.toLocaleString()}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'list_undo',
    {
      title: 'История отмены',
      description: 'Показать список доступных для отката операций.',
      inputSchema: {},
    },
    async () => {
      try {
        const entries = await ctx.bridge.undoList();
        return toolText(
          entries.length
            ? entries
                .map((e) => `${e.undoId} — ${e.label ?? '—'} (${e.blocks.toLocaleString()} блоков, ${new Date(e.at).toISOString()})`)
                .join('\n')
            : 'История пуста.',
        );
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'job_status',
    {
      title: 'Статус задачи',
      description:
        'Прогресс фоновой стройки. Большие постройки выполняются по тик-бюджету, чтобы сервер не лагал — ' +
        'этим инструментом можно следить за процентом готовности.',
      inputSchema: { jobId: z.string().optional().describe('без id — покажет все активные') },
    },
    async ({ jobId }) => {
      try {
        if (jobId) {
          const j = await ctx.bridge.job(jobId);
          return toolText(
            `${j.jobId} [${j.status}] ${j.label ?? ''}\n` +
              `операций ${j.progress?.opsDone ?? 0}/${j.progress?.opsTotal ?? 0}, ` +
              `блоков ${j.progress?.blocksChanged?.toLocaleString() ?? j.blocksChanged?.toLocaleString() ?? 0}, ` +
              `${j.progress?.percent?.toFixed(1) ?? '—'}%` +
              (j.progress?.checkpoint ? `\nэтап: ${j.progress.checkpoint}` : '') +
              (j.error ? `\nошибка: ${j.error}` : '') +
              (j.warnings?.length ? `\nпредупреждения:\n- ${j.warnings.slice(0, 10).join('\n- ')}` : ''),
          );
        }
        const jobs = await ctx.bridge.jobs();
        return toolText(
          jobs.length
            ? jobs.map((j) => `${j.jobId} [${j.status}] ${j.progress?.percent?.toFixed(0) ?? '—'}% ${j.label ?? ''}`).join('\n')
            : 'Активных задач нет.',
        );
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'cancel_job',
    {
      title: 'Отменить задачу',
      description: 'Остановить выполняющуюся стройку. Уже поставленные блоки останутся — используй undo_last, чтобы убрать их.',
      inputSchema: { jobId: z.string() },
    },
    async ({ jobId }) => {
      try {
        await ctx.bridge.cancelJob(jobId);
        return toolText(`Задача ${jobId} отменена.`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'capture_region',
    {
      title: 'Снять постройку в файл',
      description:
        'Сохранить существующую постройку игрока как структуру, чтобы потом копировать её в другие места или миры. ' +
        'Требует плагин.',
      inputSchema: {
        world: z.string().optional(),
        from: z.tuple([z.number().int(), z.number().int(), z.number().int()]),
        to: z.tuple([z.number().int(), z.number().int(), z.number().int()]),
        name: z.string().regex(/^[a-z0-9_-]+$/i),
        trimAir: z.boolean().optional(),
      },
    },
    async ({ world, from, to, name, trimAir }) => {
      try {
        const res = await ctx.bridge.capture({
          world: ctx.resolveWorld(world),
          from: from as [number, number, number],
          to: to as [number, number, number],
          name,
          trimAir: trimAir ?? true,
        });
        return toolText(
          `Сохранено "${res.id}": размер ${res.size.join('×')}, палитра ${res.palette.length} блоков. ` +
            `Ставить через build_structure.`,
        );
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'teleport_player',
    {
      title: 'Телепорт игрока',
      description: 'Перенести игрока к постройке — удобно показать результат сразу после стройки.',
      inputSchema: {
        player: z.string(),
        world: z.string().optional(),
        x: z.number(), y: z.number().optional(), z: z.number(),
        yaw: z.number().optional(), pitch: z.number().optional(),
      },
    },
    async ({ player, world, x, y, z, yaw, pitch }) => {
      try {
        const w = ctx.resolveWorld(world);
        const finalY = y ?? (await ctx.groundHeight(w, Math.floor(x), Math.floor(z))) + 1;
        await ctx.bridge.teleport(player, { world: w, x, y: finalY, z, yaw, pitch });
        return toolText(`${player} → ${w} ${x}, ${finalY}, ${z}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'broadcast_message',
    {
      title: 'Сообщение в чат',
      description: 'Отправить сообщение всем игрокам (например, «деревня готова, /warp village»).',
      inputSchema: { message: z.string().max(400) },
    },
    async ({ message }) => {
      try {
        await ctx.bridge.broadcast(message);
        return toolText('Отправлено.');
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'run_command',
    {
      title: 'Выполнить команду',
      description:
        'Запасной выход: выполнить произвольную консольную команду сервера. Используй только когда специализированного ' +
        'инструмента нет — команды не откатываются через undo_last.',
      inputSchema: {
        world: z.string().optional(),
        command: z.string().describe('без ведущего слэша, например "gamerule keepInventory true"'),
      },
    },
    async ({ world, command }) => {
      try {
        if (ctx.cfg.readOnly) throw new BridgeError('READ_ONLY', 'Режим только для чтения.');
        const res = await ctx.bridge.ops({
          world: ctx.resolveWorld(world),
          label: `command: ${command.slice(0, 40)}`,
          ops: [{ type: 'command', command }],
          undo: false,
          async: false,
        });
        return toolText(`Выполнено (job ${res.jobId}).${res.warnings?.length ? `\n${res.warnings.join('\n')}` : ''}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );
}

import { z } from 'zod';
import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { Context, toolError, toolText } from '../context.js';
import { textOf } from '../content/types.js';
import { REQUIRED_ROLES } from '../content/types.js';

/** Compact ASCII rendering of a heightmap — an LLM reads this far better than a number grid. */
export function renderHeightmap(heights: number[][], water?: number[][]): string {
  const flat = heights.flat().filter((n) => Number.isFinite(n));
  if (!flat.length) return '(no data)';
  const min = Math.min(...flat), max = Math.max(...flat);
  const ramp = '▁▂▃▄▅▆▇█';
  const lines = heights.map((row, r) =>
    row
      .map((h, c) => {
        if (water?.[r]?.[c]) return '~';
        if (max === min) return ramp[0];
        const idx = Math.min(ramp.length - 1, Math.floor(((h - min) / (max - min)) * ramp.length));
        return ramp[idx];
      })
      .join(''),
  );
  return `${lines.join('\n')}\n(высоты ${min}..${max}; «~» — вода; ряды идут по Z, символы по X)`;
}

function topEntries(hist: Record<string, number> | undefined, n = 6): string {
  if (!hist) return '—';
  return Object.entries(hist)
    .sort((a, b) => b[1] - a[1])
    .slice(0, n)
    .map(([k, v]) => `${k}×${v}`)
    .join(', ');
}

export function registerInspectTools(server: McpServer, ctx: Context): void {
  server.registerTool(
    'mapaimine_status',
    {
      title: 'Состояние MapAiMine',
      description:
        'Проверить связь с Minecraft-сервером и узнать возможности. ВСЕГДА вызывай это первым в сессии: ' +
        'вернёт транспорт (плагин или RCON), версию сервера, список миров, лимиты, доступные стили и блюпринты. ' +
        'От транспорта зависит, какие инструменты вообще работают.',
      inputSchema: {},
    },
    async () => {
      try {
        const health = ctx.bridge.health();
        const lines: string[] = [];
        lines.push(`Транспорт: ${ctx.bridge.describe()}`);
        lines.push(`Заметка: ${ctx.transportNote}`);
        if (health) {
          lines.push(`Сервер: ${health.server} ${health.minecraftVersion}, плагин: ${health.plugin}`);
          lines.push(`Возможности: ${health.capabilities.join(', ') || '—'}`);
          lines.push(
            `Лимиты: ${health.limits.blocksPerTick} блоков/тик, регион ≤ ${health.limits.maxRegionVolume.toLocaleString()}, ` +
              `история отмены ${health.limits.maxUndoHistory}`,
          );
        }
        try {
          const worlds = await ctx.bridge.worlds();
          lines.push(
            `Миры (${worlds.length}): ` +
              worlds
                .map((w) => `${w.name} [${w.environment}] спавн ${w.spawn.join(',')} Y ${w.minY}..${w.maxY}`)
                .join(' | '),
          );
        } catch {
          lines.push('Миры: недоступны на этом транспорте (нужен плагин)');
        }
        lines.push(`Мир по умолчанию: ${ctx.resolveWorld()}`);
        lines.push(
          `Библиотека: стилей ${ctx.library.styles.size}, построек ${ctx.library.blueprints.size}, ` +
            `пропов ${ctx.library.props.size} (${ctx.library.dirs.join(', ') || 'нет каталогов'})`,
        );
        lines.push(`Стили: ${[...ctx.library.styles.keys()].join(', ')}`);
        if (ctx.library.warnings.length) {
          lines.push(`Проблемы контента:\n- ${ctx.library.warnings.slice(0, 10).join('\n- ')}`);
        }
        if (ctx.bridge.kind === 'rcon') {
          lines.push(
            'ВНИМАНИЕ: режим RCON. Недоступны: survey/probe, undo, создание миров, paint/scatter/smooth/flatten, capture. ' +
              'Ставь постройки только по явным координатам Y.',
          );
        }
        return toolText(lines.join('\n'));
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'list_styles',
    {
      title: 'Список стилей',
      description:
        'Показать доступные стили (палитры): средневековье, зима, весна, лето, осень, японский, пустыня и т.д. ' +
        'Используй, чтобы выбрать вайб постройки под запрос пользователя.',
      inputSchema: {
        season: z.enum(['summer', 'autumn', 'winter', 'spring', 'none']).optional()
          .describe('фильтр по сезону'),
        tag: z.string().optional().describe('фильтр по тегу, например "medieval"'),
      },
    },
    async ({ season, tag }) => {
      const rows = [...ctx.library.styles.values()]
        .filter((s) => (!season || s.season === season) && (!tag || s.tags?.includes(tag)))
        .map((s) => {
          const roles = Object.keys(s.materials ?? {}).length;
          return (
            `• ${s.id} — ${textOf(s.name, ctx.lang)} [${s.season ?? 'none'}] ` +
            `${s.tags?.join('/') ?? ''}\n  ${textOf(s.description, ctx.lang)}\n` +
            `  ролей: ${roles}, крыша: ${s.building?.roofShape ?? '—'}, стены: ${s.building?.wallStyle ?? '—'}`
          );
        });
      return toolText(rows.length ? rows.join('\n') : 'Стили не найдены.');
    },
  );

  server.registerTool(
    'get_style',
    {
      title: 'Детали стиля',
      description:
        'Полное описание стиля: палитра по ролям, окружение, растительность, настройки поселения. ' +
        'Полезно, если нужно понять, из чего будет построено, или переопределить отдельные роли.',
      inputSchema: { id: z.string().describe('id стиля, например "medieval"') },
    },
    async ({ id }) => {
      try {
        const s = ctx.style(id);
        const mats = Object.entries(s.materials ?? {})
          .map(([role, m]) => {
            const full = (m?.full ?? []).map((b) => `${b.block}${b.weight ? `×${b.weight}` : ''}`).join(' | ');
            const variants = Object.keys(m ?? {}).filter((k) => k !== 'full');
            return `  ${role}: ${full}${variants.length ? `  [${variants.join(', ')}]` : ''}`;
          })
          .join('\n');
        const missing = REQUIRED_ROLES.filter((r) => !s.materials?.[r]);
        return toolText(
          [
            `${s.id} — ${textOf(s.name, ctx.lang)}`,
            textOf(s.description, ctx.lang),
            `сезон: ${s.season ?? 'none'}, теги: ${s.tags?.join(', ') ?? '—'}`,
            '',
            'Палитра:',
            mats,
            '',
            `Окружение: ${JSON.stringify(s.environment ?? {})}`,
            `Земля: ${JSON.stringify(s.ground ?? {})}`,
            `Постройки: ${JSON.stringify(s.building ?? {})}`,
            `Поселение: ${JSON.stringify(s.settlement ?? {})}`,
            `Флора: деревья ${s.flora?.trees?.length ?? 0}, трава ${s.flora?.ground?.length ?? 0}`,
            missing.length ? `⚠ отсутствуют обязательные роли: ${missing.join(', ')}` : '',
          ]
            .filter(Boolean)
            .join('\n'),
        );
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'list_blueprints',
    {
      title: 'Список построек',
      description:
        'Каталог готовых построек (дома, таверны, кузницы, башни, стены, мосты, фонтаны...). ' +
        'Постройки не привязаны к блокам — они рисуются любым стилем.',
      inputSchema: {
        category: z.string().optional().describe('house, tavern, farm, tower, wall, bridge...'),
        maxWidth: z.number().int().optional().describe('максимальная ширина по X'),
        maxDepth: z.number().int().optional().describe('максимальная глубина по Z'),
        includeProps: z.boolean().optional().describe('включить мелкие пропы (мебель)'),
      },
    },
    async ({ category, maxWidth, maxDepth, includeProps }) => {
      const source = includeProps
        ? [...ctx.library.blueprints.values(), ...ctx.library.props.values()]
        : [...ctx.library.blueprints.values()];
      const rows = source
        .filter((b) => !category || b.category === category)
        .filter((b) => !maxWidth || b.size[0] <= maxWidth)
        .filter((b) => !maxDepth || b.size[2] <= maxDepth)
        .sort((a, b) => a.category.localeCompare(b.category) || a.id.localeCompare(b.id))
        .map(
          (b) =>
            `• ${b.id} [${b.category}] ${b.size.join('×')} — ${textOf(b.name, ctx.lang)}` +
            (b.styleHints?.length ? ` (стили: ${b.styleHints.join('/')})` : ''),
        );
      return toolText(rows.length ? `${rows.length} построек:\n${rows.join('\n')}` : 'Ничего не найдено.');
    },
  );

  server.registerTool(
    'get_blueprint',
    {
      title: 'Детали постройки',
      description:
        'Метаданные постройки и предпросмотр слоёв в ASCII. Используй, чтобы понять размеры и как она будет ' +
        'вписана в участок до строительства.',
      inputSchema: {
        id: z.string(),
        showLayers: z.boolean().optional().describe('печатать ASCII-слои целиком'),
      },
    },
    async ({ id, showLayers }) => {
      try {
        const bp = ctx.blueprint(id);
        const legend = Object.entries(bp.legend)
          .map(([ch, cell]) => `  "${ch}" = ${cell.skip ? 'не трогать' : cell.block ?? cell.prop ?? `${cell.role}${cell.variant ? `/${cell.variant}` : ''}${cell.facing ? `→${cell.facing}` : ''}`}`)
          .join('\n');
        const layers = showLayers
          ? bp.layers
              .map((l) => `y=${l.y}\n${l.rows.map((r) => `  ${r}`).join('\n')}`)
              .join('\n')
          : bp.layers
              .slice(0, 2)
              .map((l) => `y=${l.y}\n${l.rows.map((r) => `  ${r}`).join('\n')}`)
              .join('\n');
        return toolText(
          [
            `${bp.id} — ${textOf(bp.name, ctx.lang)}`,
            `категория: ${bp.category}, размер ${bp.size.join('×')} (X×Y×Z), фасад: ${bp.facing ?? 'north'}`,
            `уровень земли: слой y=${bp.groundLevel ?? 0}, площадь: ${(bp.footprint ?? [bp.size[0], bp.size[2]]).join('×')}`,
            `стили: ${bp.styleHints?.join(', ') ?? 'любые'}`,
            `нужны роли: ${bp.minStyleRoles?.join(', ') ?? '—'}`,
            '',
            'Легенда:',
            legend,
            '',
            showLayers ? 'Слои:' : 'Первые слои (showLayers=true — все):',
            layers,
          ].join('\n'),
        );
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'survey_area',
    {
      title: 'Разведать участок',
      description:
        'ГЛАЗА агента. Считывает рельеф участка: карта высот в ASCII, поверхностные блоки, биомы, вода, ' +
        'ровность, рекомендуемая высота застройки. Вызывай ПЕРЕД любой стройкой, чтобы выбрать место и Y. ' +
        'Требует плагин (в RCON недоступно).',
      inputSchema: {
        world: z.string().optional(),
        x1: z.number().int(), z1: z.number().int(),
        x2: z.number().int(), z2: z.number().int(),
        step: z.number().int().min(1).optional().describe('шаг сетки; по умолчанию подбирается сам'),
        include: z.array(z.enum(['height', 'surface', 'biome', 'water', 'light'])).optional(),
        showGrid: z.boolean().optional().describe('печатать ASCII-карту высот (по умолчанию да)'),
      },
    },
    async ({ world, x1, z1, x2, z2, step, include, showGrid }) => {
      try {
        const w = ctx.resolveWorld(world);
        const survey = await ctx.bridge.survey({
          world: w, x1, z1, x2, z2, step,
          include: include ?? ['height', 'surface', 'biome', 'water'],
        });
        const s = survey.stats;
        const out = [
          `Участок ${w} (${survey.origin.join(',')}) размер ${survey.size.join('×')}, шаг ${survey.step}, сетка ${survey.cols}×${survey.rows}`,
          `Высоты: ${s.minY}..${s.maxY}, средняя ${s.avgY.toFixed(1)}, макс. перепад на клетку ${s.slopeMax}`,
          `Ровность: ${(s.flatnessScore * 100).toFixed(0)}% · вода ${(s.waterFraction * 100).toFixed(0)}%`,
          `РЕКОМЕНДУЕМАЯ высота застройки Y=${s.suggestedBuildY}`,
          `Поверхность: ${topEntries(s.surfaceHistogram)}`,
          `Биомы: ${topEntries(s.biomeHistogram)}`,
        ];
        if (showGrid !== false && survey.heightmap) {
          out.push('', renderHeightmap(survey.heightmap, survey.water));
        }
        if (s.flatnessScore < 0.35) {
          out.push('', '⚠ Участок неровный: либо выровняй его (terraform flatten), либо ставь постройки по отдельности с prepare.');
        }
        return toolText(out.join('\n'));
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'probe_blocks',
    {
      title: 'Прочитать блоки',
      description: 'Узнать, какие блоки стоят в конкретных точках (до 1024). Требует плагин.',
      inputSchema: {
        world: z.string().optional(),
        points: z.array(z.tuple([z.number().int(), z.number().int(), z.number().int()])).max(1024),
      },
    },
    async ({ world, points }) => {
      try {
        const res = await ctx.bridge.probe(ctx.resolveWorld(world), points as [number, number, number][]);
        return toolText(res.blocks.map((b) => `${b.pos.join(',')} → ${b.block}${b.biome ? ` (${b.biome})` : ''}`).join('\n'));
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'list_materials',
    {
      title: 'Список блоков сервера',
      description:
        'Реальный список id блоков этой версии сервера. Используй, если сомневаешься в названии блока ' +
        'вместо того, чтобы гадать.',
      inputSchema: { filter: z.string().optional().describe('подстрока, например "copper"') },
    },
    async ({ filter }) => {
      try {
        const blocks = await ctx.bridge.materials(filter);
        const shown = blocks.slice(0, 400);
        return toolText(
          `${blocks.length} блоков${filter ? ` по фильтру "${filter}"` : ''}:\n${shown.join(', ')}` +
            (blocks.length > shown.length ? `\n… ещё ${blocks.length - shown.length}` : ''),
        );
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'validate_blocks',
    {
      title: 'Проверить блоки',
      description:
        'Проверить, существуют ли указанные block id / block state на сервере, и получить подсказки по опечаткам. ' +
        'Дешевле, чем построить дом из несуществующих блоков и получить ошибку.',
      inputSchema: { blocks: z.array(z.string()).max(256) },
    },
    async ({ blocks }) => {
      try {
        const res = await ctx.bridge.validateBlocks(blocks);
        return toolText(
          res.results
            .map((r) =>
              r.valid
                ? `✔ ${r.input} → ${r.normalized ?? r.input}`
                : `✘ ${r.input}${r.suggestions?.length ? ` — возможно: ${r.suggestions.join(', ')}` : ''}`,
            )
            .join('\n'),
        );
      } catch (err) {
        return toolError(err);
      }
    },
  );
}

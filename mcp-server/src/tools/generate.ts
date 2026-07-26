import { z } from 'zod';
import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { Context, toolError, toolText } from '../context.js';
import type { Op, Pos } from '../protocol.js';
import { placeBlueprint, rotatedFootprint, type Rotation } from '../build/blueprint.js';
import { generateSettlement } from '../build/settlement.js';
import { decodeRle } from '../build/raster.js';
import { toScatterEntries } from '../build/flora.js';
import { parseBlock, rotateAxis, rotateFacing, withStates, type Facing } from '../build/blockstate.js';
import { textOf } from '../content/types.js';

const rotationSchema = z.union([z.literal(0), z.literal(90), z.literal(180), z.literal(270)]);

export function registerGenerateTools(server: McpServer, ctx: Context): void {
  server.registerTool(
    'build_blueprint',
    {
      title: 'Построить здание',
      description:
        'Поставить готовую постройку из каталога в выбранном стиле. Это основной инструмент строительства. ' +
        'y — высота ПОВЕРХНОСТИ земли (верхний блок), фундамент дотянется вниз сам; если y не указать, ' +
        'он определится по рельефу. rotation поворачивает здание, фасад по умолчанию смотрит на север.',
      inputSchema: {
        world: z.string().optional(),
        blueprint: z.string().describe('id из list_blueprints'),
        style: z.string().describe('id из list_styles'),
        x: z.number().int().describe('X минимального угла постройки'),
        z: z.number().int().describe('Z минимального угла постройки'),
        y: z.number().int().optional().describe('Y поверхности земли; по умолчанию определяется автоматически'),
        rotation: rotationSchema.optional(),
        mirror: z.enum(['x', 'z']).optional(),
        seed: z.number().int().optional().describe('тот же seed → тот же результат'),
        paletteOverrides: z.record(z.string(), z.string()).optional()
          .describe('переопределить роли, например {"roof_primary":"minecraft:deepslate_tiles"}'),
        prepare: z.boolean().optional().describe('готовить площадку: выровнять, расчистить, достроить фундамент (по умолчанию да)'),
        settlementName: z.string().optional().describe('подставляется в тексты табличек вместо %settlement%'),
        dryRun: z.boolean().optional(),
      },
    },
    async (a) => {
      try {
        const bp = ctx.blueprint(a.blueprint);
        const style = ctx.style(a.style);
        const world = ctx.resolveWorld(a.world);
        const rotation = (a.rotation ?? 0) as Rotation;

        const groundY = a.y ?? (await ctx.groundHeight(world, a.x, a.z));
        const origin: Pos = [a.x, groundY + 1 - (bp.groundLevel ?? 0), a.z];

        const placed = placeBlueprint(bp, {
          origin,
          rotation,
          mirror: a.mirror ?? null,
          style,
          props: ctx.library.props,
          seed: a.seed ?? `${bp.id}:${a.x},${a.z}`,
          paletteOverrides: a.paletteOverrides,
          lang: ctx.lang,
          skipPrepare: a.prepare === false,
          signVars: { settlement: a.settlementName ?? '' },
        });

        const res = await ctx.submit({
          world,
          label: `${bp.id} (${style.id}) @ ${a.x},${a.z}`,
          ops: placed.ops,
          physicsOps: placed.physicsOps,
          dryRun: a.dryRun,
        });

        const [fw, fd] = rotatedFootprint(bp, rotation);
        return toolText(
          [
            `Построено: ${textOf(bp.name, ctx.lang)} (${bp.id}) в стиле ${ctx.styleLabel(style)}`,
            `позиция ${a.x},${groundY},${a.z}, поворот ${rotation}°, площадь ${fw}×${fd}, блоков ${placed.blockCount}`,
            res.report,
            placed.warnings.length ? `предупреждения:\n- ${[...new Set(placed.warnings)].slice(0, 10).join('\n- ')}` : '',
          ].filter(Boolean).join('\n'),
        );
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'generate_settlement',
    {
      title: 'Сгенерировать поселение',
      description:
        'ГЛАВНАЯ ФИЧА: целиком сгенерировать деревню, город, замок, лагерь, ферму или порт — с площадью, ' +
        'сеткой улиц, домами по фасадам, фонарями, стеной с воротами и озеленением. Всё в выбранном стиле ' +
        '(средневековье / зима / весна / лето / осень / япония / пустыня …). Один seed → один и тот же результат. ' +
        'Перед вызовом полезно сделать survey_area, чтобы выбрать ровное место.',
      inputSchema: {
        world: z.string().optional(),
        centerX: z.number().int(),
        centerZ: z.number().int(),
        size: z.number().int().min(32).max(400).describe('сторона квадрата в блоках, типично 96-160'),
        style: z.string(),
        kind: z.enum(['village', 'town', 'castle', 'camp', 'farmstead', 'harbor']).optional(),
        name: z.string().optional().describe('название поселения — попадёт на таблички'),
        y: z.number().int().optional().describe('высота земли; по умолчанию берётся из разведки'),
        seed: z.number().int().optional(),
        wall: z.boolean().optional(),
        density: z.number().min(0.1).max(1).optional(),
        flattenSite: z.enum(['full', 'plots', 'none']).optional(),
        only: z.array(z.string()).optional().describe('ограничить набор построек этими id'),
        dryRun: z.boolean().optional(),
      },
    },
    async (a) => {
      try {
        const style = ctx.style(a.style);
        const world = ctx.resolveWorld(a.world);

        let baseY = a.y;
        if (baseY === undefined) {
          const half = Math.floor(a.size / 2);
          const survey = await ctx.bridge.survey({
            world,
            x1: a.centerX - half, z1: a.centerZ - half,
            x2: a.centerX + half, z2: a.centerZ + half,
            include: ['height'],
          });
          baseY = survey.stats.suggestedBuildY;
        }

        const result = generateSettlement({
          center: [a.centerX, a.centerZ],
          size: a.size,
          baseY,
          style,
          library: ctx.library,
          kind: a.kind ?? 'village',
          seed: a.seed ?? Math.floor(Math.abs(a.centerX * 31 + a.centerZ * 17)),
          name: a.name,
          lang: ctx.lang,
          flattenSite: a.flattenSite ?? 'full',
          wall: a.wall,
          density: a.density,
          only: a.only,
        });

        const res = await ctx.submit({
          world,
          label: `settlement ${a.kind ?? 'village'} "${a.name ?? ''}" (${style.id})`,
          ops: result.ops,
          physicsOps: result.physicsOps,
          dryRun: a.dryRun,
        });

        const list = result.buildings
          .map((b) => `  ${b.blueprint} [${b.category}] @ ${b.pos[0]},${b.pos[2]} ↻${b.rotation}`)
          .join('\n');

        return toolText(
          [
            result.summary,
            `стиль: ${ctx.styleLabel(style)}, земля Y=${baseY}, площадь ${result.bounds.x1},${result.bounds.z1} … ${result.bounds.x2},${result.bounds.z2}`,
            `площадь-плаза: ${result.plaza.x1},${result.plaza.z1} … ${result.plaza.x2},${result.plaza.z2}`,
            '',
            'Постройки:',
            list || '  (нет — проверь, что в библиотеке есть блюпринты под этот стиль)',
            '',
            res.report,
            result.warnings.length
              ? `предупреждения:\n- ${[...new Set(result.warnings)].slice(0, 12).join('\n- ')}`
              : '',
            '',
            `Дальше: set_spawn на площадь (${a.centerX}, ${baseY + 1}, ${a.centerZ}), ` +
              `configure_world с applyStyleEnvironment="${style.id}", teleport_player чтобы посмотреть.`,
          ].filter(Boolean).join('\n'),
        );
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'decorate_area',
    {
      title: 'Оформить участок',
      description:
        'Одним вызовом навести «вайб» стиля на участок: покрасить землю, рассыпать траву и цветы, посадить деревья, ' +
        'камни, при зимнем стиле — снег. Хорошо работает вокруг готовых построек.',
      inputSchema: {
        world: z.string().optional(),
        from: z.tuple([z.number().int(), z.number().int()]).describe('[x, z]'),
        to: z.tuple([z.number().int(), z.number().int()]).describe('[x, z]'),
        style: z.string(),
        paintGround: z.boolean().optional(),
        trees: z.boolean().optional(),
        plants: z.boolean().optional(),
        rocks: z.boolean().optional(),
        intensity: z.number().min(0.1).max(3).optional().describe('множитель плотности, по умолчанию 1'),
        seed: z.number().int().optional(),
        dryRun: z.boolean().optional(),
      },
    },
    async (a) => {
      try {
        const style = ctx.style(a.style);
        const k = a.intensity ?? 1;
        const from: Pos = [a.from[0], 0, a.from[1]];
        const to: Pos = [a.to[0], 0, a.to[1]];
        const ops: Op[] = [];
        const onlyOnGround = (style.ground?.top ?? []).map((b) => b.block);
        let seed = a.seed ?? 4242;

        if (a.paintGround !== false && style.ground?.top?.length) {
          ops.push({
            type: 'paint', from, to,
            block: { choices: style.ground.top.map((b) => ({ block: b.block, weight: b.weight })) },
            depth: 1, seed: seed++,
          });
        }
        if (a.plants !== false && style.flora?.ground?.length) {
          ops.push({
            type: 'scatter', from, to,
            density: (style.flora.groundDensity ?? 0.1) * k,
            seed: seed++,
            entries: toScatterEntries(style.flora.ground),
            onlyOn: onlyOnGround, needsAir: true, avoidWater: true,
          });
        }
        if (a.trees !== false && style.flora?.trees?.length) {
          ops.push({
            type: 'scatter', from, to,
            density: (style.flora.treeDensity ?? 0.02) * k,
            seed: seed++, minSpacing: 4,
            entries: toScatterEntries(style.flora.trees),
            onlyOn: onlyOnGround, needsAir: true, avoidWater: true,
          });
        }
        if (a.rocks !== false && style.flora?.rocks?.length) {
          ops.push({
            type: 'scatter', from, to,
            density: (style.flora.rockDensity ?? 0.004) * k,
            seed: seed++, minSpacing: 6,
            entries: toScatterEntries(style.flora.rocks),
            onlyOn: onlyOnGround, needsAir: true, avoidWater: true,
          });
        }
        if (style.environment?.snowLayer) {
          ops.push({
            type: 'scatter', from, to, density: 0.9, seed: seed++,
            entries: [{ block: 'minecraft:snow[layers=1]', weight: 7 }, { block: 'minecraft:snow[layers=2]', weight: 3 }],
            needsAir: true, avoidWater: true,
          });
        }
        if (!ops.length) throw new Error(`в стиле ${style.id} нет данных для оформления`);

        const res = await ctx.submit({ world: a.world, label: `decorate (${style.id})`, ops, dryRun: a.dryRun });
        return toolText(`Участок оформлен в стиле ${ctx.styleLabel(style)}.\n${res.report}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'build_showcase',
    {
      title: 'Витрина стилей',
      description:
        'Построить рядом одну и ту же постройку в нескольких стилях, каждую с табличкой. Лучший способ дать ' +
        'пользователю выбрать вайб глазами, а не по описанию. Строит на выровненной площадке в линию по X.',
      inputSchema: {
        world: z.string().optional(),
        x: z.number().int(), z: z.number().int(),
        y: z.number().int().optional(),
        styles: z.array(z.string()).min(1).max(12).optional().describe('по умолчанию — все стили библиотеки'),
        blueprint: z.string().optional().describe('что строить, по умолчанию house_small'),
        spacing: z.number().int().optional().describe('промежуток между вариантами, по умолчанию 6'),
        dryRun: z.boolean().optional(),
      },
    },
    async (a) => {
      try {
        const world = ctx.resolveWorld(a.world);
        const bpId = a.blueprint ?? 'house_small';
        const bp = ctx.blueprint(bpId);
        const styles = (a.styles ?? [...ctx.library.styles.keys()]).map((id) => ctx.style(id));
        const spacing = a.spacing ?? 6;
        const groundY = a.y ?? (await ctx.groundHeight(world, a.x, a.z));

        const [w, d] = rotatedFootprint(bp, 0);
        const totalWidth = styles.length * (w + spacing);
        const ops: Op[] = [
          {
            type: 'flatten',
            from: [a.x - 2, groundY, a.z - 2],
            to: [a.x + totalWidth + 2, groundY, a.z + d + 4],
            y: groundY,
            surface: 'minecraft:grass_block',
            fill: 'minecraft:dirt',
            clearAbove: bp.size[1] + 6,
          },
        ];
        const physicsOps: Op[] = [];
        const notes: string[] = [];

        styles.forEach((style, i) => {
          const bx = a.x + i * (w + spacing);
          const placed = placeBlueprint(bp, {
            origin: [bx, groundY + 1 - (bp.groundLevel ?? 0), a.z],
            rotation: 0,
            style,
            props: ctx.library.props,
            seed: 1000 + i,
            lang: ctx.lang,
          });
          ops.push(...placed.ops);
          physicsOps.push(...placed.physicsOps);
          ops.push({
            type: 'sign',
            pos: [bx + Math.floor(w / 2), groundY + 1, a.z + d + 2],
            block: 'minecraft:oak_sign',
            front: [textOf(style.name, ctx.lang).slice(0, 15), style.id.slice(0, 15), style.season ?? ''],
          });
          notes.push(`  ${style.id} @ x=${bx}`);
        });

        const res = await ctx.submit({
          world, label: `showcase ${bpId}`, ops, physicsOps, dryRun: a.dryRun,
        });
        return toolText(
          [`Витрина «${bpId}» в ${styles.length} стилях:`, ...notes, '', res.report,
           `Подойди/телепортируйся: ${a.x}, ${groundY + 1}, ${a.z - 6}`].join('\n'),
        );
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'build_structure',
    {
      title: 'Поставить сохранённую структуру',
      description:
        'Поставить структуру, ранее снятую через capture_region (постройка игрока, эталонный дом и т.п.). ' +
        'Блоки ставятся один в один; поворот поддерживается.',
      inputSchema: {
        world: z.string().optional(),
        name: z.string(),
        x: z.number().int(), y: z.number().int(), z: z.number().int(),
        rotation: rotationSchema.optional(),
        skipAir: z.boolean().optional().describe('не затирать существующие блоки воздухом структуры'),
        dryRun: z.boolean().optional(),
      },
    },
    async (a) => {
      try {
        const structure = await ctx.bridge.getCapture(a.name);
        const rotation = (a.rotation ?? 0) as Rotation;
        const [sx, sy, sz] = structure.size;
        const origin: Pos = [a.x, a.y, a.z];

        let op: Op;
        if (rotation === 0 && !a.skipAir) {
          op = { type: 'blocks', origin, palette: structure.palette, size: structure.size, data: structure.data };
        } else {
          const idx = decodeRle(structure.data, sx * sy * sz);
          const blocks: Array<{ pos: Pos; block: string }> = [];
          let i = 0;
          for (let y = 0; y < sy; y++) {
            for (let z = 0; z < sz; z++) {
              for (let x = 0; x < sx; x++) {
                const block = structure.palette[idx[i++]];
                if (!block) continue;
                if (a.skipAir && /(^|:)air$/.test(block)) continue;
                let rx = x, rz = z;
                switch (rotation) {
                  case 90: rx = sz - 1 - z; rz = x; break;
                  case 180: rx = sx - 1 - x; rz = sz - 1 - z; break;
                  case 270: rx = z; rz = sx - 1 - x; break;
                }
                blocks.push({ pos: [origin[0] + rx, origin[1] + y, origin[2] + rz], block: rotateBlockStates(block, rotation) });
              }
            }
          }
          op = { type: 'blocks', blocks };
        }

        const res = await ctx.submit({ world: a.world, label: `structure ${a.name}`, ops: [op], dryRun: a.dryRun });
        return toolText(`Структура "${a.name}" (${structure.size.join('×')}) поставлена в ${a.x},${a.y},${a.z}.\n${res.report}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'list_structures',
    {
      title: 'Сохранённые структуры',
      description: 'Список структур, снятых через capture_region.',
      inputSchema: {},
    },
    async () => {
      try {
        const names = await ctx.bridge.listCaptures();
        return toolText(names.length ? names.join('\n') : 'Пока ничего не сохранено.');
      } catch (err) {
        return toolError(err);
      }
    },
  );
}

function rotateBlockStates(block: string, rotation: Rotation): string {
  if (rotation === 0) return block;
  const parsed = parseBlock(block);
  const states = { ...parsed.states };
  if (states.facing) states.facing = rotateFacing(states.facing as Facing, rotation);
  if (states.axis) states.axis = rotateAxis(states.axis, rotation);
  if (states.rotation !== undefined) {
    const n = Number(states.rotation);
    if (Number.isFinite(n)) states.rotation = String((n + (rotation / 90) * 4) % 16);
  }
  return withStates(parsed.id, states);
}

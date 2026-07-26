import { z } from 'zod';
import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { Context, toolError, toolText } from '../context.js';
import type { Op, Pos } from '../protocol.js';
import { Palette } from '../build/palette.js';
import { Rng } from '../util/rng.js';
import { blockEntry, toScatterEntries } from '../build/flora.js';

const posSchema = z.tuple([z.number().int(), z.number().int(), z.number().int()]);
const blockRefSchema = z.union([
  z.string(),
  z.object({
    choices: z.array(z.object({ block: z.string(), weight: z.number().optional() })).min(1),
  }),
]);

export function registerBuildTools(server: McpServer, ctx: Context): void {
  server.registerTool(
    'fill_region',
    {
      title: 'Залить область',
      description:
        'Заполнить прямоугольную область блоком. mode: replace (всё), keep (только воздух), hollow (полая коробка), ' +
        'outline (только стены без пола/потолка), destroy. filter — менять только перечисленные блоки. ' +
        'block может быть взвешенным набором, чтобы поверхность выглядела естественно.',
      inputSchema: {
        world: z.string().optional(),
        from: posSchema,
        to: posSchema,
        block: blockRefSchema,
        mode: z.enum(['replace', 'keep', 'hollow', 'outline', 'destroy']).optional(),
        filter: z.array(z.string()).optional(),
        dryRun: z.boolean().optional(),
      },
    },
    async ({ world, from, to, block, mode, filter, dryRun }) => {
      try {
        const op: Op = { type: 'fill', from: from as Pos, to: to as Pos, block, mode, filter };
        const res = await ctx.submit({ world, label: 'fill_region', ops: [op], dryRun });
        return toolText(`Заливка выполнена.\n${res.report}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'place_blocks',
    {
      title: 'Поставить блоки',
      description:
        'Поставить произвольный набор блоков по координатам. Порядок сохраняется — важно для дверей и кроватей. ' +
        'Для больших объёмов лучше использовать fill_region / draw_shape / build_blueprint.',
      inputSchema: {
        world: z.string().optional(),
        blocks: z.array(z.object({ pos: posSchema, block: z.string() })).min(1).max(60000),
        dryRun: z.boolean().optional(),
      },
    },
    async ({ world, blocks, dryRun }) => {
      try {
        const op: Op = { type: 'blocks', blocks: blocks.map((b) => ({ pos: b.pos as Pos, block: b.block })) };
        const res = await ctx.submit({ world, label: `place_blocks (${blocks.length})`, ops: [op], dryRun });
        return toolText(`Поставлено блоков: ${blocks.length}.\n${res.report}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'draw_shape',
    {
      title: 'Нарисовать фигуру',
      description:
        'Геометрические примитивы: sphere (шар/эллипсоид), cylinder, cone, pyramid, torus, line (с толщиной), ' +
        'walls (4 стены без пола и потолка). Считается на сервере, поэтому даже большие фигуры дешёвые.',
      inputSchema: {
        world: z.string().optional(),
        shape: z.enum(['sphere', 'cylinder', 'cone', 'pyramid', 'torus', 'line', 'walls']),
        block: blockRefSchema,
        hollow: z.boolean().optional(),
        center: posSchema.optional().describe('для sphere/torus'),
        base: posSchema.optional().describe('для cylinder/cone/pyramid'),
        from: posSchema.optional().describe('для line/walls'),
        to: posSchema.optional().describe('для line/walls'),
        radius: z.union([z.number(), posSchema]).optional(),
        height: z.number().int().optional(),
        size: z.number().int().optional().describe('для pyramid'),
        tube: z.number().optional().describe('толщина тора'),
        axis: z.enum(['x', 'y', 'z']).optional().describe('ось цилиндра'),
        thickness: z.number().int().optional().describe('толщина линии'),
        inverted: z.boolean().optional(),
        dryRun: z.boolean().optional(),
      },
    },
    async (a) => {
      try {
        let op: Op;
        switch (a.shape) {
          case 'sphere':
            if (!a.center || a.radius === undefined) throw new Error('sphere требует center и radius');
            op = { type: 'sphere', center: a.center as Pos, radius: a.radius as number | Pos, block: a.block, hollow: a.hollow };
            break;
          case 'cylinder':
            if (!a.base || typeof a.radius !== 'number' || !a.height) throw new Error('cylinder требует base, radius (число) и height');
            op = { type: 'cylinder', base: a.base as Pos, radius: a.radius, height: a.height, axis: a.axis, block: a.block, hollow: a.hollow };
            break;
          case 'cone':
            if (!a.base || typeof a.radius !== 'number' || !a.height) throw new Error('cone требует base, radius и height');
            op = { type: 'cone', base: a.base as Pos, radius: a.radius, height: a.height, block: a.block, hollow: a.hollow };
            break;
          case 'pyramid':
            if (!a.base || !a.size) throw new Error('pyramid требует base и size');
            op = { type: 'pyramid', base: a.base as Pos, size: a.size, block: a.block, hollow: a.hollow, inverted: a.inverted };
            break;
          case 'torus':
            if (!a.center || typeof a.radius !== 'number' || !a.tube) throw new Error('torus требует center, radius и tube');
            op = { type: 'torus', center: a.center as Pos, radius: a.radius, tube: a.tube, block: a.block };
            break;
          case 'line':
            if (!a.from || !a.to) throw new Error('line требует from и to');
            op = { type: 'line', from: a.from as Pos, to: a.to as Pos, block: a.block, thickness: a.thickness };
            break;
          case 'walls':
            if (!a.from || !a.to) throw new Error('walls требует from и to');
            op = { type: 'walls', from: a.from as Pos, to: a.to as Pos, block: a.block };
            break;
        }
        const res = await ctx.submit({ world: a.world, label: `draw_shape ${a.shape}`, ops: [op], dryRun: a.dryRun });
        return toolText(`Фигура ${a.shape} построена.\n${res.report}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'terraform',
    {
      title: 'Изменить рельеф',
      description:
        'Работа с рельефом: flatten (выровнять до Y, засыпать/срезать), smooth (сгладить), raise (поднять/опустить), ' +
        'terrace (террасы), clear (очистить объём в воздух). Делай ЭТО ПЕРЕД постройкой на неровной местности. ' +
        'Требует плагин.',
      inputSchema: {
        world: z.string().optional(),
        action: z.enum(['flatten', 'smooth', 'raise', 'terrace', 'clear']),
        from: posSchema,
        to: posSchema,
        y: z.number().int().optional().describe('целевая высота для flatten'),
        amount: z.number().int().optional().describe('для raise: + вверх, − вниз'),
        step: z.number().int().optional().describe('высота ступени для terrace'),
        iterations: z.number().int().min(1).max(8).optional().describe('для smooth'),
        strength: z.number().optional(),
        clearAbove: z.number().int().optional().describe('сколько блоков расчистить над поверхностью'),
        style: z.string().optional().describe('id стиля — взять из него блоки поверхности и подложки'),
        surface: z.string().optional(),
        fill: z.string().optional(),
        keepGround: z.boolean().optional(),
        dryRun: z.boolean().optional(),
      },
    },
    async (a) => {
      try {
        const rng = new Rng(`terraform:${a.from.join(',')}`);
        let surface = a.surface;
        let fill = a.fill;
        if (a.style) {
          const palette = new Palette(ctx.style(a.style), rng);
          surface ??= palette.ground('top');
          fill ??= palette.ground('under');
        }
        let op: Op;
        switch (a.action) {
          case 'flatten':
            if (a.y === undefined) throw new Error('flatten требует y (целевую высоту поверхности)');
            op = { type: 'flatten', from: a.from as Pos, to: a.to as Pos, y: a.y, surface, fill, clearAbove: a.clearAbove ?? 8 };
            break;
          case 'smooth':
            op = { type: 'smooth', from: a.from as Pos, to: a.to as Pos, iterations: a.iterations ?? 2, strength: a.strength };
            break;
          case 'raise':
            if (a.amount === undefined) throw new Error('raise требует amount');
            op = { type: 'raise', from: a.from as Pos, to: a.to as Pos, amount: a.amount, falloff: 'smooth' };
            break;
          case 'terrace':
            op = { type: 'terrace', from: a.from as Pos, to: a.to as Pos, step: a.step ?? 3 };
            break;
          case 'clear':
            op = { type: 'clear', from: a.from as Pos, to: a.to as Pos, keepGround: a.keepGround ?? false };
            break;
        }
        const res = await ctx.submit({ world: a.world, label: `terraform ${a.action}`, ops: [op], dryRun: a.dryRun });
        return toolText(`Рельеф изменён (${a.action}).\n${res.report}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'paint_surface',
    {
      title: 'Покрасить поверхность',
      description:
        'Заменить верхний слой земли на участке: трава/подзол/песок/снег, дорожки, площади. Работает по колонкам, ' +
        'Y в координатах игнорируется. Если указать style — блоки берутся из его палитры (ground.top / path / plaza).',
      inputSchema: {
        world: z.string().optional(),
        from: z.tuple([z.number().int(), z.number().int()]).describe('[x, z]'),
        to: z.tuple([z.number().int(), z.number().int()]).describe('[x, z]'),
        style: z.string().optional(),
        layer: z.enum(['top', 'path', 'plaza']).optional().describe('какой слой стиля использовать'),
        block: blockRefSchema.optional().describe('явный блок вместо стиля'),
        depth: z.number().int().min(1).max(8).optional(),
        onlyOn: z.array(z.string()).optional().describe('красить только поверх этих блоков'),
        dryRun: z.boolean().optional(),
      },
    },
    async (a) => {
      try {
        let block = a.block;
        if (!block) {
          if (!a.style) throw new Error('нужен либо block, либо style');
          const style = ctx.style(a.style);
          const list =
            a.layer === 'path' ? style.ground?.path :
            a.layer === 'plaza' ? style.ground?.plaza :
            style.ground?.top;
          if (!list?.length) throw new Error(`в стиле ${a.style} нет слоя "${a.layer ?? 'top'}"`);
          block = { choices: list.map((b) => ({ block: b.block, weight: b.weight })) };
        }
        const op: Op = {
          type: 'paint',
          from: [a.from[0], 0, a.from[1]],
          to: [a.to[0], 0, a.to[1]],
          block,
          depth: a.depth ?? 1,
          onlyOn: a.onlyOn,
          seed: 1,
        };
        const res = await ctx.submit({ world: a.world, label: 'paint_surface', ops: [op], dryRun: a.dryRun });
        return toolText(`Поверхность покрашена.\n${res.report}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'scatter_props',
    {
      title: 'Рассыпать декор',
      description:
        'Естественно раскидать по участку траву, цветы, деревья, камни, снег — по палитре стиля или своим набором. ' +
        'Это то, что превращает голую площадку в живой ландшафт.',
      inputSchema: {
        world: z.string().optional(),
        from: z.tuple([z.number().int(), z.number().int()]).describe('[x, z]'),
        to: z.tuple([z.number().int(), z.number().int()]).describe('[x, z]'),
        style: z.string().optional(),
        kind: z.enum(['ground', 'trees', 'rocks', 'snow', 'custom']).optional(),
        density: z.number().min(0).max(1).optional(),
        minSpacing: z.number().int().optional(),
        entries: z.array(z.object({
          weight: z.number().optional(),
          block: z.string().optional(),
          tree: z.string().optional(),
          blueprint: z.string().optional(),
        })).optional().describe('для kind=custom'),
        onlyOn: z.array(z.string()).optional(),
        seed: z.number().int().optional(),
        dryRun: z.boolean().optional(),
      },
    },
    async (a) => {
      try {
        const kind = a.kind ?? 'ground';
        let entries = a.entries;
        let density = a.density;
        let onlyOn = a.onlyOn;

        if (kind !== 'custom') {
          if (kind === 'snow') {
            entries = [{ block: 'minecraft:snow[layers=1]', weight: 7 }, { block: 'minecraft:snow[layers=2]', weight: 3 }];
            density ??= 0.9;
          } else {
            if (!a.style) throw new Error('нужен style (или kind=custom с entries)');
            const style = ctx.style(a.style);
            const src = kind === 'trees' ? style.flora?.trees : kind === 'rocks' ? style.flora?.rocks : style.flora?.ground;
            if (!src?.length) throw new Error(`в стиле ${a.style} нет флоры типа "${kind}"`);
            entries = toScatterEntries(src);
            density ??= kind === 'trees' ? style.flora?.treeDensity ?? 0.02
              : kind === 'rocks' ? style.flora?.rockDensity ?? 0.004
              : style.flora?.groundDensity ?? 0.1;
            onlyOn ??= (style.ground?.top ?? []).map((b) => b.block);
          }
        }
        if (!entries?.length) throw new Error('нечего рассыпать: пустой список entries');
        entries = entries.map((e) => (e.block ? { weight: e.weight, ...blockEntry(e.block) } : e));

        const op: Op = {
          type: 'scatter',
          from: [a.from[0], 0, a.from[1]],
          to: [a.to[0], 0, a.to[1]],
          density: density ?? 0.05,
          seed: a.seed ?? 1234,
          minSpacing: a.minSpacing ?? (kind === 'trees' ? 4 : 0),
          entries,
          onlyOn,
          needsAir: true,
          avoidWater: true,
        };
        const res = await ctx.submit({ world: a.world, label: `scatter ${kind}`, ops: [op], dryRun: a.dryRun });
        return toolText(`Декор рассыпан (${kind}).\n${res.report}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'place_sign',
    {
      title: 'Поставить табличку',
      description: 'Табличка с текстом — названия зданий, указатели, приветствие на спавне.',
      inputSchema: {
        world: z.string().optional(),
        pos: posSchema,
        block: z.string().optional().describe('по умолчанию minecraft:oak_sign'),
        facing: z.enum(['north', 'south', 'east', 'west']).optional().describe('для настенной таблички'),
        lines: z.array(z.string()).max(4),
        back: z.array(z.string()).max(4).optional(),
        glowing: z.boolean().optional(),
        color: z.string().optional(),
      },
    },
    async ({ world, pos, block, facing, lines, back, glowing, color }) => {
      try {
        const signBlock = block ?? (facing ? `minecraft:oak_wall_sign[facing=${facing}]` : 'minecraft:oak_sign');
        const op: Op = { type: 'sign', pos: pos as Pos, block: signBlock, front: lines, back, glowing, color };
        const res = await ctx.submit({ world, label: 'place_sign', ops: [op] });
        return toolText(`Табличка поставлена.\n${res.report}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );

  server.registerTool(
    'spawn_entity',
    {
      title: 'Заспавнить существо',
      description:
        'Поставить жителя, торговца, животное или моба. Для оживления деревни: villager с профессией, ' +
        'персистентный, чтобы не деспавнился.',
      inputSchema: {
        world: z.string().optional(),
        pos: z.tuple([z.number(), z.number(), z.number()]),
        entity: z.string().describe('например minecraft:villager'),
        name: z.string().optional(),
        profession: z.string().optional(),
        noAI: z.boolean().optional(),
        count: z.number().int().min(1).max(50).optional(),
        snbt: z.string().optional().describe('дополнительный NBT в SNBT-формате'),
      },
    },
    async ({ world, pos, entity, name, profession, noAI, count, snbt }) => {
      try {
        const ops: Op[] = Array.from({ length: count ?? 1 }, () => ({
          type: 'entity',
          pos: pos as [number, number, number],
          entity,
          name,
          profession,
          noAI,
          persistent: true,
          tags: ['mapaimine'],
          snbt,
        }));
        const res = await ctx.submit({ world, label: `spawn ${entity}`, ops, undo: false });
        return toolText(`Заспавнено: ${entity} ×${count ?? 1}.\n${res.report}`);
      } catch (err) {
        return toolError(err);
      }
    },
  );
}

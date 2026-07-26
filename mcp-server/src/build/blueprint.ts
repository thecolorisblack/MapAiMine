import type { Blueprint, LegendCell, StylePack } from '../content/types.js';
import { signLines } from '../content/types.js';
import type { Op, Pos } from '../protocol.js';
import { Rng } from '../util/rng.js';
import { Palette } from './palette.js';
import {
  isConnecting, isDoor, mirrorFacing, parseBlock, rotateAxis, rotateFacing,
  withStates, type Facing,
} from './blockstate.js';

export type Rotation = 0 | 90 | 180 | 270;

export interface PlaceOptions {
  /** World position that blueprint-local (0,0,0) maps to, after rotation. */
  origin: Pos;
  rotation?: Rotation;
  mirror?: 'x' | 'z' | null;
  seed?: number | string;
  style: StylePack;
  paletteOverrides?: Record<string, string>;
  props: Map<string, Blueprint>;
  lang?: 'ru' | 'en';
  /** Skip terrain preparation (flatten / clearance / foundation). */
  skipPrepare?: boolean;
  /** Names substituted into sign text, e.g. { settlement: "Ольхово" }. */
  signVars?: Record<string, string>;
}

export interface PlaceResult {
  ops: Op[];
  /** Re-applied afterwards with physics on, so fences/stairs connect properly. */
  physicsOps: Op[];
  blockCount: number;
  /** World-space AABB actually touched, including prepared ground. */
  bounds: { min: Pos; max: Pos };
  footprint: { minX: number; minZ: number; maxX: number; maxZ: number };
  warnings: string[];
}

/** Footprint of a blueprint after rotation, in blocks. */
export function rotatedFootprint(bp: Blueprint, rotation: Rotation = 0): [number, number] {
  const [w, , d] = bp.size;
  return rotation === 90 || rotation === 270 ? [d, w] : [w, d];
}

interface Transformer {
  (x: number, y: number, z: number): Pos;
  facing(f: Facing): Facing;
  axis(a: string): string;
}

function makeTransformer(
  size: [number, number, number],
  origin: Pos,
  rotation: Rotation,
  mirror: 'x' | 'z' | null,
): Transformer {
  const [W, , D] = size;
  const fn = ((x: number, y: number, z: number): Pos => {
    let lx = x, lz = z;
    if (mirror === 'x') lx = W - 1 - lx;
    if (mirror === 'z') lz = D - 1 - lz;
    let rx: number, rz: number;
    switch (rotation) {
      case 90: rx = D - 1 - lz; rz = lx; break;
      case 180: rx = W - 1 - lx; rz = D - 1 - lz; break;
      case 270: rx = lz; rz = W - 1 - lx; break;
      default: rx = lx; rz = lz; break;
    }
    return [origin[0] + rx, origin[1] + y, origin[2] + rz];
  }) as Transformer;
  fn.facing = (f: Facing) => rotateFacing(mirror ? mirrorFacing(f, mirror) : f, rotation);
  fn.axis = (a: string) => rotateAxis(a, rotation);
  return fn;
}

/**
 * Renders a blueprint into bridge operations.
 *
 * Order matters: layers are emitted bottom-up so that supports exist before what
 * rests on them, while door upper halves, props and decorations are deferred to
 * the end so a later layer's air cell cannot erase them.
 */
export function placeBlueprint(bp: Blueprint, opts: PlaceOptions): PlaceResult {
  const rotation = (opts.rotation ?? 0) as Rotation;
  const mirror = opts.mirror ?? null;
  const rng = new Rng(opts.seed ?? `${bp.id}:${opts.origin.join(',')}`);
  const palette = new Palette(opts.style, rng, opts.paletteOverrides ?? {});
  const lang = opts.lang ?? 'ru';
  const warnings: string[] = [];

  const xf = makeTransformer(bp.size, opts.origin, rotation, mirror);

  const blocks: Array<{ pos: Pos; block: string }> = [];
  const deferred: Array<{ pos: Pos; block: string }> = [];
  const tail: Op[] = [];

  const layers = [...bp.layers].sort((a, b) => a.y - b.y);
  for (const layer of layers) {
    for (let z = 0; z < layer.rows.length; z++) {
      const row = layer.rows[z];
      for (let x = 0; x < row.length; x++) {
        const cell = bp.legend[row[x]];
        if (!cell || cell.skip) continue;
        if (cell.chance !== undefined && !rng.chance(cell.chance)) continue;

        const pos = xf(x, layer.y, z);

        if (cell.prop) {
          const prop = opts.props.get(cell.prop);
          if (!prop) {
            warnings.push(`unknown prop "${cell.prop}" in blueprint ${bp.id}`);
            continue;
          }
          const sub = placeBlueprint(prop, {
            ...opts,
            origin: pos,
            rotation,
            mirror,
            seed: `${opts.seed ?? bp.id}:${cell.prop}:${x},${layer.y},${z}`,
            skipPrepare: true,
          });
          tail.push(...sub.ops, ...sub.physicsOps);
          warnings.push(...sub.warnings);
          continue;
        }

        const block = resolveCell(cell, palette, xf);
        if (!block) continue;

        if (isDoor(block)) {
          blocks.push({ pos, block });
          deferred.push({
            pos: [pos[0], pos[1] + 1, pos[2]] as Pos,
            block: withStates(block, { half: 'upper' }),
          });
        } else {
          blocks.push({ pos, block });
        }
      }
    }
  }

  blocks.push(...deferred);

  /* ── decorations declared outside the ASCII grid ── */
  for (const sign of bp.signs ?? []) {
    const pos = xf(sign.pos[0], sign.pos[1], sign.pos[2]);
    const facing = xf.facing(sign.facing ?? 'north');
    const block =
      sign.block ??
      palette.block('wall_secondary', { variant: sign.variant ?? 'wall_sign', facing });
    const raw = sign.text ?? signLines(opts.style.signs?.[sign.textKey ?? ''], lang);
    const front = raw.map((line) =>
      line.replace(/%(\w+)%/g, (_, key: string) => opts.signVars?.[key] ?? ''),
    );
    tail.push({ type: 'sign', pos, block, front: front.slice(0, 4), glowing: false });
  }

  for (const c of bp.containers ?? []) {
    const pos = xf(c.pos[0], c.pos[1], c.pos[2]);
    tail.push({
      type: 'container',
      pos,
      block: rotateRawBlock(c.block, xf),
      lootTable: c.lootTable,
      items: c.items,
    });
  }

  for (const e of bp.entities ?? []) {
    const pos = xf(e.pos[0], e.pos[1], e.pos[2]);
    tail.push({
      type: 'entity',
      pos: [pos[0] + 0.5, pos[1], pos[2] + 0.5],
      entity: e.entity,
      profession: e.profession,
      name: e.name,
      noAI: e.noAI,
      persistent: true,
      tags: ['mapaimine'],
    });
  }

  for (const l of bp.lights ?? []) {
    const pos = xf(l[0], l[1], l[2]);
    blocks.push({ pos, block: palette.block('light') });
  }

  /* ── world-space bounds ── */
  const corners: Pos[] = [];
  for (const cx of [0, bp.size[0] - 1]) {
    for (const cz of [0, bp.size[2] - 1]) corners.push(xf(cx, 0, cz));
  }
  const minX = Math.min(...corners.map((c) => c[0]));
  const maxX = Math.max(...corners.map((c) => c[0]));
  const minZ = Math.min(...corners.map((c) => c[2]));
  const maxZ = Math.max(...corners.map((c) => c[2]));
  const baseY = opts.origin[1];
  const topY = baseY + bp.size[1] - 1;

  /* ── terrain preparation ── */
  const prepare: Op[] = [];
  if (!opts.skipPrepare) {
    const pad = bp.prepare?.padding ?? 0;
    const clearAbove = bp.clearance?.above ?? 0;
    if (clearAbove > 0) {
      prepare.push({
        type: 'fill',
        from: [minX - pad, topY + 1, minZ - pad],
        to: [maxX + pad, topY + clearAbove, maxZ + pad],
        block: 'minecraft:air',
        mode: 'replace',
      });
    }
    if (bp.prepare?.flatten) {
      const groundLevel = bp.groundLevel ?? 0;
      prepare.push({
        type: 'flatten',
        from: [minX - pad, baseY, minZ - pad],
        to: [maxX + pad, baseY, maxZ + pad],
        y: baseY + groundLevel - 1,
        surface: palette.ground('top'),
        fill: palette.ground('under'),
        clearAbove: bp.size[1] + clearAbove,
      });
    }
    if ((bp.prepare?.foundationTo ?? 'ground') === 'ground') {
      prepare.push({
        type: 'fill',
        from: [minX, baseY - 12, minZ],
        to: [maxX, baseY - 1, maxZ],
        block: palette.fullBlock('foundation'),
        mode: 'keep', // only fills air/water, so it grows a pillar down a slope
      });
    }
  }

  /* ── split the connection-sensitive blocks into a physics pass ── */
  const plain: Array<{ pos: Pos; block: string }> = [];
  const connecting: Array<{ pos: Pos; block: string }> = [];
  for (const b of blocks) (isConnecting(b.block) ? connecting : plain).push(b);

  const ops: Op[] = [...prepare];
  if (plain.length) ops.push({ type: 'blocks', blocks: plain });
  if (connecting.length) ops.push({ type: 'blocks', blocks: connecting });
  ops.push(...tail);

  const physicsOps: Op[] = connecting.length ? [{ type: 'blocks', blocks: connecting }] : [];

  for (const w of palette.warnings) warnings.push(w);

  return {
    ops,
    physicsOps,
    blockCount: blocks.length,
    bounds: { min: [minX, baseY - 12, minZ], max: [maxX, topY + (bp.clearance?.above ?? 0), maxZ] },
    footprint: { minX, minZ, maxX, maxZ },
    warnings,
  };
}

function resolveCell(cell: LegendCell, palette: Palette, xf: Transformer): string | null {
  if (cell.block) {
    let block = rotateRawBlock(cell.block, xf);
    if (cell.states) block = withStates(block, cell.states);
    return block;
  }
  if (!cell.role) return null;
  return palette.block(cell.role, {
    variant: cell.variant,
    facing: cell.facing ? xf.facing(cell.facing) : undefined,
    half: cell.half,
    axis: cell.axis ? (xf.axis(cell.axis) as 'x' | 'y' | 'z') : undefined,
    waterlogged: cell.waterlogged,
    states: cell.states,
  });
}

/** Rotate the orientation-carrying states of a literal block written in a blueprint. */
function rotateRawBlock(block: string, xf: Transformer): string {
  const parsed = parseBlock(block);
  const states = { ...parsed.states };
  if (states.facing) states.facing = xf.facing(states.facing as Facing);
  if (states.axis) states.axis = xf.axis(states.axis);
  if (states.rotation !== undefined) {
    // 16-step sign rotation: rotate along with the build.
    const steps = Number(states.rotation);
    if (Number.isFinite(steps)) {
      const turn = xf.facing('north') === 'east' ? 4 : xf.facing('north') === 'south' ? 8 : xf.facing('north') === 'west' ? 12 : 0;
      states.rotation = String((steps + turn) % 16);
    }
  }
  return withStates(parsed.id, states);
}

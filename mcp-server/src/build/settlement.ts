import type { Blueprint, ContentLibrary, StylePack } from '../content/types.js';
import type { Op, Pos } from '../protocol.js';
import { Rng } from '../util/rng.js';
import { Palette } from './palette.js';
import { placeBlueprint, rotatedFootprint, type Rotation } from './blueprint.js';
import { toScatterEntries } from './flora.js';

export type SettlementKind = 'village' | 'town' | 'castle' | 'camp' | 'farmstead' | 'harbor';

export interface SettlementOptions {
  center: [number, number];
  /** Side length of the square site, in blocks. */
  size: number;
  baseY: number;
  style: StylePack;
  library: ContentLibrary;
  kind?: SettlementKind;
  seed?: number | string;
  name?: string;
  lang?: 'ru' | 'en';
  /** How aggressively to level the ground. */
  flattenSite?: 'full' | 'plots' | 'none';
  wall?: boolean;
  density?: number;
  /** Restrict building choice to these blueprint ids. */
  only?: string[];
}

export interface PlacedBuilding {
  blueprint: string;
  category: string;
  pos: Pos;
  rotation: Rotation;
  footprint: [number, number];
}

export interface SettlementResult {
  ops: Op[];
  physicsOps: Op[];
  buildings: PlacedBuilding[];
  bounds: { x1: number; z1: number; x2: number; z2: number };
  plaza: { x1: number; z1: number; x2: number; z2: number };
  roads: number;
  blockCount: number;
  warnings: string[];
  summary: string;
}

interface Rect { x1: number; z1: number; x2: number; z2: number }

const overlaps = (a: Rect, b: Rect, gap = 0): boolean =>
  a.x1 - gap <= b.x2 && a.x2 + gap >= b.x1 && a.z1 - gap <= b.z2 && a.z2 + gap >= b.z1;

const KIND_DEFAULTS: Record<SettlementKind, { wall: boolean; density: number; mix: Record<string, number>; landmark?: string }> = {
  village:   { wall: false, density: 0.65, mix: { house: 10, farm: 4, shop: 2, smithy: 1, tavern: 1, well: 1, barn: 2, stable: 1 }, landmark: 'well' },
  town:      { wall: true,  density: 0.85, mix: { house: 8, shop: 5, tavern: 2, smithy: 2, library: 1, town_hall: 1, church: 1, watchtower: 2 }, landmark: 'town_hall' },
  castle:    { wall: true,  density: 0.5,  mix: { keep: 1, watchtower: 4, smithy: 1, stable: 1, house: 3, barn: 1 }, landmark: 'keep' },
  camp:      { wall: false, density: 0.35, mix: { campsite: 6, watchtower: 1, stable: 1, market_stall: 2 }, landmark: 'campsite' },
  farmstead: { wall: false, density: 0.5,  mix: { farm_field: 8, barn: 3, house: 3, windmill: 1, stable: 2, well: 1 }, landmark: 'windmill' },
  harbor:    { wall: false, density: 0.7,  mix: { dock_segment: 6, house: 6, shop: 3, tavern: 1, market_stall: 3, watchtower: 1 }, landmark: 'tavern' },
};

/**
 * Procedural settlement layout: a road grid around a central plaza, plots facing
 * the streets, a landmark in the middle, optional curtain wall, street lamps and
 * a decoration pass. Fully deterministic for a given seed.
 */
export function generateSettlement(opts: SettlementOptions): SettlementResult {
  const kind = opts.kind ?? 'village';
  const defaults = KIND_DEFAULTS[kind];
  const rng = new Rng(opts.seed ?? `${kind}:${opts.center.join(',')}:${opts.size}`);
  const palette = new Palette(opts.style, rng);
  const warnings: string[] = [];
  const settings = opts.style.settlement ?? {};
  const lang = opts.lang ?? 'ru';

  const roadWidth = Math.max(2, settings.roadWidth ?? 3);
  const padding = settings.plotPadding ?? 2;
  const density = opts.density ?? settings.density ?? defaults.density;
  const wantWall = opts.wall ?? settings.wall ?? defaults.wall;
  const lampSpacing = settings.lampSpacing ?? 8;
  const baseY = opts.baseY;
  const groundY = baseY; // top solid block of the levelled site

  const half = Math.floor(opts.size / 2);
  const site: Rect = {
    x1: opts.center[0] - half, z1: opts.center[1] - half,
    x2: opts.center[0] + half, z2: opts.center[1] + half,
  };

  const ops: Op[] = [];
  const physicsOps: Op[] = [];
  const buildings: PlacedBuilding[] = [];
  const occupied: Rect[] = [];
  let blockCount = 0;

  /* ── 1. site preparation ─────────────────────────────────────────── */
  if (opts.flattenSite !== 'none') {
    ops.push({
      type: 'flatten',
      from: [site.x1, baseY, site.z1],
      to: [site.x2, baseY, site.z2],
      y: groundY,
      surface: palette.ground('top'),
      fill: palette.ground('under'),
      clearAbove: 24,
    });
    blockCount += (site.x2 - site.x1 + 1) * (site.z2 - site.z1 + 1) * 4;
  }

  /* ── 2. plaza ────────────────────────────────────────────────────── */
  const [pw, pd] = settings.plazaSize ?? [13, 13];
  const plaza: Rect = {
    x1: opts.center[0] - Math.floor(pw / 2), z1: opts.center[1] - Math.floor(pd / 2),
    x2: opts.center[0] + Math.floor(pw / 2), z2: opts.center[1] + Math.floor(pd / 2),
  };
  ops.push({
    type: 'fill',
    from: [plaza.x1, groundY, plaza.z1],
    to: [plaza.x2, groundY, plaza.z2],
    block: { choices: (opts.style.ground?.plaza ?? [{ block: palette.fullBlock('plaza') }]).map((b) => ({ block: b.block, weight: b.weight })) },
    mode: 'replace',
  });
  occupied.push(plaza);
  blockCount += (plaza.x2 - plaza.x1 + 1) * (plaza.z2 - plaza.z1 + 1);

  /* ── 3. road grid ────────────────────────────────────────────────── */
  const cell = Math.max(18, Math.round(opts.size / Math.max(2, Math.round(opts.size / 26))));
  const roadXs: number[] = [];
  const roadZs: number[] = [];
  for (let x = opts.center[0]; x >= site.x1 + 6; x -= cell) roadXs.unshift(x);
  for (let x = opts.center[0] + cell; x <= site.x2 - 6; x += cell) roadXs.push(x);
  for (let z = opts.center[1]; z >= site.z1 + 6; z -= cell) roadZs.unshift(z);
  for (let z = opts.center[1] + cell; z <= site.z2 - 6; z += cell) roadZs.push(z);

  const roadBlock = { choices: (opts.style.ground?.path ?? [{ block: palette.fullBlock('path_primary') }]).map((b) => ({ block: b.block, weight: b.weight })) };
  const roadRects: Rect[] = [];
  const hw = Math.floor(roadWidth / 2);

  for (const x of roadXs) {
    const r: Rect = { x1: x - hw, z1: site.z1, x2: x - hw + roadWidth - 1, z2: site.z2 };
    roadRects.push(r);
  }
  for (const z of roadZs) {
    const r: Rect = { x1: site.x1, z1: z - hw, x2: site.x2, z2: z - hw + roadWidth - 1 };
    roadRects.push(r);
  }
  for (const r of roadRects) {
    ops.push({
      type: 'fill',
      from: [r.x1, groundY, r.z1], to: [r.x2, groundY, r.z2],
      block: roadBlock, mode: 'replace',
    });
    ops.push({
      type: 'fill',
      from: [r.x1, groundY + 1, r.z1], to: [r.x2, groundY + 3, r.z2],
      block: 'minecraft:air', mode: 'replace',
    });
    occupied.push(r);
    blockCount += (r.x2 - r.x1 + 1) * (r.z2 - r.z1 + 1) * 4;
  }

  /* ── 4. landmark in the plaza ────────────────────────────────────── */
  const landmarkId = settings.landmark ?? defaults.landmark;
  const landmark = landmarkId ? pickBlueprint(opts.library, [landmarkId], opts.style, rng) : null;
  if (landmark) {
    const [lw, ld] = rotatedFootprint(landmark, 0);
    const pos: Pos = [
      opts.center[0] - Math.floor(lw / 2),
      groundY + 1 - (landmark.groundLevel ?? 0),
      opts.center[1] - Math.floor(ld / 2),
    ];
    const placed = placeBlueprint(landmark, {
      origin: pos, rotation: 0, style: opts.style, props: opts.library.props,
      seed: rng.int(0, 1e9), lang, signVars: { settlement: opts.name ?? '' },
    });
    ops.push(...placed.ops);
    physicsOps.push(...placed.physicsOps);
    warnings.push(...placed.warnings);
    blockCount += placed.blockCount;
    buildings.push({ blueprint: landmark.id, category: landmark.category, pos, rotation: 0, footprint: [lw, ld] });
    occupied.push({ x1: pos[0] - 1, z1: pos[2] - 1, x2: pos[0] + lw, z2: pos[2] + ld });
  } else if (landmarkId) {
    warnings.push(`landmark blueprint "${landmarkId}" not found in the library`);
  }

  /* ── 5. plots between the roads ──────────────────────────────────── */
  const mix = { ...defaults.mix, ...(settings.buildingMix ?? {}) };
  const catalog = buildCatalog(opts.library, opts.style, mix, opts.only);
  if (!catalog.length) {
    warnings.push('no blueprints matched this style — nothing was built inside the plots');
  }

  const xEdges = [site.x1, ...roadXs.flatMap((x) => [x - hw - 1, x - hw + roadWidth]), site.x2];
  const zEdges = [site.z1, ...roadZs.flatMap((z) => [z - hw - 1, z - hw + roadWidth]), site.z2];
  const xSpans = pairs(xEdges);
  const zSpans = pairs(zEdges);

  for (const [bx1, bx2] of xSpans) {
    for (const [bz1, bz2] of zSpans) {
      const block: Rect = { x1: bx1 + padding, z1: bz1 + padding, x2: bx2 - padding, z2: bz2 - padding };
      if (block.x2 - block.x1 < 5 || block.z2 - block.z1 < 5) continue;
      if (overlaps(block, plaza)) continue;

      for (const side of ['north', 'south', 'west', 'east'] as const) {
        fillSide(side, block);
      }
    }
  }

  function fillSide(side: 'north' | 'south' | 'west' | 'east', block: Rect): void {
    const horizontal = side === 'north' || side === 'south';
    const spanStart = horizontal ? block.x1 : block.z1;
    const spanEnd = horizontal ? block.x2 : block.z2;
    const depthAvailable = horizontal ? block.z2 - block.z1 + 1 : block.x2 - block.x1 + 1;
    const rotation: Rotation = side === 'north' ? 0 : side === 'south' ? 180 : side === 'west' ? 270 : 90;

    let cursor = spanStart;
    let guard = 0;
    while (cursor <= spanEnd - 4 && guard++ < 64) {
      if (!rng.chance(density)) {
        cursor += rng.int(3, 7);
        continue;
      }
      const remaining = spanEnd - cursor + 1;
      const candidate = pickFitting(catalog, rng, remaining, Math.floor(depthAvailable / 2) + 1, rotation);
      if (!candidate) break;
      const { bp, w, d } = candidate;

      const x = horizontal ? cursor : side === 'west' ? block.x1 : block.x2 - w + 1;
      const z = horizontal ? (side === 'north' ? block.z1 : block.z2 - d + 1) : cursor;
      const rect: Rect = { x1: x, z1: z, x2: x + w - 1, z2: z + d - 1 };

      if (occupied.some((o) => overlaps(rect, o, 1))) {
        cursor += 2;
        continue;
      }

      const origin: Pos = [x, groundY + 1 - (bp.groundLevel ?? 0), z];
      const placed = placeBlueprint(bp, {
        origin, rotation, style: opts.style, props: opts.library.props,
        seed: rng.int(0, 1e9), lang, signVars: { settlement: opts.name ?? '' },
      });
      ops.push(...placed.ops);
      physicsOps.push(...placed.physicsOps);
      warnings.push(...placed.warnings);
      blockCount += placed.blockCount;
      buildings.push({ blueprint: bp.id, category: bp.category, pos: origin, rotation, footprint: [w, d] });
      occupied.push(rect);
      cursor += (horizontal ? w : d) + rng.int(1, 3);
    }
  }

  /* ── 6. street lamps ─────────────────────────────────────────────── */
  const lampBp = opts.library.blueprints.get('lamp_post');
  const lampSpots: Pos[] = [];
  for (const x of roadXs) {
    for (let z = site.z1 + 4; z <= site.z2 - 4; z += lampSpacing) {
      lampSpots.push([x - hw - 1, groundY + 1, z]);
    }
  }
  for (const z of roadZs) {
    for (let x = site.x1 + 4; x <= site.x2 - 4; x += lampSpacing) {
      lampSpots.push([x, groundY + 1, z - hw - 1]);
    }
  }
  for (const spot of lampSpots) {
    if (occupied.some((o) => spot[0] >= o.x1 && spot[0] <= o.x2 && spot[2] >= o.z1 && spot[2] <= o.z2 && o !== plaza)) continue;
    if (lampBp) {
      const placed = placeBlueprint(lampBp, {
        origin: spot, rotation: 0, style: opts.style, props: opts.library.props,
        seed: rng.int(0, 1e9), lang, skipPrepare: true,
      });
      ops.push(...placed.ops);
      physicsOps.push(...placed.physicsOps);
      blockCount += placed.blockCount;
    } else {
      const post = palette.block('fence', { variant: 'fence' });
      ops.push({ type: 'fill', from: spot, to: [spot[0], spot[1] + 2, spot[2]], block: post, mode: 'replace' });
      ops.push({ type: 'set', pos: [spot[0], spot[1] + 3, spot[2]], block: palette.block('light') });
      blockCount += 4;
    }
  }

  /* ── 7. curtain wall ─────────────────────────────────────────────── */
  if (wantWall) {
    const wallHeight = settings.wallHeight ?? 6;
    const wallBlock = palette.fullBlock('wall_primary');
    ops.push({
      type: 'walls',
      from: [site.x1, groundY + 1, site.z1],
      to: [site.x2, groundY + wallHeight, site.z2],
      block: wallBlock,
    });
    // Crenellations
    ops.push({
      type: 'walls',
      from: [site.x1, groundY + wallHeight + 1, site.z1],
      to: [site.x2, groundY + wallHeight + 1, site.z2],
      block: palette.block('wall_primary', { variant: 'wall' }),
    });
    blockCount += ((site.x2 - site.x1) + (site.z2 - site.z1)) * 2 * (wallHeight + 1);

    // Gates where the main roads meet the wall.
    const gate = opts.library.blueprints.get('gatehouse');
    for (const x of [roadXs[Math.floor(roadXs.length / 2)]].filter((v) => v !== undefined)) {
      for (const z of [site.z1, site.z2]) {
        ops.push({
          type: 'fill',
          from: [x - hw, groundY + 1, z - 1], to: [x - hw + roadWidth - 1, groundY + 4, z + 1],
          block: 'minecraft:air', mode: 'replace',
        });
        if (gate) {
          const [gw, gd] = rotatedFootprint(gate, 0);
          const rot: Rotation = z === site.z1 ? 0 : 180;
          const origin: Pos = [x - Math.floor(gw / 2), groundY + 1 - (gate.groundLevel ?? 0), z - Math.floor(gd / 2)];
          const placed = placeBlueprint(gate, {
            origin, rotation: rot, style: opts.style, props: opts.library.props,
            seed: rng.int(0, 1e9), lang,
          });
          ops.push(...placed.ops);
          physicsOps.push(...placed.physicsOps);
          blockCount += placed.blockCount;
          buildings.push({ blueprint: gate.id, category: 'gate', pos: origin, rotation: rot, footprint: [gw, gd] });
        }
      }
    }
  }

  /* ── 8. greenery on whatever is left ─────────────────────────────── */
  const flora = opts.style.flora;
  if (flora?.ground?.length) {
    ops.push({
      type: 'scatter',
      from: [site.x1, groundY, site.z1], to: [site.x2, groundY, site.z2],
      density: flora.groundDensity ?? 0.08,
      seed: rng.int(0, 1e9),
      entries: toScatterEntries(flora.ground),
      onlyOn: (opts.style.ground?.top ?? []).map((b) => b.block),
      needsAir: true,
      avoidWater: true,
    });
  }
  if (flora?.trees?.length) {
    ops.push({
      type: 'scatter',
      from: [site.x1, groundY, site.z1], to: [site.x2, groundY, site.z2],
      density: (flora.treeDensity ?? 0.02) * 0.5,
      seed: rng.int(0, 1e9),
      minSpacing: 5,
      entries: toScatterEntries(flora.trees),
      onlyOn: (opts.style.ground?.top ?? []).map((b) => b.block),
      needsAir: true,
      avoidWater: true,
    });
  }
  if (opts.style.environment?.snowLayer) {
    ops.push({
      type: 'scatter',
      from: [site.x1, groundY, site.z1], to: [site.x2, groundY, site.z2],
      density: 0.9, seed: rng.int(0, 1e9),
      entries: [{ block: 'minecraft:snow[layers=1]' }, { block: 'minecraft:snow[layers=2]', weight: 0.3 }],
      needsAir: true, avoidWater: true,
    });
  }

  for (const w of palette.warnings) warnings.push(w);

  const byCategory = buildings.reduce<Record<string, number>>((acc, b) => {
    acc[b.category] = (acc[b.category] ?? 0) + 1;
    return acc;
  }, {});

  const summary =
    `${kind} "${opts.name ?? '—'}" @ ${opts.center[0]},${groundY},${opts.center[1]} · ` +
    `${opts.size}×${opts.size} · ${buildings.length} buildings (` +
    Object.entries(byCategory).map(([c, n]) => `${c}:${n}`).join(', ') +
    `) · ${roadRects.length} roads${wantWall ? ' · walled' : ''}`;

  return {
    ops, physicsOps, buildings, bounds: site, plaza,
    roads: roadRects.length, blockCount, warnings, summary,
  };
}

/**
 * Top-down ASCII map of a generated layout. Lets an agent (and the user) sanity-check
 * a town plan before a single block is placed.
 */
export function renderLayout(result: SettlementResult, cellSize = 2): string {
  const { bounds, plaza } = result;
  const cols = Math.max(1, Math.ceil((bounds.x2 - bounds.x1 + 1) / cellSize));
  const rows = Math.max(1, Math.ceil((bounds.z2 - bounds.z1 + 1) / cellSize));
  const grid: string[][] = Array.from({ length: rows }, () => Array<string>(cols).fill('·'));

  const mark = (x1: number, z1: number, x2: number, z2: number, ch: string, overwrite = true) => {
    for (let z = z1; z <= z2; z++) {
      for (let x = x1; x <= x2; x++) {
        const c = Math.floor((x - bounds.x1) / cellSize);
        const r = Math.floor((z - bounds.z1) / cellSize);
        if (r < 0 || r >= rows || c < 0 || c >= cols) continue;
        if (overwrite || grid[r][c] === '·') grid[r][c] = ch;
      }
    }
  };

  mark(plaza.x1, plaza.z1, plaza.x2, plaza.z2, '▒');
  const legend = new Map<string, string>();
  for (const b of result.buildings) {
    const ch = b.category[0].toUpperCase();
    legend.set(ch, b.category);
    mark(b.pos[0], b.pos[2], b.pos[0] + b.footprint[0] - 1, b.pos[2] + b.footprint[1] - 1, ch);
  }

  const legendText = [...legend.entries()].map(([ch, cat]) => `${ch}=${cat}`).join(', ');
  return (
    grid.map((row) => row.join('')).join('\n') +
    `\n(1 символ ≈ ${cellSize}×${cellSize} блоков; ▒ — площадь, · — свободно; ${legendText})`
  );
}

function pairs(edges: number[]): Array<[number, number]> {
  const out: Array<[number, number]> = [];
  for (let i = 0; i + 1 < edges.length; i += 2) out.push([edges[i], edges[i + 1]]);
  return out;
}

interface CatalogEntry { bp: Blueprint; weight: number }

function buildCatalog(
  lib: ContentLibrary,
  style: StylePack,
  mix: Record<string, number>,
  only?: string[],
): CatalogEntry[] {
  const out: CatalogEntry[] = [];
  for (const bp of lib.blueprints.values()) {
    if (only?.length && !only.includes(bp.id)) continue;
    const weightByCategory = mix[bp.category] ?? mix[bp.id];
    if (weightByCategory === undefined) continue;
    if (bp.styleHints?.length) {
      const matches = bp.styleHints.some(
        (h) => h === style.id || style.tags?.includes(h) || h === style.season,
      );
      if (!matches && !bp.styleHints.includes('any')) continue;
    }
    out.push({ bp, weight: weightByCategory });
  }
  return out;
}

/** Styles name their landmark loosely ("mill", "tower"); map that onto real blueprints. */
const LANDMARK_ALIASES: Record<string, string[]> = {
  mill: ['windmill', 'watermill'],
  tower: ['watchtower', 'keep'],
  statue: ['statue_plinth', 'fountain'],
  market: ['market_stall'],
  temple: ['church'],
  hall: ['town_hall'],
  fountain: ['fountain', 'well'],
};

function pickBlueprint(
  lib: ContentLibrary,
  ids: string[],
  _style: StylePack,
  rng: Rng,
): Blueprint | null {
  const tried = new Set<string>();
  const queue = [...ids];
  for (const id of ids) queue.push(...(LANDMARK_ALIASES[id] ?? []));

  for (const id of queue) {
    if (tried.has(id)) continue;
    tried.add(id);
    const bp = lib.blueprints.get(id);
    if (bp) return bp;
  }
  // Last resort: any blueprint filed under that category.
  for (const id of ids) {
    const byCategory = [...lib.blueprints.values()].filter((b) => b.category === id);
    if (byCategory.length) return rng.pick(byCategory);
  }
  return null;
}

function pickFitting(
  catalog: CatalogEntry[],
  rng: Rng,
  maxWidth: number,
  maxDepth: number,
  rotation: Rotation,
): { bp: Blueprint; w: number; d: number } | null {
  const fitting = catalog
    .map((e) => {
      const [w, d] = rotatedFootprint(e.bp, rotation);
      return { ...e, w, d };
    })
    .filter((e) => e.w <= maxWidth && e.d <= maxDepth);
  if (!fitting.length) return null;
  const chosen = rng.weighted(fitting);
  return { bp: chosen.bp, w: chosen.w, d: chosen.d };
}

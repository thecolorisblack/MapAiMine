/** Parsing / composing Minecraft block-state strings and deriving material variants. */

export interface ParsedBlock {
  id: string;
  states: Record<string, string>;
}

export function parseBlock(input: string): ParsedBlock {
  const s = input.trim();
  const open = s.indexOf('[');
  if (open === -1) return { id: normalizeId(s), states: {} };
  const id = normalizeId(s.slice(0, open));
  const body = s.slice(open + 1, s.lastIndexOf(']'));
  const states: Record<string, string> = {};
  for (const part of body.split(',')) {
    const [k, v] = part.split('=');
    if (k && v !== undefined) states[k.trim()] = v.trim();
  }
  return { id, states };
}

export function normalizeId(id: string): string {
  const t = id.trim().toLowerCase();
  return t.includes(':') ? t : `minecraft:${t}`;
}

export function composeBlock(id: string, states: Record<string, string | number | boolean> = {}): string {
  const keys = Object.keys(states);
  if (keys.length === 0) return normalizeId(id);
  const body = keys
    .sort()
    .map((k) => `${k}=${String(states[k])}`)
    .join(',');
  return `${normalizeId(id)}[${body}]`;
}

export function withStates(block: string, extra: Record<string, string | number | boolean>): string {
  const parsed = parseBlock(block);
  return composeBlock(parsed.id, { ...parsed.states, ...extra });
}

/** Bare name without the namespace *and without block-state*, for suffix rules. */
export function bareId(id: string): string {
  const withoutStates = id.includes('[') ? id.slice(0, id.indexOf('[')) : id;
  return normalizeId(withoutStates).split(':')[1];
}

/**
 * Turn a full block into the stem used for its family's variants:
 *   oak_planks     -> oak
 *   stone_bricks   -> stone_brick
 *   deepslate_tiles-> deepslate_tile
 *   bricks         -> brick
 *   quartz_block   -> quartz
 *   cobblestone    -> cobblestone
 */
export function variantStem(id: string): string {
  let n = bareId(id);
  const direct: Record<string, string> = {
    quartz_block: 'quartz',
    purpur_block: 'purpur',
    bricks: 'brick',
    nether_bricks: 'nether_brick',
    red_nether_bricks: 'red_nether_brick',
    stone_bricks: 'stone_brick',
    mossy_stone_bricks: 'mossy_stone_brick',
    end_stone_bricks: 'end_stone_brick',
    prismarine_bricks: 'prismarine_brick',
    polished_blackstone_bricks: 'polished_blackstone_brick',
    deepslate_bricks: 'deepslate_brick',
    deepslate_tiles: 'deepslate_tile',
    mud_bricks: 'mud_brick',
    resin_bricks: 'resin_brick',
    hay_block: 'hay',
    snow_block: 'snow',
  };
  if (direct[n]) return direct[n];
  if (n.endsWith('_planks')) return n.slice(0, -'_planks'.length);
  if (n.endsWith('_bricks')) return n.slice(0, -1);
  if (n.endsWith('_tiles')) return n.slice(0, -1);
  if (n.endsWith('_wood')) return n.slice(0, -'_wood'.length);
  if (n.endsWith('_log')) return n.slice(0, -'_log'.length);
  if (n.endsWith('_hyphae')) return n.slice(0, -'_hyphae'.length);
  if (n.endsWith('_stem')) return n.slice(0, -'_stem'.length);
  return n;
}

/** Woods that actually have sign blocks. Stripped variants and stone families do not. */
const SIGN_WOODS = new Set([
  'oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry',
  'bamboo', 'crimson', 'warped', 'pale_oak',
]);

/** Variants that only exist for base wood types, never for stripped logs or stone. */
const WOOD_ONLY = new Set(['door', 'trapdoor', 'fence', 'gate', 'sign', 'wall_sign', 'button', 'pressure_plate']);

const WOODS = new Set([
  'oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry',
  'pale_oak', 'bamboo', 'crimson', 'warped',
]);

const COPPER = new Set([
  'copper', 'exposed_copper', 'weathered_copper', 'oxidized_copper',
  'waxed_copper', 'waxed_exposed_copper', 'waxed_weathered_copper', 'waxed_oxidized_copper',
]);

/**
 * Families that genuinely have a `_wall` block. Deriving one for anything else
 * produces ids like `packed_ice_wall`, which the server rejects outright — and a
 * single bad id fails the whole build request, so guessing is not acceptable here.
 */
const WALL_FAMILIES = new Set([
  'cobblestone', 'mossy_cobblestone', 'brick', 'prismarine', 'sandstone', 'red_sandstone',
  'stone_brick', 'mossy_stone_brick', 'granite', 'andesite', 'diorite', 'nether_brick',
  'red_nether_brick', 'end_stone_brick', 'blackstone', 'polished_blackstone',
  'polished_blackstone_brick', 'deepslate_brick', 'deepslate_tile', 'cobbled_deepslate',
  'polished_deepslate', 'mud_brick', 'tuff', 'polished_tuff', 'tuff_brick', 'resin_brick',
]);

/**
 * Best-effort derivation of a variant block when a style did not spell it out.
 * Returns null when nothing sensible can be derived, so the caller can fall back
 * to the full block and emit a warning instead of inventing a nonexistent id.
 */
export function inferVariant(fullBlock: string, variant: string): string | null {
  const id = bareId(fullBlock);
  let stem = variantStem(fullBlock);

  // `stripped_spruce_wood` is a fine wall material but there is no
  // `stripped_spruce_door` — the joinery variants come from the base wood.
  if (WOOD_ONLY.has(variant)) stem = stem.replace(/^stripped_/, '');

  switch (variant) {
    case 'full':
      return fullBlock;
    case 'stairs':
      return `minecraft:${stem}_stairs`;
    case 'slab':
      return `minecraft:${stem}_slab`;
    case 'wall':
      return WALL_FAMILIES.has(stem) ? `minecraft:${stem}_wall` : null;
    case 'fence':
      // Wooden fences use the wood name; nether brick fence is the odd one out.
      if (stem === 'nether_brick') return 'minecraft:nether_brick_fence';
      return WOODS.has(stem) ? `minecraft:${stem}_fence` : null;
    case 'gate':
      return WOODS.has(stem) ? `minecraft:${stem}_fence_gate` : null;
    case 'door':
      if (stem === 'iron') return 'minecraft:iron_door';
      return WOODS.has(stem) || COPPER.has(stem) ? `minecraft:${stem}_door` : null;
    case 'trapdoor':
      if (stem === 'iron') return 'minecraft:iron_trapdoor';
      return WOODS.has(stem) || COPPER.has(stem) ? `minecraft:${stem}_trapdoor` : null;
    case 'button':
      return WOODS.has(stem) || stem === 'stone' || stem === 'polished_blackstone'
        ? `minecraft:${stem}_button` : null;
    case 'pressure_plate':
      return WOODS.has(stem) || stem === 'stone' || stem === 'polished_blackstone'
        ? `minecraft:${stem}_pressure_plate` : null;
    case 'sign':
    case 'wall_sign': {
      const wood = SIGN_WOODS.has(stem) ? stem : 'oak';
      return `minecraft:${wood}${variant === 'wall_sign' ? '_wall_sign' : '_sign'}`;
    }
    case 'carpet':
      if (id.endsWith('_wool')) return `minecraft:${id.replace(/_wool$/, '_carpet')}`;
      if (id === 'moss_block') return 'minecraft:moss_carpet';
      return null;
    case 'pane':
      if (id === 'glass') return 'minecraft:glass_pane';
      if (id.endsWith('_stained_glass')) return `minecraft:${id}_pane`;
      if (id.endsWith('_glass')) return `minecraft:${id}_pane`;
      if (id.endsWith('_pane')) return fullBlock;
      return null;
    case 'axisX':
      return withStates(fullBlock, { axis: 'x' });
    case 'axisY':
      return withStates(fullBlock, { axis: 'y' });
    case 'axisZ':
      return withStates(fullBlock, { axis: 'z' });
    case 'hanging':
      if (id.endsWith('lantern')) return withStates(fullBlock, { hanging: 'true' });
      return null;
    default:
      return null;
  }
}

export type Facing = 'north' | 'south' | 'east' | 'west' | 'up' | 'down';

const CW: Record<string, Facing> = { north: 'east', east: 'south', south: 'west', west: 'north' };

/** Rotate a facing clockwise (seen from above) by `deg` (0/90/180/270). */
export function rotateFacing(facing: Facing, deg: number): Facing {
  if (facing === 'up' || facing === 'down') return facing;
  let f: 'north' | 'south' | 'east' | 'west' = facing;
  const steps = ((deg / 90) % 4 + 4) % 4;
  for (let i = 0; i < steps; i++) f = CW[f] as 'north' | 'south' | 'east' | 'west';
  return f;
}

export function mirrorFacing(facing: Facing, axis: 'x' | 'z'): Facing {
  if (facing === 'up' || facing === 'down') return facing;
  if (axis === 'x') return facing === 'east' ? 'west' : facing === 'west' ? 'east' : facing;
  return facing === 'north' ? 'south' : facing === 'south' ? 'north' : facing;
}

export function rotateAxis(axis: string, deg: number): string {
  if (axis === 'y') return 'y';
  const steps = ((deg / 90) % 4 + 4) % 4;
  return steps % 2 === 1 ? (axis === 'x' ? 'z' : 'x') : axis;
}

/**
 * Blocks whose visual state depends on neighbours. They are re-applied in a second
 * pass with physics enabled so fences link up and stair corners resolve themselves.
 */
const CONNECTING = /(_fence|_fence_gate|_wall|_pane|_stairs|iron_bars|chain|glass_pane|_bars)$/;

export function isConnecting(block: string): boolean {
  const id = bareId(block);
  return CONNECTING.test(id) || id === 'iron_bars' || id === 'chain';
}

const DOOR_RE = /_door$/;
export function isDoor(block: string): boolean {
  return DOOR_RE.test(bareId(block));
}

/** Levenshtein distance, used to suggest corrections for bad block ids. */
export function editDistance(a: string, b: string): number {
  const m = a.length, n = b.length;
  if (!m) return n;
  if (!n) return m;
  let prev = Array.from({ length: n + 1 }, (_, i) => i);
  const cur = new Array<number>(n + 1);
  for (let i = 1; i <= m; i++) {
    cur[0] = i;
    for (let j = 1; j <= n; j++) {
      cur[j] = Math.min(
        prev[j] + 1,
        cur[j - 1] + 1,
        prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1),
      );
    }
    prev = cur.slice();
  }
  return prev[n];
}

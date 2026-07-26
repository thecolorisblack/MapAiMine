import type { ScatterEntry } from '../protocol.js';
import type { FloraEntry } from '../content/types.js';
import { bareId, parseBlock, withStates } from './blockstate.js';

/**
 * Bukkit's TreeType enum does not contain the names people expect. Plain oak is
 * `TREE`, not `OAK`; the aliases below let style packs stay readable.
 */
const TREE_ALIASES: Record<string, string> = {
  OAK: 'TREE',
  OAK_TREE: 'TREE',
  BIG_OAK: 'BIG_TREE',
  SPRUCE: 'REDWOOD',
  TALL_SPRUCE: 'TALL_REDWOOD',
  MEGA_SPRUCE: 'MEGA_REDWOOD',
  PINE: 'REDWOOD',
  JUNGLE_SMALL: 'SMALL_JUNGLE',
  CHERRY_TREE: 'CHERRY',
  BIRCH_TREE: 'BIRCH',
};

/**
 * Two-block-tall plants. Scattering only the lower half makes the block pop off
 * the next time it receives an update, so they are emitted as tiny structures.
 */
const TALL_PLANTS = new Set([
  'sunflower', 'lilac', 'rose_bush', 'peony', 'tall_grass', 'large_fern',
  'tall_seagrass', 'pitcher_plant',
]);

export function normalizeTreeType(tree: string): string {
  const upper = tree.toUpperCase();
  return TREE_ALIASES[upper] ?? upper;
}

/** Style flora entries -> bridge scatter entries, with the quirks smoothed out. */
export function toScatterEntries(entries: FloraEntry[]): ScatterEntry[] {
  return entries.map((f) => {
    if (f.tree) return { weight: f.weight, tree: normalizeTreeType(f.tree) };
    if (f.blueprint) return { weight: f.weight, blueprint: f.blueprint };
    if (f.block) return { weight: f.weight, ...blockEntry(f.block) };
    return { weight: f.weight, block: 'minecraft:short_grass' };
  });
}

/** Same normalisation for a raw block string coming straight from tool arguments. */
export function blockEntry(block: string): Pick<ScatterEntry, 'block' | 'structure'> {
  const parsed = parseBlock(block);
  if (!TALL_PLANTS.has(bareId(parsed.id))) return { block };
  return {
    structure: {
      size: [1, 2, 1],
      palette: [withStates(parsed.id, { half: 'lower' }), withStates(parsed.id, { half: 'upper' })],
      data: '0,1',
    },
  };
}

/** Minecraft has no "snow" weather — cold biomes render rain as snowfall. */
export function normalizeWeather(weather: string | undefined): 'clear' | 'rain' | 'thunder' | undefined {
  if (!weather) return undefined;
  const w = weather.toLowerCase();
  if (w === 'snow' || w === 'rain') return 'rain';
  if (w === 'thunder' || w === 'storm') return 'thunder';
  return 'clear';
}

/** TypeScript mirror of docs/SCHEMAS.md. */

export interface I18nText { ru?: string; en?: string; [k: string]: string | undefined }
export type MaybeI18n = string | I18nText;
export type SignText = string[] | { ru?: string[]; en?: string[] };

export interface WeightedBlock { block: string; weight?: number }

/** One palette role's blocks, plus optional explicit variants. */
export interface Material {
  full: WeightedBlock[];
  stairs?: string;
  slab?: string;
  wall?: string;
  fence?: string;
  gate?: string;
  door?: string;
  trapdoor?: string;
  button?: string;
  pressure_plate?: string;
  sign?: string;
  wall_sign?: string;
  axisX?: string;
  axisY?: string;
  axisZ?: string;
  hanging?: string;
  carpet?: string;
  pane?: string;
  [variant: string]: unknown;
}

export type Role =
  | 'foundation' | 'wall_primary' | 'wall_secondary' | 'wall_accent' | 'beam' | 'pillar'
  | 'floor' | 'floor_accent' | 'ceiling' | 'roof_primary' | 'roof_secondary' | 'roof_support'
  | 'window' | 'window_frame' | 'door' | 'gate' | 'fence' | 'railing'
  | 'path_primary' | 'path_secondary' | 'plaza' | 'ground_top' | 'ground_under' | 'water'
  | 'light' | 'light_ground' | 'decor' | 'banner' | 'carpet' | 'plant' | 'crop'
  | 'log' | 'foliage' | 'snow';

export const REQUIRED_ROLES: Role[] = [
  'foundation', 'wall_primary', 'wall_secondary', 'beam', 'floor', 'roof_primary',
  'window', 'door', 'fence', 'path_primary', 'ground_top', 'ground_under', 'light',
];

/** Role → role fallback chain, from docs/SCHEMAS.md. */
export const ROLE_FALLBACK: Partial<Record<Role, Role>> = {
  foundation: 'wall_primary',
  wall_secondary: 'wall_primary',
  wall_accent: 'wall_secondary',
  beam: 'wall_accent',
  pillar: 'beam',
  floor: 'wall_secondary',
  floor_accent: 'floor',
  ceiling: 'floor',
  roof_secondary: 'roof_primary',
  roof_support: 'beam',
  window_frame: 'wall_accent',
  door: 'wall_secondary',
  gate: 'door',
  fence: 'wall_secondary',
  railing: 'fence',
  path_secondary: 'path_primary',
  plaza: 'path_primary',
  light_ground: 'light',
  decor: 'wall_accent',
  log: 'beam',
};

export interface FloraEntry {
  weight?: number;
  block?: string;
  tree?: string;
  blueprint?: string;
}

export interface StylePack {
  id: string;
  name: MaybeI18n;
  description?: MaybeI18n;
  tags?: string[];
  season?: 'summer' | 'autumn' | 'winter' | 'spring' | 'none';
  author?: string;
  version?: number;

  materials: Partial<Record<Role, Material>> & Record<string, Material | undefined>;

  environment?: {
    time?: number;
    timeLock?: boolean;
    weather?: 'clear' | 'rain' | 'thunder';
    snowLayer?: boolean;
    biomePaint?: string | null;
  };

  ground?: {
    top?: WeightedBlock[];
    under?: WeightedBlock[];
    path?: WeightedBlock[];
    pathEdge?: WeightedBlock[];
    plaza?: WeightedBlock[];
  };

  flora?: {
    trees?: FloraEntry[];
    treeDensity?: number;
    ground?: FloraEntry[];
    groundDensity?: number;
    rocks?: FloraEntry[];
    rockDensity?: number;
  };

  building?: {
    roofShape?: 'gable' | 'hip' | 'conical' | 'flat' | 'pagoda' | 'dome';
    roofPitch?: number;
    wallStyle?: 'solid' | 'timber_frame' | 'stone_base' | 'log_cabin';
    storyHeight?: number;
    foundationDepth?: number;
    overhang?: number;
    chimney?: boolean;
  };

  settlement?: {
    roadWidth?: number;
    plazaSize?: [number, number];
    plotPadding?: number;
    density?: number;
    wall?: boolean;
    wallHeight?: number;
    lampSpacing?: number;
    buildingMix?: Record<string, number>;
    landmark?: string;
  };

  lighting?: { role?: Role; spacing?: number; height?: number; onPosts?: boolean };

  signs?: Record<string, SignText>;
}

/* ─────────────────────────── Blueprints ─────────────────────────── */

export type Facing = 'north' | 'south' | 'east' | 'west' | 'up' | 'down';

export interface LegendCell {
  /** Leave whatever is already in the world. */
  skip?: boolean;
  /** Raw block string, bypasses the style. */
  block?: string;
  role?: Role | string;
  variant?: string;
  facing?: Facing;
  half?: 'top' | 'bottom';
  axis?: 'x' | 'y' | 'z';
  /** Probability the cell is placed at all (0..1). Adds natural wear/variation. */
  chance?: number;
  waterlogged?: boolean;
  /** Named prop from content/props. */
  prop?: string;
  /** Extra block-state properties merged into the resolved block. */
  states?: Record<string, string | number | boolean>;
}

export interface BlueprintLayer { y: number; rows: string[] }

export interface Blueprint {
  id: string;
  name: MaybeI18n;
  category: string;
  tags?: string[];
  size: [number, number, number];
  facing?: Facing;
  groundLevel?: number;
  footprint?: [number, number];
  styleHints?: string[];
  minStyleRoles?: string[];
  legend: Record<string, LegendCell>;
  layers: BlueprintLayer[];
  signs?: Array<{ pos: [number, number, number]; variant?: string; facing?: Facing; textKey?: string; text?: string[]; block?: string }>;
  containers?: Array<{ pos: [number, number, number]; block: string; lootTable?: string; items?: Array<{ slot: number; id: string; count?: number }> }>;
  entities?: Array<{ pos: [number, number, number]; entity: string; profession?: string; name?: string; noAI?: boolean }>;
  lights?: Array<[number, number, number]>;
  clearance?: { above?: number };
  prepare?: { flatten?: boolean; padding?: number; foundationTo?: 'ground' | 'none' };
  /** Set for props loaded from content/props. */
  isProp?: boolean;
}

export interface ContentLibrary {
  styles: Map<string, StylePack>;
  blueprints: Map<string, Blueprint>;
  props: Map<string, Blueprint>;
  warnings: string[];
  dirs: string[];
}

export function textOf(v: MaybeI18n | undefined, lang: 'ru' | 'en'): string {
  if (!v) return '';
  if (typeof v === 'string') return v;
  return v[lang] ?? v.en ?? v.ru ?? Object.values(v).find((x): x is string => !!x) ?? '';
}

export function signLines(v: SignText | undefined, lang: 'ru' | 'en'): string[] {
  if (!v) return [];
  if (Array.isArray(v)) return v;
  return v[lang] ?? v.en ?? v.ru ?? [];
}

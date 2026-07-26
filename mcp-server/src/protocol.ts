/**
 * TypeScript mirror of docs/PROTOCOL.md (Bridge Protocol v1).
 *
 * These types are the wire format spoken to the MapAiMine bridge plugin.
 * The RCON transport implements a documented subset of the same shapes.
 */

export type Pos = [number, number, number];
export type Pos2 = [number, number];

/** A block is either a literal block-state string or a weighted choice list. */
export type BlockRef = string | { choices: Array<{ block: string; weight?: number }> };

export const PROTOCOL_VERSION = 1;

export interface Limits {
  maxOpsPerRequest: number;
  maxBlocksPerOp: number;
  maxRegionVolume: number;
  blocksPerTick: number;
  maxUndoHistory: number;
  surveyMaxPoints: number;
}

export type Capability =
  | 'survey'
  | 'undo'
  | 'jobs'
  | 'worldCreate'
  | 'schematic'
  | 'entities'
  | 'containers'
  | 'commands'
  | 'trees';

export interface Health {
  protocol: number;
  plugin: string;
  server: string;
  minecraftVersion: string;
  worlds: string[];
  capabilities: Capability[];
  limits: Limits;
}

export interface WorldInfo {
  name: string;
  environment: string;
  seed?: number;
  worldType?: string;
  spawn: Pos;
  time?: number;
  weather?: string;
  difficulty?: string;
  players?: number;
  loadedChunks?: number;
  minY: number;
  maxY: number;
}

export interface SurveyResult {
  world: string;
  origin: Pos2;
  size: Pos2;
  step: number;
  cols: number;
  rows: number;
  heightmap: number[][];
  surface?: string[][];
  biome?: string[][];
  water?: number[][];
  stats: {
    minY: number;
    maxY: number;
    avgY: number;
    slopeMax: number;
    waterFraction: number;
    surfaceHistogram: Record<string, number>;
    biomeHistogram: Record<string, number>;
    flatnessScore: number;
    suggestedBuildY: number;
  };
}

export interface ProbeResult {
  blocks: Array<{ pos: Pos; block: string; light?: number; biome?: string }>;
}

export interface JobStatus {
  jobId: string;
  label?: string;
  status: 'queued' | 'running' | 'done' | 'failed' | 'cancelled';
  progress?: {
    opsDone: number;
    opsTotal: number;
    blocksChanged: number;
    estimatedBlocks: number;
    percent: number;
    checkpoint?: string;
  };
  undoId?: string;
  blocksChanged?: number;
  estimatedBlocks?: number;
  elapsedMs?: number;
  etaMs?: number;
  warnings?: string[];
  error?: string | null;
}

/* ─────────────────────────── Operations ─────────────────────────── */

export interface OpBase {
  type: string;
}

export interface SetOp extends OpBase { type: 'set'; pos: Pos; block: BlockRef }

export interface BlocksListOp extends OpBase {
  type: 'blocks';
  blocks: Array<{ pos: Pos; block: string }>;
}
export interface BlocksPackedOp extends OpBase {
  type: 'blocks';
  origin: Pos;
  palette: string[];
  size: Pos;
  /** palette indices, y -> z -> x order, run-length encoded as "3x1,0,2" */
  data: string;
}
export type BlocksOp = BlocksListOp | BlocksPackedOp;

export type FillMode = 'replace' | 'keep' | 'hollow' | 'outline' | 'destroy';
export interface FillOp extends OpBase {
  type: 'fill';
  from: Pos; to: Pos; block: BlockRef;
  mode?: FillMode;
  filter?: string[];
}

export interface SphereOp extends OpBase {
  type: 'sphere'; center: Pos; radius: number | Pos; block: BlockRef; hollow?: boolean;
}
export interface CylinderOp extends OpBase {
  type: 'cylinder'; base: Pos; radius: number; height: number;
  axis?: 'x' | 'y' | 'z'; block: BlockRef; hollow?: boolean;
}
export interface PyramidOp extends OpBase {
  type: 'pyramid'; base: Pos; size: number; block: BlockRef; hollow?: boolean; inverted?: boolean;
}
export interface ConeOp extends OpBase {
  type: 'cone'; base: Pos; radius: number; height: number; block: BlockRef; hollow?: boolean;
}
export interface LineOp extends OpBase {
  type: 'line'; from: Pos; to: Pos; block: BlockRef; thickness?: number;
}
export interface WallsOp extends OpBase { type: 'walls'; from: Pos; to: Pos; block: BlockRef }
export interface TorusOp extends OpBase {
  type: 'torus'; center: Pos; radius: number; tube: number; block: BlockRef;
}
export interface ReplaceOp extends OpBase {
  type: 'replace'; from: Pos; to: Pos; find: string[]; block: BlockRef;
}
export interface PaintOp extends OpBase {
  type: 'paint'; from: Pos; to: Pos; block: BlockRef;
  depth?: number; onlyOn?: string[]; seed?: number;
}
export interface ScatterEntry {
  weight?: number;
  block?: string;
  structure?: { size: Pos; palette: string[]; data: string };
  blueprint?: string;
  tree?: string;
}
export interface ScatterOp extends OpBase {
  type: 'scatter'; from: Pos; to: Pos; density: number; seed?: number;
  minSpacing?: number; entries: ScatterEntry[];
  onlyOn?: string[]; needsAir?: boolean; avoidWater?: boolean;
}
export interface SmoothOp extends OpBase {
  type: 'smooth'; from: Pos; to: Pos; iterations?: number; strength?: number;
}
export interface FlattenOp extends OpBase {
  type: 'flatten'; from: Pos; to: Pos; y: number;
  surface?: string; fill?: string; clearAbove?: number;
}
export interface RaiseOp extends OpBase {
  type: 'raise'; from: Pos; to: Pos; amount: number; falloff?: 'none' | 'smooth';
}
export interface TerraceOp extends OpBase { type: 'terrace'; from: Pos; to: Pos; step: number }
export interface ClearOp extends OpBase { type: 'clear'; from: Pos; to: Pos; keepGround?: boolean }

export interface SignOp extends OpBase {
  type: 'sign'; pos: Pos; block: string;
  front?: string[]; back?: string[]; glowing?: boolean; color?: string;
}
export interface ContainerOp extends OpBase {
  type: 'container'; pos: Pos; block: string;
  items?: Array<{ slot: number; id: string; count?: number; name?: string; enchants?: Record<string, number> }>;
  lootTable?: string;
}
export interface HeadOp extends OpBase { type: 'head'; pos: Pos; texture?: string; owner?: string }
export interface SpawnerOp extends OpBase {
  type: 'spawner'; pos: Pos; entity: string; delay?: number; maxNearby?: number;
}
export interface EntityOp extends OpBase {
  type: 'entity'; pos: [number, number, number]; entity: string;
  name?: string; nameVisible?: boolean; profession?: string; noAI?: boolean;
  persistent?: boolean; tags?: string[]; snbt?: string;
}
export interface CommandOp extends OpBase { type: 'command'; command: string }
export interface CheckpointOp extends OpBase { type: 'checkpoint'; name: string }

export type Op =
  | SetOp | BlocksOp | FillOp | SphereOp | CylinderOp | PyramidOp | ConeOp | LineOp
  | WallsOp | TorusOp | ReplaceOp | PaintOp | ScatterOp | SmoothOp | FlattenOp
  | RaiseOp | TerraceOp | ClearOp | SignOp | ContainerOp | HeadOp | SpawnerOp
  | EntityOp | CommandOp | CheckpointOp;

export interface OpsRequest {
  world: string;
  label?: string;
  ops: Op[];
  undo?: boolean;
  dryRun?: boolean;
  async?: boolean;
  physics?: boolean;
  lightUpdate?: boolean;
}

export interface OpsResult {
  jobId: string;
  status: JobStatus['status'];
  estimatedBlocks?: number;
  blocksChanged?: number;
  undoId?: string;
  elapsedMs?: number;
  warnings?: string[];
}

export interface CreateWorldRequest {
  name: string;
  environment?: 'normal' | 'nether' | 'the_end';
  worldType?: 'normal' | 'flat' | 'large_biomes' | 'amplified' | 'void';
  seed?: number;
  generateStructures?: boolean;
  flatPreset?: string;
  biome?: string;
}

export interface WorldSettingsRequest {
  time?: number;
  timeLock?: boolean;
  weather?: 'clear' | 'rain' | 'thunder';
  weatherLock?: boolean;
  difficulty?: string;
  pvp?: boolean;
  spawnRadius?: number;
  gameRules?: Record<string, string | number | boolean>;
  keepLoaded?: boolean;
}

export interface SetSpawnRequest {
  x: number; y: number; z: number; yaw?: number; pitch?: number; safe?: boolean;
}

export interface CaptureRequest {
  world: string; from: Pos; to: Pos; name: string; trimAir?: boolean;
}

export interface RawStructure {
  id: string;
  kind: 'raw';
  size: Pos;
  palette: string[];
  data: string;
  blockEntities?: unknown[];
  entities?: unknown[];
  capturedAt?: number;
  sourceWorld?: string;
}

/** Thrown when a transport cannot honour a request (mainly the RCON fallback). */
export class UnsupportedError extends Error {
  constructor(feature: string, hint?: string) {
    super(
      `Feature "${feature}" is not available on the current transport.` +
        (hint ? ` ${hint}` : ''),
    );
    this.name = 'UnsupportedError';
  }
}

/** Thrown for structured errors coming back from the bridge. */
export class BridgeError extends Error {
  constructor(public code: string, message: string, public hint?: string, public status?: number) {
    super(message);
    this.name = 'BridgeError';
  }
}

import type {
  Capability, CaptureRequest, CreateWorldRequest, Health, JobStatus, OpsRequest, OpsResult,
  Pos, ProbeResult, RawStructure, SetSpawnRequest, SurveyResult, WorldInfo, WorldSettingsRequest,
} from '../protocol.js';

export interface SurveyRequest {
  world: string;
  x1: number; z1: number; x2: number; z2: number;
  step?: number;
  include?: Array<'height' | 'surface' | 'biome' | 'water' | 'light'>;
}

export interface ValidateResult {
  results: Array<{ input: string; valid: boolean; normalized?: string; suggestions?: string[] }>;
}

export interface UndoEntry { undoId: string; label?: string; blocks: number; at: number }

/** Everything the tool layer is allowed to ask of a Minecraft server. */
export interface Bridge {
  readonly kind: 'http' | 'rcon';
  /** Human-readable transport description, shown in `mapaimine_status`. */
  describe(): string;
  connect(): Promise<Health>;
  health(): Health | null;
  supports(cap: Capability): boolean;
  close(): Promise<void>;

  worlds(): Promise<WorldInfo[]>;
  createWorld(req: CreateWorldRequest): Promise<WorldInfo>;
  worldSettings(world: string, req: WorldSettingsRequest): Promise<void>;
  setSpawn(world: string, req: SetSpawnRequest): Promise<void>;
  setBorder(world: string, req: { center: [number, number]; size: number; warningDistance?: number }): Promise<void>;

  survey(req: SurveyRequest): Promise<SurveyResult>;
  probe(world: string, points: Pos[]): Promise<ProbeResult>;
  materials(filter?: string): Promise<string[]>;
  validateBlocks(blocks: string[]): Promise<ValidateResult>;

  ops(req: OpsRequest): Promise<OpsResult>;
  job(id: string): Promise<JobStatus>;
  jobs(): Promise<JobStatus[]>;
  cancelJob(id: string): Promise<void>;

  undo(undoId?: string): Promise<{ blocksRestored: number }>;
  undoList(): Promise<UndoEntry[]>;

  capture(req: CaptureRequest): Promise<RawStructure>;
  listCaptures(): Promise<string[]>;
  getCapture(name: string): Promise<RawStructure>;

  teleport(player: string, loc: { world?: string; x: number; y: number; z: number; yaw?: number; pitch?: number }): Promise<void>;
  gamemode(player: string, mode: string): Promise<void>;
  broadcast(message: string): Promise<void>;
  players(): Promise<Array<{ name: string; world: string; pos: Pos }>>;
}

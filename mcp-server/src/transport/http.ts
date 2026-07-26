import {
  BridgeError, type Capability, type CaptureRequest, type CreateWorldRequest, type Health,
  type JobStatus, type OpsRequest, type OpsResult, type Pos, type ProbeResult, type RawStructure,
  type SetSpawnRequest, type SurveyResult, type WorldInfo, type WorldSettingsRequest,
} from '../protocol.js';
import type { Logger } from '../config.js';
import type { Bridge, SurveyRequest, UndoEntry, ValidateResult } from './types.js';

/** Talks to the MapAiMine bridge plugin over HTTP — the full-feature transport. */
export class HttpBridge implements Bridge {
  readonly kind = 'http' as const;
  private cachedHealth: Health | null = null;

  constructor(
    private baseUrl: string,
    private token: string | undefined,
    private timeoutMs: number,
    private log: Logger,
  ) {}

  describe(): string {
    const h = this.cachedHealth;
    return h
      ? `HTTP bridge ${this.baseUrl} — ${h.plugin} on ${h.server} ${h.minecraftVersion}`
      : `HTTP bridge ${this.baseUrl} (not connected)`;
  }

  health(): Health | null {
    return this.cachedHealth;
  }

  supports(cap: Capability): boolean {
    return this.cachedHealth?.capabilities?.includes(cap) ?? false;
  }

  async connect(): Promise<Health> {
    const health = await this.request<Health>('GET', '/health');
    if (health.protocol !== 1) {
      this.log.warn(
        `bridge speaks protocol v${health.protocol}, this MCP server implements v1 — behaviour may be off`,
      );
    }
    this.cachedHealth = health;
    return health;
  }

  async close(): Promise<void> {
    /* stateless */
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const url = `${this.baseUrl}/api/v1${path}`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    const headers: Record<string, string> = { accept: 'application/json' };
    if (this.token) headers.authorization = `Bearer ${this.token}`;
    if (body !== undefined) headers['content-type'] = 'application/json';

    this.log.debug(method, path, body ? JSON.stringify(body).slice(0, 400) : '');

    let res: Response;
    try {
      res = await fetch(url, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
      });
    } catch (err) {
      throw new BridgeError(
        'UNREACHABLE',
        `Cannot reach the MapAiMine bridge at ${this.baseUrl}: ${(err as Error).message}`,
        'Is the server running and the plugin installed? Check MAPAIMINE_BRIDGE_URL.',
      );
    } finally {
      clearTimeout(timer);
    }

    const text = await res.text();
    let parsed: { ok?: boolean; data?: T; error?: { code: string; message: string; hint?: string } };
    try {
      parsed = text ? JSON.parse(text) : {};
    } catch {
      throw new BridgeError('BAD_RESPONSE', `Bridge returned non-JSON (HTTP ${res.status}): ${text.slice(0, 200)}`);
    }
    if (!res.ok || parsed.ok === false) {
      const e = parsed.error;
      throw new BridgeError(
        e?.code ?? `HTTP_${res.status}`,
        e?.message ?? `Bridge error (HTTP ${res.status})`,
        e?.hint,
        res.status,
      );
    }
    return parsed.data as T;
  }

  worlds(): Promise<WorldInfo[]> {
    return this.request<{ worlds: WorldInfo[] }>('GET', '/worlds').then((d) => d.worlds);
  }

  createWorld(req: CreateWorldRequest): Promise<WorldInfo> {
    return this.request<WorldInfo>('POST', '/worlds', req);
  }

  worldSettings(world: string, req: WorldSettingsRequest): Promise<void> {
    return this.request<void>('POST', `/worlds/${encodeURIComponent(world)}/settings`, req);
  }

  setSpawn(world: string, req: SetSpawnRequest): Promise<void> {
    return this.request<void>('POST', `/worlds/${encodeURIComponent(world)}/spawn`, req);
  }

  setBorder(world: string, req: { center: [number, number]; size: number; warningDistance?: number }): Promise<void> {
    return this.request<void>('POST', `/worlds/${encodeURIComponent(world)}/border`, req);
  }

  survey(req: SurveyRequest): Promise<SurveyResult> {
    const q = new URLSearchParams({
      world: req.world,
      x1: String(req.x1), z1: String(req.z1), x2: String(req.x2), z2: String(req.z2),
    });
    if (req.step) q.set('step', String(req.step));
    if (req.include?.length) q.set('include', req.include.join(','));
    return this.request<SurveyResult>('GET', `/survey?${q.toString()}`);
  }

  probe(world: string, points: Pos[]): Promise<ProbeResult> {
    return this.request<ProbeResult>('POST', '/probe', { world, points });
  }

  materials(filter?: string): Promise<string[]> {
    const q = filter ? `?filter=${encodeURIComponent(filter)}` : '';
    return this.request<{ blocks: string[] }>('GET', `/materials${q}`).then((d) => d.blocks);
  }

  validateBlocks(blocks: string[]): Promise<ValidateResult> {
    return this.request<ValidateResult>('POST', '/validate', { blocks });
  }

  ops(req: OpsRequest): Promise<OpsResult> {
    return this.request<OpsResult>('POST', '/ops', req);
  }

  job(id: string): Promise<JobStatus> {
    return this.request<JobStatus>('GET', `/jobs/${encodeURIComponent(id)}`);
  }

  jobs(): Promise<JobStatus[]> {
    return this.request<{ jobs: JobStatus[] }>('GET', '/jobs').then((d) => d.jobs ?? []);
  }

  cancelJob(id: string): Promise<void> {
    return this.request<void>('DELETE', `/jobs/${encodeURIComponent(id)}`);
  }

  undo(undoId?: string): Promise<{ blocksRestored: number }> {
    return this.request<{ blocksRestored: number }>('POST', '/undo', undoId ? { undoId } : {});
  }

  undoList(): Promise<UndoEntry[]> {
    return this.request<{ entries: UndoEntry[] }>('GET', '/undo').then((d) => d.entries ?? []);
  }

  capture(req: CaptureRequest): Promise<RawStructure> {
    return this.request<RawStructure>('POST', '/capture', req);
  }

  listCaptures(): Promise<string[]> {
    return this.request<{ captures: string[] }>('GET', '/captures').then((d) => d.captures ?? []);
  }

  getCapture(name: string): Promise<RawStructure> {
    return this.request<RawStructure>('GET', `/captures/${encodeURIComponent(name)}`);
  }

  teleport(player: string, loc: { world?: string; x: number; y: number; z: number; yaw?: number; pitch?: number }): Promise<void> {
    return this.request<void>('POST', `/players/${encodeURIComponent(player)}/teleport`, loc);
  }

  gamemode(player: string, mode: string): Promise<void> {
    return this.request<void>('POST', `/players/${encodeURIComponent(player)}/gamemode`, { mode });
  }

  broadcast(message: string): Promise<void> {
    return this.request<void>('POST', '/broadcast', { message });
  }

  players(): Promise<Array<{ name: string; world: string; pos: Pos }>> {
    return this.request<{ players: Array<{ name: string; world: string; pos: Pos }> }>('GET', '/players')
      .then((d) => d.players ?? []);
  }
}

import {
  UnsupportedError, type Capability, type CaptureRequest, type CreateWorldRequest, type Health,
  type JobStatus, type Op, type OpsRequest, type OpsResult, type Pos, type ProbeResult,
  type RawStructure, type SetSpawnRequest, type SurveyResult, type WorldInfo,
  type WorldSettingsRequest,
} from '../protocol.js';
import type { Logger } from '../config.js';
import { compressToRuns, estimateRequest, makePicker, rasterize } from '../build/raster.js';
import { RconClient } from './rcon-client.js';
import type { Bridge, SurveyRequest, UndoEntry, ValidateResult } from './types.js';

const NO_PLUGIN =
  'Install the MapAiMine bridge plugin to unlock this. RCON can only issue commands — it cannot read the world back.';

/**
 * Fallback transport for servers without the bridge plugin (including vanilla).
 * Geometry is rasterised locally and pushed as /fill and /setblock commands.
 * Everything that needs to *read* the world is unavailable by design.
 */
export class RconBridge implements Bridge {
  readonly kind = 'rcon' as const;
  private client: RconClient;
  private cachedHealth: Health | null = null;
  private jobCounter = 0;
  private finishedJobs = new Map<string, JobStatus>();

  constructor(
    private host: string,
    private port: number,
    password: string,
    private log: Logger,
    timeoutMs = 20_000,
  ) {
    this.client = new RconClient(host, port, password, timeoutMs);
  }

  describe(): string {
    return `RCON ${this.host}:${this.port} (reduced feature set — no world reads, no undo)`;
  }

  health(): Health | null {
    return this.cachedHealth;
  }

  supports(cap: Capability): boolean {
    return ['entities', 'containers', 'commands'].includes(cap);
  }

  async connect(): Promise<Health> {
    await this.client.connect();
    let version = 'unknown';
    try {
      const raw = await this.client.command('version');
      const m = raw.match(/1\.\d+(\.\d+)?/);
      if (m) version = m[0];
    } catch {
      /* `version` is not a vanilla command; ignore */
    }
    this.cachedHealth = {
      protocol: 1,
      plugin: 'none (RCON fallback)',
      server: 'unknown',
      minecraftVersion: version,
      worlds: [],
      capabilities: ['entities', 'containers', 'commands'],
      limits: {
        maxOpsPerRequest: 512,
        maxBlocksPerOp: 250_000,
        maxRegionVolume: 1_000_000,
        blocksPerTick: 0,
        maxUndoHistory: 0,
        surveyMaxPoints: 0,
      },
    };
    return this.cachedHealth;
  }

  async close(): Promise<void> {
    await this.client.close();
  }

  /* ── world reads: not possible over RCON ── */
  async worlds(): Promise<WorldInfo[]> { throw new UnsupportedError('list worlds', NO_PLUGIN); }
  async createWorld(_req: CreateWorldRequest): Promise<WorldInfo> { throw new UnsupportedError('create world', NO_PLUGIN); }
  async survey(_req: SurveyRequest): Promise<SurveyResult> { throw new UnsupportedError('survey', NO_PLUGIN); }
  async probe(_world: string, _points: Pos[]): Promise<ProbeResult> { throw new UnsupportedError('probe', NO_PLUGIN); }
  async materials(_filter?: string): Promise<string[]> { throw new UnsupportedError('materials listing', NO_PLUGIN); }
  async validateBlocks(_blocks: string[]): Promise<ValidateResult> { throw new UnsupportedError('block validation', NO_PLUGIN); }
  async undo(_id?: string): Promise<{ blocksRestored: number }> { throw new UnsupportedError('undo', NO_PLUGIN); }
  async undoList(): Promise<UndoEntry[]> { throw new UnsupportedError('undo history', NO_PLUGIN); }
  async capture(_req: CaptureRequest): Promise<RawStructure> { throw new UnsupportedError('capture', NO_PLUGIN); }
  async listCaptures(): Promise<string[]> { throw new UnsupportedError('saved structures', NO_PLUGIN); }
  async getCapture(_name: string): Promise<RawStructure> { throw new UnsupportedError('saved structures', NO_PLUGIN); }
  async players(): Promise<Array<{ name: string; world: string; pos: Pos }>> { throw new UnsupportedError('player list', NO_PLUGIN); }

  async worldSettings(world: string, req: WorldSettingsRequest): Promise<void> {
    const cmds: string[] = [];
    if (req.time !== undefined) cmds.push(`time set ${Math.round(req.time)}`);
    if (req.timeLock !== undefined) cmds.push(`gamerule doDaylightCycle ${!req.timeLock}`);
    if (req.weather) cmds.push(`weather ${req.weather}`);
    if (req.weatherLock !== undefined) cmds.push(`gamerule doWeatherCycle ${!req.weatherLock}`);
    if (req.difficulty) cmds.push(`difficulty ${req.difficulty.toLowerCase()}`);
    for (const [k, v] of Object.entries(req.gameRules ?? {})) cmds.push(`gamerule ${k} ${v}`);
    await this.runAll(cmds.map((c) => this.inWorld(world, c)));
  }

  async setSpawn(world: string, req: SetSpawnRequest): Promise<void> {
    await this.client.command(
      this.inWorld(world, `setworldspawn ${Math.floor(req.x)} ${Math.floor(req.y)} ${Math.floor(req.z)}`),
    );
  }

  async setBorder(world: string, req: { center: [number, number]; size: number }): Promise<void> {
    await this.runAll([
      this.inWorld(world, `worldborder center ${req.center[0]} ${req.center[1]}`),
      this.inWorld(world, `worldborder set ${req.size}`),
    ]);
  }

  async ops(req: OpsRequest): Promise<OpsResult> {
    const jobId = `rcon_${++this.jobCounter}`;
    const started = Date.now();
    const warnings: string[] = [];
    const pick = makePicker(`${req.world}:${req.label ?? jobId}`);
    const commands: string[] = [];

    for (const [index, op] of req.ops.entries()) {
      const cmds = this.translate(op, req.world, pick, warnings, index);
      commands.push(...cmds);
    }

    const estimated = estimateRequest(req.ops);
    if (req.dryRun) {
      return {
        jobId, status: 'done', estimatedBlocks: estimated, blocksChanged: 0,
        warnings: [...warnings, `dry run: would issue ${commands.length} commands`],
      };
    }

    let issued = 0;
    for (const cmd of commands) {
      try {
        await this.client.command(cmd);
      } catch (err) {
        warnings.push(`command failed (${cmd.slice(0, 60)}…): ${(err as Error).message}`);
        if (warnings.length > 40) {
          warnings.push('too many failures, aborting this batch');
          break;
        }
      }
      issued++;
      // Give the server room to breathe on very large batches.
      if (issued % 200 === 0) await new Promise((r) => setTimeout(r, 50));
    }

    const status: JobStatus = {
      jobId,
      label: req.label,
      status: 'done',
      blocksChanged: estimated,
      elapsedMs: Date.now() - started,
      warnings,
    };
    this.finishedJobs.set(jobId, status);
    return {
      jobId, status: 'done', estimatedBlocks: estimated, blocksChanged: estimated,
      elapsedMs: status.elapsedMs, warnings,
    };
  }

  async job(id: string): Promise<JobStatus> {
    const job = this.finishedJobs.get(id);
    if (!job) throw new UnsupportedError('job tracking', 'RCON runs everything synchronously.');
    return job;
  }

  async jobs(): Promise<JobStatus[]> {
    return [...this.finishedJobs.values()];
  }

  async cancelJob(_id: string): Promise<void> {
    throw new UnsupportedError('job cancellation', 'RCON runs everything synchronously.');
  }

  async teleport(player: string, loc: { x: number; y: number; z: number }): Promise<void> {
    await this.client.command(`tp ${player} ${loc.x} ${loc.y} ${loc.z}`);
  }

  async gamemode(player: string, mode: string): Promise<void> {
    await this.client.command(`gamemode ${mode} ${player}`);
  }

  async broadcast(message: string): Promise<void> {
    await this.client.command(`say ${message}`);
  }

  /* ─────────────────── op → command translation ─────────────────── */

  /**
   * Multi-world servers expose worlds as dimensions only when the id is namespaced;
   * a plain Bukkit world name cannot be targeted from the console, so we run in the
   * default dimension and warn the operator instead of silently building elsewhere.
   */
  private inWorld(world: string, cmd: string): string {
    if (world && world.includes(':')) return `execute in ${world} run ${cmd}`;
    return cmd;
  }

  private translate(op: Op, world: string, pick: ReturnType<typeof makePicker>, warnings: string[], index: number): string[] {
    switch (op.type) {
      case 'checkpoint':
        return [];
      case 'command':
        return [this.inWorld(world, op.command.replace(/^\//, ''))];
      case 'sign': {
        const lines = (op.front ?? []).slice(0, 4);
        const messages = Array.from({ length: 4 }, (_, i) => JSON.stringify(JSON.stringify({ text: lines[i] ?? '' })));
        const nbt = `{front_text:{messages:[${messages.join(',')}]}}`;
        return [this.inWorld(world, `setblock ${op.pos.join(' ')} ${op.block}${nbt} replace`)];
      }
      case 'container': {
        let nbt = '';
        if (op.lootTable) {
          nbt = `{LootTable:"${op.lootTable}"}`;
        } else if (op.items?.length) {
          const items = op.items
            .map((it) => `{Slot:${it.slot}b,id:"${it.id}",count:${it.count ?? 1}}`)
            .join(',');
          nbt = `{Items:[${items}]}`;
        }
        return [this.inWorld(world, `setblock ${op.pos.join(' ')} ${op.block}${nbt} replace`)];
      }
      case 'spawner':
        return [this.inWorld(
          world,
          `setblock ${op.pos.join(' ')} minecraft:spawner{SpawnData:{entity:{id:"${op.entity}"}}} replace`,
        )];
      case 'entity': {
        const nbt = op.snbt ?? (op.name ? `{CustomName:'{"text":"${op.name}"}'}` : '');
        return [this.inWorld(world, `summon ${op.entity} ${op.pos.join(' ')} ${nbt}`.trim())];
      }
      case 'head':
        return [this.inWorld(world, `setblock ${op.pos.join(' ')} minecraft:player_head replace`)];
      case 'fill': {
        // A direct /fill is dramatically cheaper than rasterising, when it fits.
        const mode = op.mode ?? 'replace';
        if ((mode === 'replace' || mode === 'keep' || mode === 'destroy' || mode === 'hollow' || mode === 'outline')
            && typeof op.block === 'string') {
          const suffix =
            op.filter?.length === 1 ? `replace ${op.filter[0]}` :
            mode === 'replace' ? 'replace' : mode;
          if (op.filter && op.filter.length > 1) {
            warnings.push(`op[${index}]: RCON /fill supports only one filter block; used "${op.filter[0]}"`);
          }
          return this.chunkedFill(world, op.from, op.to, op.block, suffix);
        }
        break;
      }
      default:
        break;
    }

    const raster = rasterize(op, pick);
    if (!raster) {
      warnings.push(
        `op[${index}] "${op.type}" needs to read the world and is unavailable over RCON — install the bridge plugin`,
      );
      return [];
    }
    const runs = compressToRuns(raster);
    return runs.map((r) =>
      r.from[0] === r.to[0] && r.from[1] === r.to[1] && r.from[2] === r.to[2]
        ? this.inWorld(world, `setblock ${r.from.join(' ')} ${r.block} replace`)
        : this.inWorld(world, `fill ${r.from.join(' ')} ${r.to.join(' ')} ${r.block}`),
    );
  }

  /** /fill is capped at 32768 blocks per command, so slice the region. */
  private chunkedFill(world: string, from: Pos, to: Pos, block: string, suffix: string): string[] {
    const lo: Pos = [Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2])];
    const hi: Pos = [Math.max(from[0], to[0]), Math.max(from[1], to[1]), Math.max(from[2], to[2])];
    const out: string[] = [];
    const step = 30; // 30^3 = 27000 < 32768
    for (let y = lo[1]; y <= hi[1]; y += step) {
      for (let z = lo[2]; z <= hi[2]; z += step) {
        for (let x = lo[0]; x <= hi[0]; x += step) {
          const a: Pos = [x, y, z];
          const b: Pos = [
            Math.min(x + step - 1, hi[0]),
            Math.min(y + step - 1, hi[1]),
            Math.min(z + step - 1, hi[2]),
          ];
          out.push(this.inWorld(world, `fill ${a.join(' ')} ${b.join(' ')} ${block} ${suffix}`.trim()));
        }
      }
    }
    return out;
  }

  private async runAll(commands: string[]): Promise<void> {
    for (const cmd of commands) {
      try {
        await this.client.command(cmd);
      } catch (err) {
        this.log.warn(`RCON command failed: ${cmd} — ${(err as Error).message}`);
      }
    }
  }
}

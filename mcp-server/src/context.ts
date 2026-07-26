import type { Config, Logger } from './config.js';
import type { Bridge } from './transport/index.js';
import type { Blueprint, ContentLibrary, StylePack } from './content/types.js';
import { textOf } from './content/types.js';
import { BridgeError, UnsupportedError, type JobStatus, type Op, type OpsResult } from './protocol.js';
import { estimateRequest } from './build/raster.js';

export interface SubmitOptions {
  world?: string;
  label: string;
  ops: Op[];
  /** Re-applied with physics on so fences/stairs connect. */
  physicsOps?: Op[];
  dryRun?: boolean;
  undo?: boolean;
  /** Block until the job finishes (or the timeout elapses). */
  wait?: boolean;
}

export interface SubmitResult {
  result: OpsResult;
  final?: JobStatus;
  estimated: number;
  world: string;
  report: string;
}

export class Context {
  constructor(
    readonly cfg: Config,
    readonly log: Logger,
    readonly bridge: Bridge,
    readonly library: ContentLibrary,
    readonly transportNote: string,
  ) {}

  get lang(): 'ru' | 'en' {
    return this.cfg.language;
  }

  /** Resolve the world to act on: explicit → configured default → first world. */
  resolveWorld(world?: string): string {
    if (world) return world;
    if (this.cfg.defaultWorld) return this.cfg.defaultWorld;
    const first = this.bridge.health()?.worlds?.[0];
    if (first) return first;
    return 'world';
  }

  style(id: string): StylePack {
    const style = this.library.styles.get(id);
    if (!style) {
      const known = [...this.library.styles.keys()].join(', ');
      throw new BridgeError('NO_STYLE', `Unknown style "${id}". Available: ${known}`);
    }
    return style;
  }

  blueprint(id: string): Blueprint {
    const bp = this.library.blueprints.get(id) ?? this.library.props.get(id);
    if (!bp) {
      throw new BridgeError(
        'NO_BLUEPRINT',
        `Unknown blueprint "${id}". Use list_blueprints to see what is available.`,
      );
    }
    return bp;
  }

  styleLabel(style: StylePack): string {
    return `${style.id} (${textOf(style.name, this.lang)})`;
  }

  /**
   * The single funnel every write goes through: budget check, dry-run handling,
   * job submission, the physics follow-up pass and optional wait-for-completion.
   */
  async submit(opts: SubmitOptions): Promise<SubmitResult> {
    if (this.cfg.readOnly) {
      throw new BridgeError('READ_ONLY', 'MapAiMine is running in read-only mode (MAPAIMINE_READ_ONLY=1).');
    }
    const world = this.resolveWorld(opts.world);
    const estimated = estimateRequest(opts.ops);

    if (estimated > this.cfg.maxBlocksPerCall) {
      throw new BridgeError(
        'BUDGET',
        `This operation would touch about ${estimated.toLocaleString()} blocks, over the ${this.cfg.maxBlocksPerCall.toLocaleString()} limit.`,
        'Split it into smaller regions, or raise MAPAIMINE_MAX_BLOCKS.',
      );
    }
    if (opts.ops.length === 0) {
      throw new BridgeError('EMPTY', 'Nothing to do — the operation list is empty.');
    }

    // A generated town easily exceeds the plugin's per-request op cap, so submit
    // in sequential batches and stitch the results back together.
    const maxOps = Math.max(64, this.bridge.health()?.limits?.maxOpsPerRequest ?? 1024);
    const batches: Op[][] = [];
    for (let i = 0; i < opts.ops.length; i += maxOps) batches.push(opts.ops.slice(i, i + maxOps));

    let result!: OpsResult;
    let final: JobStatus | undefined;
    const undoIds: string[] = [];
    const jobIds: string[] = [];
    let changedTotal = 0;
    const batchWarnings: string[] = [];

    for (const [index, batch] of batches.entries()) {
      const label = batches.length > 1 ? `${opts.label} [${index + 1}/${batches.length}]` : opts.label;
      const partial = await this.bridge.ops({
        world,
        label,
        ops: batch,
        undo: opts.undo ?? true,
        dryRun: opts.dryRun ?? false,
        async: true,
        physics: false,
        lightUpdate: index === batches.length - 1,
      });
      result = partial;
      if (partial.jobId) jobIds.push(partial.jobId);
      if (partial.undoId) undoIds.push(partial.undoId);
      batchWarnings.push(...(partial.warnings ?? []));

      if (!opts.dryRun && partial.jobId && (opts.wait ?? true) && this.bridge.kind === 'http') {
        const jobId = partial.jobId;
        final = await this.waitForJob(jobId).catch((err) => {
          this.log.warn(`could not follow job ${jobId}: ${(err as Error).message}`);
          return undefined;
        });
        if (final?.undoId && !undoIds.includes(final.undoId)) undoIds.push(final.undoId);
        changedTotal += final?.blocksChanged ?? 0;
        if (final?.status === 'failed') {
          batchWarnings.push(`batch ${index + 1} failed: ${final.error ?? 'unknown error'}`);
          break;
        }
      }
    }

    if (!opts.dryRun && opts.physicsOps?.length) {
      try {
        await this.bridge.ops({
          world,
          label: `${opts.label} — connections`,
          ops: opts.physicsOps,
          undo: false,
          async: true,
          physics: true,
          lightUpdate: true,
        });
      } catch (err) {
        this.log.warn(`physics pass failed: ${(err as Error).message}`);
      }
    }

    const status = final?.status ?? result.status;
    const changed = changedTotal || result.blocksChanged || result.estimatedBlocks || estimated;
    const warnings = [...batchWarnings, ...(final?.warnings ?? [])];

    const report =
      `world=${world} ` +
      (jobIds.length > 1
        ? `jobs=${jobIds.length} (${jobIds[0]}…${jobIds[jobIds.length - 1]})`
        : jobIds.length === 1 ? `job=${jobIds[0]}` : 'dry-run') +
      ` status=${status} blocks≈${changed.toLocaleString()}` +
      (undoIds.length ? ` undo=${undoIds.join(',')}` : '') +
      (undoIds.length > 1
        ? `\n(постройка ушла ${undoIds.length} пакетами — для полного отката вызови undo_last ${undoIds.length} раз)`
        : '') +
      (warnings.length ? `\nwarnings:\n- ${[...new Set(warnings)].slice(0, 12).join('\n- ')}` : '');

    return { result, final, estimated, world, report };
  }

  /** Polls a job until it settles. Long builds intentionally take a while. */
  async waitForJob(jobId: string, timeoutMs = 180_000): Promise<JobStatus> {
    const deadline = Date.now() + timeoutMs;
    let delay = 250;
    for (;;) {
      const job = await this.bridge.job(jobId);
      if (job.status === 'done' || job.status === 'failed' || job.status === 'cancelled') return job;
      if (Date.now() > deadline) return job;
      await new Promise((r) => setTimeout(r, delay));
      delay = Math.min(2000, Math.round(delay * 1.4));
    }
  }

  /** Ground height at a point, using survey when the transport can see the world. */
  async groundHeight(world: string, x: number, z: number, fallback?: number): Promise<number> {
    if (!this.bridge.supports('survey')) {
      if (fallback === undefined) {
        throw new UnsupportedError(
          'automatic ground detection',
          'Pass an explicit y — the current transport cannot read the world.',
        );
      }
      return fallback;
    }
    const survey = await this.bridge.survey({ world, x1: x, z1: z, x2: x, z2: z, step: 1, include: ['height'] });
    return survey.heightmap?.[0]?.[0] ?? fallback ?? 64;
  }
}

export function toolText(text: string) {
  return { content: [{ type: 'text' as const, text }] };
}

export function toolError(err: unknown) {
  const e = err as Error & { code?: string; hint?: string };
  const parts = [`❌ ${e.message ?? String(err)}`];
  if (e.hint) parts.push(`подсказка: ${e.hint}`);
  return { content: [{ type: 'text' as const, text: parts.join('\n') }], isError: true };
}

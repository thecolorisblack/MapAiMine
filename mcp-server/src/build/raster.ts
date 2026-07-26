import type { BlockRef, Op, Pos } from '../protocol.js';
import { Rng } from '../util/rng.js';

export interface RasterBlock { pos: Pos; block: string }

export type BlockPicker = (ref: BlockRef) => string;

export function makePicker(seed: number | string = 'raster'): BlockPicker {
  const rng = new Rng(seed);
  return (ref) => {
    if (typeof ref === 'string') return ref;
    if (!ref?.choices?.length) return 'minecraft:stone';
    return rng.weighted(ref.choices).block;
  };
}

const min3 = (a: Pos, b: Pos): Pos => [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])];
const max3 = (a: Pos, b: Pos): Pos => [Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])];

export function regionVolume(from: Pos, to: Pos): number {
  const lo = min3(from, to), hi = max3(from, to);
  return (hi[0] - lo[0] + 1) * (hi[1] - lo[1] + 1) * (hi[2] - lo[2] + 1);
}

/** Decode the compact "3x1,0,2" run-length palette-index stream. */
export function decodeRle(data: string, expected?: number): number[] {
  const out: number[] = [];
  for (const token of data.split(',')) {
    const t = token.trim();
    if (!t) continue;
    const x = t.indexOf('x');
    if (x > 0) {
      const count = Number(t.slice(0, x));
      const value = Number(t.slice(x + 1));
      for (let i = 0; i < count; i++) out.push(value);
    } else {
      out.push(Number(t));
    }
  }
  if (expected !== undefined && out.length !== expected) {
    throw new Error(`RLE stream decodes to ${out.length} entries, expected ${expected}`);
  }
  return out;
}

export function encodeRle(values: number[]): string {
  const parts: string[] = [];
  let i = 0;
  while (i < values.length) {
    let j = i;
    while (j < values.length && values[j] === values[i]) j++;
    const run = j - i;
    parts.push(run > 1 ? `${run}x${values[i]}` : String(values[i]));
    i = j;
  }
  return parts.join(',');
}

/**
 * Expands a geometric operation into explicit blocks on the client side.
 *
 * The bridge plugin does this itself (far faster), so this exists for two reasons:
 * the RCON fallback, which can only speak /setblock and /fill, and dry-run
 * estimation without touching the server.
 *
 * Returns null for operations that need to read the world (paint, scatter,
 * smooth, flatten, raise, terrace, replace) — those cannot be resolved blind.
 */
export function rasterize(op: Op, pick: BlockPicker): RasterBlock[] | null {
  switch (op.type) {
    case 'set':
      return [{ pos: op.pos, block: pick(op.block) }];

    case 'blocks': {
      if ('blocks' in op) return op.blocks.map((b) => ({ pos: b.pos, block: b.block }));
      const [sx, sy, sz] = op.size;
      const idx = decodeRle(op.data, sx * sy * sz);
      const out: RasterBlock[] = [];
      let i = 0;
      for (let y = 0; y < sy; y++) {
        for (let z = 0; z < sz; z++) {
          for (let x = 0; x < sx; x++) {
            const block = op.palette[idx[i++]];
            if (block) out.push({ pos: [op.origin[0] + x, op.origin[1] + y, op.origin[2] + z], block });
          }
        }
      }
      return out;
    }

    case 'fill':
    case 'clear': {
      const block = op.type === 'clear' ? 'minecraft:air' : pick(op.block);
      const lo = min3(op.from, op.to), hi = max3(op.from, op.to);
      const mode = op.type === 'fill' ? op.mode ?? 'replace' : 'replace';
      const out: RasterBlock[] = [];
      for (let y = lo[1]; y <= hi[1]; y++) {
        for (let z = lo[2]; z <= hi[2]; z++) {
          for (let x = lo[0]; x <= hi[0]; x++) {
            const onShell =
              x === lo[0] || x === hi[0] || y === lo[1] || y === hi[1] || z === lo[2] || z === hi[2];
            const onSide = x === lo[0] || x === hi[0] || z === lo[2] || z === hi[2];
            if (mode === 'hollow' && !onShell) continue;
            if (mode === 'outline' && !onSide) continue;
            out.push({ pos: [x, y, z], block });
          }
        }
      }
      return out;
    }

    case 'walls': {
      const lo = min3(op.from, op.to), hi = max3(op.from, op.to);
      const block = pick(op.block);
      const out: RasterBlock[] = [];
      for (let y = lo[1]; y <= hi[1]; y++) {
        for (let z = lo[2]; z <= hi[2]; z++) {
          for (let x = lo[0]; x <= hi[0]; x++) {
            if (x === lo[0] || x === hi[0] || z === lo[2] || z === hi[2]) out.push({ pos: [x, y, z], block });
          }
        }
      }
      return out;
    }

    case 'sphere': {
      const [rx, ry, rz] = typeof op.radius === 'number'
        ? [op.radius, op.radius, op.radius]
        : op.radius;
      const block = pick(op.block);
      const out: RasterBlock[] = [];
      const inside = (dx: number, dy: number, dz: number, shrink: number) =>
        (dx / Math.max(rx - shrink, 0.001)) ** 2 +
        (dy / Math.max(ry - shrink, 0.001)) ** 2 +
        (dz / Math.max(rz - shrink, 0.001)) ** 2 <= 1;
      for (let dy = -Math.ceil(ry); dy <= Math.ceil(ry); dy++) {
        for (let dz = -Math.ceil(rz); dz <= Math.ceil(rz); dz++) {
          for (let dx = -Math.ceil(rx); dx <= Math.ceil(rx); dx++) {
            if (!inside(dx, dy, dz, 0)) continue;
            if (op.hollow && inside(dx, dy, dz, 1)) continue;
            out.push({ pos: [op.center[0] + dx, op.center[1] + dy, op.center[2] + dz], block });
          }
        }
      }
      return out;
    }

    case 'cylinder': {
      const axis = op.axis ?? 'y';
      const r = op.radius;
      const block = pick(op.block);
      const out: RasterBlock[] = [];
      for (let h = 0; h < op.height; h++) {
        for (let a = -Math.ceil(r); a <= Math.ceil(r); a++) {
          for (let b = -Math.ceil(r); b <= Math.ceil(r); b++) {
            const d = Math.sqrt(a * a + b * b);
            if (d > r + 0.5) continue;
            if (op.hollow && d < r - 0.5) continue;
            const pos: Pos =
              axis === 'y' ? [op.base[0] + a, op.base[1] + h, op.base[2] + b]
              : axis === 'x' ? [op.base[0] + h, op.base[1] + a, op.base[2] + b]
              : [op.base[0] + a, op.base[1] + b, op.base[2] + h];
            out.push({ pos, block });
          }
        }
      }
      return out;
    }

    case 'pyramid': {
      const block = pick(op.block);
      const out: RasterBlock[] = [];
      for (let y = 0; y < op.size; y++) {
        const half = op.inverted ? y : op.size - 1 - y;
        for (let dz = -half; dz <= half; dz++) {
          for (let dx = -half; dx <= half; dx++) {
            if (op.hollow && Math.abs(dx) !== half && Math.abs(dz) !== half && y !== 0) continue;
            out.push({ pos: [op.base[0] + dx, op.base[1] + y, op.base[2] + dz], block });
          }
        }
      }
      return out;
    }

    case 'cone': {
      const block = pick(op.block);
      const out: RasterBlock[] = [];
      for (let y = 0; y < op.height; y++) {
        const r = op.radius * (1 - y / op.height);
        for (let dz = -Math.ceil(r); dz <= Math.ceil(r); dz++) {
          for (let dx = -Math.ceil(r); dx <= Math.ceil(r); dx++) {
            const d = Math.sqrt(dx * dx + dz * dz);
            if (d > r + 0.5) continue;
            if (op.hollow && d < r - 0.5) continue;
            out.push({ pos: [op.base[0] + dx, op.base[1] + y, op.base[2] + dz], block });
          }
        }
      }
      return out;
    }

    case 'line': {
      const block = pick(op.block);
      const t = Math.max(1, op.thickness ?? 1);
      const seen = new Set<string>();
      const out: RasterBlock[] = [];
      const steps = Math.max(
        Math.abs(op.to[0] - op.from[0]),
        Math.abs(op.to[1] - op.from[1]),
        Math.abs(op.to[2] - op.from[2]),
      );
      const r = (t - 1) / 2;
      for (let i = 0; i <= steps; i++) {
        const f = steps === 0 ? 0 : i / steps;
        const cx = Math.round(op.from[0] + (op.to[0] - op.from[0]) * f);
        const cy = Math.round(op.from[1] + (op.to[1] - op.from[1]) * f);
        const cz = Math.round(op.from[2] + (op.to[2] - op.from[2]) * f);
        for (let dy = -Math.ceil(r); dy <= Math.ceil(r); dy++) {
          for (let dz = -Math.ceil(r); dz <= Math.ceil(r); dz++) {
            for (let dx = -Math.ceil(r); dx <= Math.ceil(r); dx++) {
              if (r > 0 && Math.sqrt(dx * dx + dy * dy + dz * dz) > r + 0.25) continue;
              const p: Pos = [cx + dx, cy + dy, cz + dz];
              const key = p.join(',');
              if (seen.has(key)) continue;
              seen.add(key);
              out.push({ pos: p, block });
            }
          }
        }
      }
      return out;
    }

    case 'torus': {
      const block = pick(op.block);
      const out: RasterBlock[] = [];
      const R = op.radius, r = op.tube;
      const span = Math.ceil(R + r);
      for (let dy = -Math.ceil(r); dy <= Math.ceil(r); dy++) {
        for (let dz = -span; dz <= span; dz++) {
          for (let dx = -span; dx <= span; dx++) {
            const q = Math.sqrt(dx * dx + dz * dz) - R;
            if (q * q + dy * dy <= r * r) {
              out.push({ pos: [op.center[0] + dx, op.center[1] + dy, op.center[2] + dz], block });
            }
          }
        }
      }
      return out;
    }

    default:
      return null;
  }
}

/** Cheap upper-bound estimate used for dry runs and budget checks. */
export function estimateBlocks(op: Op): number {
  switch (op.type) {
    case 'set': return 1;
    case 'blocks': return 'blocks' in op ? op.blocks.length : op.size[0] * op.size[1] * op.size[2];
    case 'fill':
    case 'clear':
    case 'walls':
    case 'replace':
    case 'smooth':
    case 'flatten':
    case 'raise':
    case 'terrace':
      return regionVolume(op.from, op.to);
    case 'paint':
    case 'scatter': {
      const lo = min3(op.from, op.to), hi = max3(op.from, op.to);
      const area = (hi[0] - lo[0] + 1) * (hi[2] - lo[2] + 1);
      return op.type === 'scatter' ? Math.ceil(area * (op.density ?? 0.05)) * 4 : area * ((op as { depth?: number }).depth ?? 1);
    }
    case 'sphere': {
      const [rx, ry, rz] = typeof op.radius === 'number' ? [op.radius, op.radius, op.radius] : op.radius;
      return Math.ceil((4 / 3) * Math.PI * rx * ry * rz);
    }
    case 'cylinder': return Math.ceil(Math.PI * op.radius * op.radius * op.height);
    case 'cone': return Math.ceil((Math.PI * op.radius * op.radius * op.height) / 3);
    case 'pyramid': return Math.ceil((op.size * op.size * op.size) / 3);
    case 'line': {
      const steps = Math.max(
        Math.abs(op.to[0] - op.from[0]), Math.abs(op.to[1] - op.from[1]), Math.abs(op.to[2] - op.from[2]),
      ) + 1;
      const t = Math.max(1, op.thickness ?? 1);
      return steps * t * t;
    }
    case 'torus': return Math.ceil(2 * Math.PI * Math.PI * op.radius * op.tube * op.tube);
    default: return 1;
  }
}

export function estimateRequest(ops: Op[]): number {
  return ops.reduce((sum, op) => sum + estimateBlocks(op), 0);
}

/**
 * Compress a block list into axis-aligned runs along X, so an RCON transport can
 * emit `/fill` instead of thousands of `/setblock`s.
 */
export function compressToRuns(blocks: RasterBlock[]): Array<{ from: Pos; to: Pos; block: string }> {
  const sorted = [...blocks].sort(
    (a, b) =>
      a.block.localeCompare(b.block) ||
      a.pos[1] - b.pos[1] ||
      a.pos[2] - b.pos[2] ||
      a.pos[0] - b.pos[0],
  );
  const runs: Array<{ from: Pos; to: Pos; block: string }> = [];
  let i = 0;
  while (i < sorted.length) {
    const start = sorted[i];
    let j = i;
    while (
      j + 1 < sorted.length &&
      sorted[j + 1].block === start.block &&
      sorted[j + 1].pos[1] === start.pos[1] &&
      sorted[j + 1].pos[2] === start.pos[2] &&
      sorted[j + 1].pos[0] === sorted[j].pos[0] + 1
    ) j++;
    runs.push({ from: start.pos, to: sorted[j].pos, block: start.block });
    i = j + 1;
  }
  return runs;
}

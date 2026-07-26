/**
 * Deterministic RNG. Every generator in MapAiMine takes a seed so that the same
 * prompt + seed rebuilds the identical village — which is what makes "regenerate
 * but move it 200 blocks north" a usable workflow for an AI agent.
 */
export class Rng {
  private state: number;

  constructor(seed: number | string) {
    this.state = typeof seed === 'number' ? seed >>> 0 : hashString(seed);
    if (this.state === 0) this.state = 0x9e3779b9;
  }

  /** mulberry32 */
  next(): number {
    this.state = (this.state + 0x6d2b79f5) >>> 0;
    let t = this.state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }

  /** Integer in [min, max] inclusive. */
  int(min: number, max: number): number {
    if (max < min) [min, max] = [max, min];
    return min + Math.floor(this.next() * (max - min + 1));
  }

  float(min: number, max: number): number {
    return min + this.next() * (max - min);
  }

  chance(p: number): boolean {
    return this.next() < p;
  }

  pick<T>(items: readonly T[]): T {
    return items[Math.floor(this.next() * items.length)];
  }

  /** Weighted pick; entries without a weight count as 1. */
  weighted<T extends { weight?: number }>(items: readonly T[]): T {
    const total = items.reduce((s, i) => s + (i.weight ?? 1), 0);
    if (total <= 0) return items[0];
    let r = this.next() * total;
    for (const item of items) {
      r -= item.weight ?? 1;
      if (r <= 0) return item;
    }
    return items[items.length - 1];
  }

  shuffle<T>(items: T[]): T[] {
    for (let i = items.length - 1; i > 0; i--) {
      const j = Math.floor(this.next() * (i + 1));
      [items[i], items[j]] = [items[j], items[i]];
    }
    return items;
  }

  /** A fresh independent stream — used so adding a feature doesn't reshuffle others. */
  fork(salt: string): Rng {
    return new Rng(hashString(salt + ':' + this.state));
  }
}

export function hashString(s: string): number {
  let h = 2166136261 >>> 0;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  return h >>> 0;
}

/** Cheap value noise on a seeded lattice — used for organic terrain painting. */
export function valueNoise2D(seed: number, x: number, z: number): number {
  const xi = Math.floor(x), zi = Math.floor(z);
  const xf = x - xi, zf = z - zi;
  const s = (a: number) => a * a * (3 - 2 * a);
  const n = (ix: number, iz: number) => {
    let h = seed ^ Math.imul(ix, 374761393) ^ Math.imul(iz, 668265263);
    h = Math.imul(h ^ (h >>> 13), 1274126177);
    return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
  };
  const a = n(xi, zi), b = n(xi + 1, zi), c = n(xi, zi + 1), d = n(xi + 1, zi + 1);
  const u = s(xf), v = s(zf);
  return a * (1 - u) * (1 - v) + b * u * (1 - v) + c * (1 - u) * v + d * u * v;
}

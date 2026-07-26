import fs from 'node:fs';
import path from 'node:path';
import type { Blueprint, ContentLibrary, StylePack } from './types.js';

function readJsonDir<T>(dir: string, warnings: string[]): Array<{ id: string; value: T; file: string }> {
  if (!fs.existsSync(dir)) return [];
  const out: Array<{ id: string; value: T; file: string }> = [];
  for (const entry of fs.readdirSync(dir)) {
    if (!entry.endsWith('.json')) continue;
    const file = path.join(dir, entry);
    try {
      const value = JSON.parse(fs.readFileSync(file, 'utf8')) as T & { id?: string };
      const id = value.id ?? path.basename(entry, '.json');
      if (value.id && value.id !== path.basename(entry, '.json')) {
        warnings.push(`${file}: id "${value.id}" does not match filename`);
      }
      out.push({ id, value: value as T, file });
    } catch (err) {
      warnings.push(`${file}: ${(err as Error).message}`);
    }
  }
  return out;
}

/**
 * Loads styles, blueprints and props from one or more content roots.
 * Later directories override earlier ones by id, so a user can drop their own
 * pack next to the shipped library without forking it.
 */
export function loadContent(dirs: string[]): ContentLibrary {
  const warnings: string[] = [];
  const styles = new Map<string, StylePack>();
  const blueprints = new Map<string, Blueprint>();
  const props = new Map<string, Blueprint>();
  const used: string[] = [];

  for (const dir of dirs) {
    if (!fs.existsSync(dir)) {
      warnings.push(`content directory not found: ${dir}`);
      continue;
    }
    used.push(dir);
    for (const { id, value } of readJsonDir<StylePack>(path.join(dir, 'styles'), warnings)) {
      styles.set(id, { ...value, id });
    }
    for (const { id, value } of readJsonDir<Blueprint>(path.join(dir, 'blueprints'), warnings)) {
      blueprints.set(id, { ...value, id });
    }
    for (const { id, value } of readJsonDir<Blueprint>(path.join(dir, 'props'), warnings)) {
      props.set(id, { ...value, id, isProp: true });
    }
  }

  return { styles, blueprints, props, warnings, dirs: used };
}

/** Structural checks that catch the mistakes an LLM-authored blueprint actually makes. */
export function validateBlueprint(bp: Blueprint, lib: ContentLibrary): string[] {
  const errors: string[] = [];
  const where = `blueprint "${bp.id}"`;

  if (!Array.isArray(bp.size) || bp.size.length !== 3 || bp.size.some((n) => !Number.isInteger(n) || n <= 0)) {
    errors.push(`${where}: size must be three positive integers`);
    return errors;
  }
  const [sx, sy, sz] = bp.size;

  if (!Array.isArray(bp.layers)) {
    errors.push(`${where}: layers missing`);
    return errors;
  }
  if (bp.layers.length !== sy) {
    errors.push(`${where}: has ${bp.layers.length} layers but size[1] is ${sy}`);
  }

  const seenY = new Set<number>();
  for (const layer of bp.layers) {
    if (seenY.has(layer.y)) errors.push(`${where}: duplicate layer y=${layer.y}`);
    seenY.add(layer.y);
    if (layer.y < 0 || layer.y >= sy) {
      errors.push(`${where}: layer y=${layer.y} outside 0..${sy - 1}`);
    }
    if (!Array.isArray(layer.rows)) {
      errors.push(`${where}: layer y=${layer.y} has no rows`);
      continue;
    }
    if (layer.rows.length !== sz) {
      errors.push(`${where}: layer y=${layer.y} has ${layer.rows.length} rows, expected ${sz}`);
    }
    layer.rows.forEach((row, z) => {
      if (row.length !== sx) {
        errors.push(`${where}: layer y=${layer.y} row ${z} is ${row.length} chars, expected ${sx}`);
      }
      for (const ch of row) {
        if (!(ch in bp.legend)) {
          errors.push(`${where}: layer y=${layer.y} row ${z} uses char "${ch}" missing from legend`);
          return;
        }
      }
    });
  }

  for (const [ch, cell] of Object.entries(bp.legend ?? {})) {
    if (cell.prop && !lib.props.has(cell.prop)) {
      errors.push(`${where}: legend "${ch}" references unknown prop "${cell.prop}"`);
    }
    if (!cell.skip && !cell.block && !cell.role && !cell.prop) {
      errors.push(`${where}: legend "${ch}" has neither block, role, prop nor skip`);
    }
  }

  const inBounds = (p: number[], label: string) => {
    if (p[0] < 0 || p[0] >= sx || p[1] < 0 || p[1] >= sy || p[2] < 0 || p[2] >= sz) {
      errors.push(`${where}: ${label} position ${JSON.stringify(p)} is outside the blueprint volume`);
    }
  };
  bp.signs?.forEach((s) => inBounds(s.pos, 'sign'));
  bp.containers?.forEach((c) => inBounds(c.pos, 'container'));
  bp.entities?.forEach((e) => inBounds(e.pos, 'entity'));
  bp.lights?.forEach((l) => inBounds(l, 'light'));

  return errors;
}

export function validateStyle(style: StylePack, requiredRoles: string[]): string[] {
  const errors: string[] = [];
  const where = `style "${style.id}"`;
  if (!style.materials || typeof style.materials !== 'object') {
    errors.push(`${where}: no materials block`);
    return errors;
  }
  for (const role of requiredRoles) {
    const mat = style.materials[role];
    if (!mat) {
      errors.push(`${where}: missing required role "${role}"`);
    } else if (!Array.isArray(mat.full) || mat.full.length === 0) {
      errors.push(`${where}: role "${role}" has an empty "full" list`);
    } else {
      mat.full.forEach((w, i) => {
        if (typeof w.block !== 'string' || !w.block.trim()) {
          errors.push(`${where}: role "${role}".full[${i}] has no block id`);
        }
      });
    }
  }
  if (style.materials.door && !style.materials.door.door) {
    errors.push(`${where}: role "door" should define a "door" variant (an actual door block)`);
  }
  if (style.materials.fence && !style.materials.fence.fence) {
    errors.push(`${where}: role "fence" should define a "fence" variant`);
  }
  return errors;
}

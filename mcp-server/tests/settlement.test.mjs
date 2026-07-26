import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { loadContent } from '../dist/content/loader.js';
import { generateSettlement } from '../dist/build/settlement.js';
import { estimateRequest } from '../dist/build/raster.js';

const contentDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../content');
const lib = loadContent([contentDir]);

test('the shipped content library loads without errors', () => {
  assert.equal(lib.warnings.length, 0, `content warnings:\n${lib.warnings.join('\n')}`);
  assert.ok(lib.styles.size >= 5, `expected several styles, got ${lib.styles.size}`);
});

test('every style produces a coherent settlement layout', { skip: lib.blueprints.size === 0 && 'no blueprints in the library yet' }, () => {
  for (const style of lib.styles.values()) {
    const result = generateSettlement({
      center: [0, 0],
      size: 96,
      baseY: 64,
      style,
      library: lib,
      kind: 'village',
      seed: 42,
      name: 'Тест',
    });

    assert.ok(result.ops.length > 0, `${style.id}: produced no operations`);
    assert.ok(result.buildings.length >= 3, `${style.id}: only ${result.buildings.length} buildings placed`);

    // Nothing may escape the declared site bounds.
    for (const b of result.buildings) {
      assert.ok(
        b.pos[0] >= result.bounds.x1 - 2 && b.pos[0] <= result.bounds.x2 + 2 &&
        b.pos[2] >= result.bounds.z1 - 2 && b.pos[2] <= result.bounds.z2 + 2,
        `${style.id}: building ${b.blueprint} at ${b.pos} is outside the site`,
      );
    }

    // Buildings must not overlap each other.
    for (let i = 0; i < result.buildings.length; i++) {
      for (let j = i + 1; j < result.buildings.length; j++) {
        const a = result.buildings[i], c = result.buildings[j];
        const separated =
          a.pos[0] + a.footprint[0] <= c.pos[0] || c.pos[0] + c.footprint[0] <= a.pos[0] ||
          a.pos[2] + a.footprint[1] <= c.pos[2] || c.pos[2] + c.footprint[1] <= a.pos[2];
        assert.ok(separated, `${style.id}: ${a.blueprint}@${a.pos} overlaps ${c.blueprint}@${c.pos}`);
      }
    }

    assert.ok(estimateRequest(result.ops) < 20_000_000, `${style.id}: absurd block estimate`);
  }
});

test('the same seed reproduces the same settlement', { skip: lib.blueprints.size === 0 && 'no blueprints in the library yet' }, () => {
  const style = [...lib.styles.values()][0];
  const opts = { center: [100, -50], size: 96, baseY: 70, style, library: lib, seed: 7, kind: 'town' };
  const a = generateSettlement({ ...opts });
  const b = generateSettlement({ ...opts });
  assert.deepEqual(
    a.buildings.map((x) => `${x.blueprint}@${x.pos}:${x.rotation}`),
    b.buildings.map((x) => `${x.blueprint}@${x.pos}:${x.rotation}`),
  );

  const c = generateSettlement({ ...opts, seed: 8 });
  assert.notDeepEqual(
    a.buildings.map((x) => `${x.blueprint}@${x.pos}`),
    c.buildings.map((x) => `${x.blueprint}@${x.pos}`),
    'a different seed should produce a different layout',
  );
});

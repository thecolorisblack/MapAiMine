/**
 * Renders every blueprint in every style offline, collects the distinct block
 * states this produces, and asks a live server whether each one actually exists.
 *
 * Only states that a real render emits are checked — brute-forcing every
 * role x variant pair would flag combinations no blueprint ever asks for
 * (there is no `anvil_slab`, and nothing requests one).
 *
 *   MAPAIMINE_TOKEN=... node scripts/verify-blocks.mjs
 *
 * This is the cheapest way to catch invented block ids across the whole
 * style x blueprint matrix without building 330 structures.
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadContent } from '../dist/content/loader.js';
import { placeBlueprint } from '../dist/build/blueprint.js';
import { normalizeBridgeUrl } from '../dist/config.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const contentDir = process.env.MAPAIMINE_CONTENT_DIR ?? path.resolve(here, '../../content');
const base = normalizeBridgeUrl(process.env.MAPAIMINE_URL ?? process.env.MAPAIMINE_BRIDGE_URL ?? 'http://127.0.0.1:25599');
const token = process.env.MAPAIMINE_TOKEN;
if (!token) {
  console.error('MAPAIMINE_TOKEN is required (see plugins/MapAiMine/config.yml)');
  process.exit(2);
}

const lib = loadContent([contentDir]);
const blocks = new Set();
const paletteWarnings = new Set();
let renders = 0;

for (const style of lib.styles.values()) {
  for (const bp of [...lib.blueprints.values(), ...lib.props.values()]) {
    for (const rotation of [0, 90]) {
      const result = placeBlueprint(bp, {
        origin: [0, 64, 0], rotation, style, props: lib.props, seed: 3, skipPrepare: true,
      });
      renders++;
      for (const op of result.ops) {
        if (op.type === 'blocks' && op.blocks) for (const b of op.blocks) blocks.add(b.block);
        if (op.type === 'sign' || op.type === 'container') blocks.add(op.block);
        if (op.type === 'fill' && typeof op.block === 'string') blocks.add(op.block);
      }
      for (const w of result.warnings) paletteWarnings.add(w);
    }
  }

  // Ground / path / plaza lists and flora blocks are used by the terrain tools.
  for (const list of Object.values(style.ground ?? {})) {
    for (const entry of list ?? []) blocks.add(entry.block);
  }
  for (const key of ['ground', 'rocks']) {
    for (const entry of style.flora?.[key] ?? []) if (entry.block) blocks.add(entry.block);
  }
}

const all = [...blocks].filter(Boolean).sort();
console.log(`${renders} renders → ${all.length} distinct block states to verify against ${base}\n`);

const invalid = [];
for (let i = 0; i < all.length; i += 200) {
  const batch = all.slice(i, i + 200);
  const res = await fetch(`${base}/api/v1/validate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
    body: JSON.stringify({ blocks: batch }),
  });
  const body = await res.json();
  if (!body.ok) {
    console.error(`validate failed: ${JSON.stringify(body.error)}`);
    process.exit(1);
  }
  for (const r of body.data.results) if (!r.valid) invalid.push(r);
}

if (paletteWarnings.size) {
  console.log(`⚠ ${paletteWarnings.size} palette warnings (variant fell back to the full block):`);
  for (const w of [...paletteWarnings].slice(0, 30)) console.log(`  - ${w}`);
  console.log('');
}

if (invalid.length) {
  console.error(`✘ ${invalid.length} block states do not exist on this server:`);
  for (const r of invalid) {
    console.error(`  - ${r.input}${r.suggestions?.length ? `   → ${r.suggestions.slice(0, 3).join(', ')}` : ''}`);
  }
  process.exit(1);
}

console.log(`✔ all ${all.length} block states exist on the server`);

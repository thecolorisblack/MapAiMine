/**
 * End-to-end smoke test against a REAL Minecraft server.
 *
 *   MAPAIMINE_TOKEN=... node scripts/e2e.mjs
 *
 * Not part of `npm test` — it needs a running Paper server with the bridge plugin
 * and it really does modify that world (it builds around 0,0 / 200,200 / 500,500).
 * Run it against a scratch world.
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

if (!process.env.MAPAIMINE_TOKEN) {
  console.error('MAPAIMINE_TOKEN is required (see plugins/MapAiMine/config.yml)');
  process.exit(2);
}

const transport = new StdioClientTransport({
  command: 'node',
  args: [path.join(here, '..', 'dist', 'index.js')],
  env: {
    ...process.env,
    MAPAIMINE_URL: process.env.MAPAIMINE_URL ?? 'http://127.0.0.1:25599',
    MAPAIMINE_DEFAULT_WORLD: process.env.MAPAIMINE_DEFAULT_WORLD ?? 'world',
  },
  stderr: 'pipe',
});

const client = new Client({ name: 'e2e', version: '1' });
await client.connect(transport);

const { tools } = await client.listTools();
console.log(`TOOLS: ${tools.length}\n${tools.map((t) => t.name).join(', ')}\n`);

async function call(name, args = {}) {
  const t0 = Date.now();
  const res = await client.callTool({ name, arguments: args });
  const text = res.content.map((c) => c.text).join('\n');
  const head = text.split('\n').slice(0, 14).join('\n');
  console.log(`\n━━━ ${name} (${Date.now() - t0}ms)${res.isError ? '  ❌ERROR' : ''}\n${head}`);
  if (text.split('\n').length > 14) console.log(`… (+${text.split('\n').length - 14} строк)`);
  return { text, isError: !!res.isError };
}

const failures = [];
const step = async (name, args) => {
  const r = await call(name, args);
  if (r.isError) failures.push(name);
  return r;
};

await step('mapaimine_status');
await step('list_styles', { season: 'winter' });
await step('list_blueprints', { category: 'house' });
await step('survey_area', { x1: -40, z1: -40, x2: 40, z2: 40 });
await step('validate_blocks', { blocks: ['minecraft:oak_planks', 'minecraft:oak_plank', 'cherry_stairs[facing=north]'] });
await step('plan_settlement', { centerX: 0, centerZ: 0, size: 96, style: 'winter', kind: 'village', seed: 42, name: 'Ольхово' });
await step('build_blueprint', { blueprint: 'house_small', style: 'medieval', x: 200, z: 200, y: 4 });
await step('probe_blocks', { points: [[200, 5, 200], [203, 6, 203]] });
await step('draw_shape', { shape: 'sphere', center: [300, 30, 300], radius: 6, block: 'minecraft:glass', hollow: true });
await step('terraform', { action: 'flatten', from: [250, 4, 250], to: [270, 4, 270], y: 6, style: 'autumn' });
await step('paint_surface', { from: [250, 250], to: [270, 270], style: 'autumn', layer: 'top' });
await step('scatter_props', { from: [250, 250], to: [270, 270], style: 'autumn', kind: 'ground' });
await step('generate_settlement', { centerX: 0, centerZ: 0, size: 96, style: 'winter', kind: 'village', seed: 42, name: 'Ольхово' });
await step('set_spawn', { x: 0, z: 0 });
await step('configure_world', { applyStyleEnvironment: 'winter', gameRules: { doMobSpawning: false } });
await step('list_undo');
await step('undo_last');
await step('capture_region', { from: [200, 4, 200], to: [210, 14, 210], name: 'e2e_capture' });
await step('build_structure', { name: 'e2e_capture', x: 400, y: 5, z: 400, rotation: 90 });
await step('build_showcase', { x: 500, z: 500, y: 4, styles: ['medieval', 'winter', 'desert'] });

console.log(`\n\n=== ИТОГ: ${failures.length ? '❌ ошибки в ' + failures.join(', ') : '✅ все шаги прошли'} ===`);
await client.close();
process.exit(failures.length ? 1 : 0);

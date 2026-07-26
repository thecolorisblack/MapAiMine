import test from 'node:test';
import assert from 'node:assert/strict';

import { decodeRle, encodeRle, rasterize, compressToRuns, estimateBlocks, makePicker }
  from '../dist/build/raster.js';
import { inferVariant, rotateFacing, mirrorFacing, rotateAxis, parseBlock, composeBlock, isConnecting }
  from '../dist/build/blockstate.js';
import { placeBlueprint } from '../dist/build/blueprint.js';
import { Palette } from '../dist/build/palette.js';
import { Rng } from '../dist/util/rng.js';
import { toScatterEntries, normalizeWeather } from '../dist/build/flora.js';

test('RLE round-trips', () => {
  const values = [0, 0, 0, 1, 2, 2, 5];
  assert.equal(encodeRle(values), '3x0,1,2x2,5');
  assert.deepEqual(decodeRle(encodeRle(values)), values);
  assert.throws(() => decodeRle('3x0', 5), /expected 5/);
});

test('block state parsing and composing', () => {
  const parsed = parseBlock('oak_stairs[facing=north,half=bottom]');
  assert.equal(parsed.id, 'minecraft:oak_stairs');
  assert.equal(parsed.states.facing, 'north');
  assert.equal(composeBlock('stone', {}), 'minecraft:stone');
  assert.equal(composeBlock('oak_slab', { type: 'top' }), 'minecraft:oak_slab[type=top]');
});

test('variant inference covers the common block families', () => {
  assert.equal(inferVariant('minecraft:oak_planks', 'stairs'), 'minecraft:oak_stairs');
  assert.equal(inferVariant('minecraft:oak_planks', 'fence'), 'minecraft:oak_fence');
  assert.equal(inferVariant('minecraft:oak_planks', 'door'), 'minecraft:oak_door');
  assert.equal(inferVariant('minecraft:stone_bricks', 'stairs'), 'minecraft:stone_brick_stairs');
  assert.equal(inferVariant('minecraft:stone_bricks', 'wall'), 'minecraft:stone_brick_wall');
  assert.equal(inferVariant('minecraft:bricks', 'slab'), 'minecraft:brick_slab');
  assert.equal(inferVariant('minecraft:deepslate_tiles', 'stairs'), 'minecraft:deepslate_tile_stairs');
  assert.equal(inferVariant('minecraft:quartz_block', 'stairs'), 'minecraft:quartz_stairs');
  assert.equal(inferVariant('minecraft:white_wool', 'carpet'), 'minecraft:white_carpet');
  assert.equal(inferVariant('minecraft:glass', 'pane'), 'minecraft:glass_pane');
  assert.equal(inferVariant('minecraft:grass_block', 'carpet'), null);
});

test('rotation helpers', () => {
  assert.equal(rotateFacing('north', 90), 'east');
  assert.equal(rotateFacing('north', 180), 'south');
  assert.equal(rotateFacing('west', 90), 'north');
  assert.equal(rotateFacing('up', 90), 'up');
  assert.equal(mirrorFacing('east', 'x'), 'west');
  assert.equal(mirrorFacing('north', 'z'), 'south');
  assert.equal(rotateAxis('x', 90), 'z');
  assert.equal(rotateAxis('y', 90), 'y');
});

test('connection-sensitive blocks are detected', () => {
  assert.ok(isConnecting('minecraft:oak_fence'));
  assert.ok(isConnecting('minecraft:oak_fence[north=true]'));
  assert.ok(isConnecting('minecraft:cobblestone_wall'));
  assert.ok(isConnecting('minecraft:glass_pane'));
  assert.ok(isConnecting('minecraft:oak_stairs[facing=north]'));
  assert.ok(!isConnecting('minecraft:stone'));
});

test('rasterizer produces the expected volumes', () => {
  const pick = makePicker(1);
  const fill = rasterize({ type: 'fill', from: [0, 0, 0], to: [2, 2, 2], block: 'minecraft:stone' }, pick);
  assert.equal(fill.length, 27);

  const hollow = rasterize(
    { type: 'fill', from: [0, 0, 0], to: [2, 2, 2], block: 'minecraft:stone', mode: 'hollow' }, pick);
  assert.equal(hollow.length, 26);

  const walls = rasterize({ type: 'walls', from: [0, 0, 0], to: [2, 0, 2], block: 'minecraft:stone' }, pick);
  assert.equal(walls.length, 8);

  const sphere = rasterize({ type: 'sphere', center: [0, 0, 0], radius: 3, block: 'minecraft:stone' }, pick);
  assert.ok(sphere.length > 100 && sphere.length < 160, `unexpected sphere size ${sphere.length}`);

  // Ops that need to read the world cannot be rasterised blind.
  assert.equal(rasterize({ type: 'smooth', from: [0, 0, 0], to: [1, 1, 1] }, pick), null);
});

test('run compression merges along X', () => {
  const blocks = [
    { pos: [0, 0, 0], block: 'minecraft:stone' },
    { pos: [1, 0, 0], block: 'minecraft:stone' },
    { pos: [2, 0, 0], block: 'minecraft:stone' },
    { pos: [0, 0, 1], block: 'minecraft:dirt' },
  ];
  const runs = compressToRuns(blocks);
  assert.equal(runs.length, 2);
  const stoneRun = runs.find((r) => r.block === 'minecraft:stone');
  assert.deepEqual(stoneRun.from, [0, 0, 0]);
  assert.deepEqual(stoneRun.to, [2, 0, 0]);
});

test('estimates stay in the right ballpark', () => {
  assert.equal(estimateBlocks({ type: 'fill', from: [0, 0, 0], to: [9, 0, 9], block: 'a' }), 100);
  assert.equal(estimateBlocks({ type: 'set', pos: [0, 0, 0], block: 'a' }), 1);
});

const STYLE = {
  id: 'test',
  name: { ru: 'Тест', en: 'Test' },
  materials: {
    wall_primary: { full: [{ block: 'minecraft:cobblestone' }] },
    wall_secondary: { full: [{ block: 'minecraft:oak_planks' }] },
    roof_primary: { full: [{ block: 'minecraft:dark_oak_planks' }] },
    window: { full: [{ block: 'minecraft:glass' }], pane: 'minecraft:glass_pane' },
    door: { full: [{ block: 'minecraft:oak_planks' }], door: 'minecraft:oak_door' },
    fence: { full: [{ block: 'minecraft:oak_planks' }], fence: 'minecraft:oak_fence' },
    floor: { full: [{ block: 'minecraft:oak_planks' }] },
    beam: { full: [{ block: 'minecraft:dark_oak_log' }] },
    foundation: { full: [{ block: 'minecraft:stone_bricks' }] },
    ground_top: { full: [{ block: 'minecraft:grass_block' }] },
    ground_under: { full: [{ block: 'minecraft:dirt' }] },
    path_primary: { full: [{ block: 'minecraft:dirt_path' }] },
    light: { full: [{ block: 'minecraft:lantern' }] },
  },
};

test('palette resolves roles, variants and fallbacks', () => {
  const p = new Palette(STYLE, new Rng(1));
  assert.equal(p.fullBlock('wall_primary'), 'minecraft:cobblestone');
  assert.equal(p.block('wall_primary', { variant: 'stairs', facing: 'east' }),
    'minecraft:cobblestone_stairs[facing=east,half=bottom,shape=straight]');
  assert.equal(p.block('roof_primary', { variant: 'slab', half: 'top' }),
    'minecraft:dark_oak_slab[type=top]');
  assert.equal(p.block('window', { variant: 'pane' }), 'minecraft:glass_pane');
  // pillar is not defined: falls back through beam
  assert.equal(p.fullBlock('pillar'), 'minecraft:dark_oak_log');
  // unknown role degrades to stone with a warning rather than throwing
  assert.equal(p.fullBlock('nonsense_role'), 'minecraft:stone');
  assert.ok([...p.warnings].some((w) => w.includes('nonsense_role')));
});

const BP = {
  id: 'test_hut',
  name: { ru: 'Хижина' },
  category: 'house',
  size: [3, 2, 4],
  groundLevel: 0,
  legend: {
    '#': { role: 'wall_primary' },
    '.': { block: 'minecraft:air' },
    ' ': { skip: true },
    D: { role: 'door', variant: 'door', facing: 'north' },
    '/': { role: 'roof_primary', variant: 'stairs', facing: 'east' },
  },
  layers: [
    { y: 0, rows: ['###', '#.#', '#.#', '###'] },
    { y: 1, rows: ['#D#', '// ', '// ', '###'] },
  ],
};

test('blueprint placement respects size, air, skip and doors', () => {
  const result = placeBlueprint(BP, {
    origin: [100, 64, 200], style: STYLE, props: new Map(), seed: 7, skipPrepare: true,
  });
  const all = result.ops.filter((o) => o.type === 'blocks').flatMap((o) => o.blocks);

  // 24 cells total, two are skip (' '), so 22 placed + 1 deferred door top.
  assert.equal(all.length, 23);

  const doorBottom = all.find((b) => b.block.startsWith('minecraft:oak_door') && b.block.includes('half=lower'));
  const doorTop = all.find((b) => b.block.includes('half=upper'));
  assert.ok(doorBottom, 'door lower half placed');
  assert.ok(doorTop, 'door upper half auto-added');
  assert.deepEqual(doorTop.pos, [doorBottom.pos[0], doorBottom.pos[1] + 1, doorBottom.pos[2]]);

  // Skipped cell is at local (2,1,1) -> world (102,65,201)
  assert.ok(!all.some((b) => b.pos[0] === 102 && b.pos[1] === 65 && b.pos[2] === 201));

  // Stairs and other connecting blocks get their own physics pass.
  assert.ok(result.physicsOps.length === 1);
  assert.ok(result.physicsOps[0].blocks.every((b) => b.block.includes('_stairs')));
});

test('blueprint rotation moves coordinates and facings together', () => {
  const base = placeBlueprint(BP, {
    origin: [0, 64, 0], style: STYLE, props: new Map(), seed: 7, skipPrepare: true,
  });
  const rotated = placeBlueprint(BP, {
    origin: [0, 64, 0], rotation: 90, style: STYLE, props: new Map(), seed: 7, skipPrepare: true,
  });
  assert.equal(base.blockCount, rotated.blockCount);

  // Footprint swaps X and Z for a 90° turn.
  assert.equal(base.footprint.maxX - base.footprint.minX, 2);
  assert.equal(rotated.footprint.maxX - rotated.footprint.minX, 3);

  const rotatedDoor = rotated.ops
    .filter((o) => o.type === 'blocks')
    .flatMap((o) => o.blocks)
    .find((b) => b.block.includes('oak_door') && b.block.includes('half=lower'));
  assert.ok(rotatedDoor.block.includes('facing=east'), `door should face east, got ${rotatedDoor.block}`);
});

test('flora normalisation fixes tree names and tall plants', () => {
  const [oak, sunflower] = toScatterEntries([{ tree: 'OAK' }, { block: 'minecraft:sunflower' }]);
  assert.equal(oak.tree, 'TREE');
  assert.ok(sunflower.structure, 'tall plants become 2-block structures');
  assert.deepEqual(sunflower.structure.size, [1, 2, 1]);
  assert.equal(normalizeWeather('snow'), 'rain');
  assert.equal(normalizeWeather('clear'), 'clear');
});

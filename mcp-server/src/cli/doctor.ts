#!/usr/bin/env node
/**
 * Connection doctor: verifies that the MCP server can actually reach a Minecraft
 * server, and prints exactly what to fix when it cannot.
 *   npm run doctor
 */
import { loadConfig, makeLogger } from '../config.js';
import { loadContent } from '../content/loader.js';
import { createBridge } from '../transport/index.js';

const cfg = loadConfig();
const log = makeLogger(true);

console.log('MapAiMine doctor');
console.log('─'.repeat(60));
console.log(`transport   : ${cfg.transport}`);
console.log(`bridge url  : ${cfg.bridgeUrl}`);
console.log(`token       : ${cfg.token ? `${cfg.token.slice(0, 6)}… (${cfg.token.length} chars)` : 'НЕ ЗАДАН'}`);
console.log(`rcon        : ${cfg.rcon.host}:${cfg.rcon.port} ${cfg.rcon.password ? '(пароль задан)' : '(пароль не задан)'}`);
console.log(`content dir : ${cfg.contentDir}`);
console.log(`default world: ${cfg.defaultWorld ?? '(автоопределение)'}`);
console.log('');

const lib = loadContent([cfg.contentDir, ...cfg.extraContentDirs]);
console.log(`Библиотека  : ${lib.styles.size} стилей, ${lib.blueprints.size} построек, ${lib.props.size} пропов`);
if (lib.warnings.length) {
  console.log('Проблемы контента:');
  for (const w of lib.warnings.slice(0, 10)) console.log(`  - ${w}`);
}
console.log('');

try {
  const { bridge, note } = await createBridge(cfg, log);
  const health = bridge.health();
  console.log(`✔ Подключено: ${bridge.describe()}`);
  console.log(`  ${note}`);
  if (health) {
    console.log(`  сервер: ${health.server} ${health.minecraftVersion}, протокол v${health.protocol}`);
    console.log(`  возможности: ${health.capabilities.join(', ') || '—'}`);
    console.log(`  лимиты: ${JSON.stringify(health.limits)}`);
  }
  try {
    const worlds = await bridge.worlds();
    console.log(`  миры: ${worlds.map((w) => w.name).join(', ')}`);
  } catch (err) {
    console.log(`  миры: недоступны (${(err as Error).message})`);
  }
  await bridge.close();
  process.exit(0);
} catch (err) {
  const e = err as Error & { hint?: string };
  console.error(`✘ Подключиться не удалось: ${e.message}`);
  if (e.hint) console.error(`  ${e.hint}`);
  console.error('');
  console.error('Что проверить:');
  console.error('  1. Сервер Minecraft запущен, плагин MapAiMine-bridge лежит в plugins/ и загрузился (см. лог).');
  console.error('  2. В plugins/MapAiMine/config.yml есть token — его надо положить в MAPAIMINE_TOKEN.');
  console.error('  3. MAPAIMINE_BRIDGE_URL совпадает с host/port из конфига плагина (по умолчанию http://127.0.0.1:25599).');
  console.error('  4. Если плагина нет — включи RCON в server.properties и задай MAPAIMINE_RCON_PASSWORD.');
  process.exit(1);
}

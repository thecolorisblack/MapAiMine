import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';

const here = path.dirname(fileURLToPath(import.meta.url));

/** Walk up from the compiled file until we find the repo's content/ directory. */
function findContentDir(): string {
  let dir = here;
  for (let i = 0; i < 6; i++) {
    const candidate = path.join(dir, 'content');
    if (fs.existsSync(path.join(candidate, 'styles'))) return candidate;
    dir = path.dirname(dir);
  }
  // Fall back to <package>/../content even if missing, so the error message is useful.
  return path.resolve(here, '../../content');
}

export interface Config {
  /** 'auto' probes the HTTP bridge first, then falls back to RCON if configured. */
  transport: 'auto' | 'http' | 'rcon';
  bridgeUrl: string;
  token?: string;
  rcon: { host: string; port: number; password?: string };
  contentDir: string;
  extraContentDirs: string[];
  defaultWorld?: string;
  /** Refuse any single tool call that would touch more blocks than this. */
  maxBlocksPerCall: number;
  /** Language used for generated sign text and human-readable summaries. */
  language: 'ru' | 'en';
  requestTimeoutMs: number;
  /** When true, `run_command` and other escape hatches are refused. */
  readOnly: boolean;
  debug: boolean;
}

function num(name: string, def: number): number {
  const raw = process.env[name];
  if (!raw) return def;
  const v = Number(raw);
  return Number.isFinite(v) ? v : def;
}

function bool(name: string, def: boolean): boolean {
  const raw = process.env[name];
  if (raw === undefined) return def;
  return /^(1|true|yes|on)$/i.test(raw);
}

export function normalizeBridgeUrl(raw: string): string {
  return raw.trim().replace(/\/+$/, '').replace(/\/api\/v1$/, '');
}

export function loadConfig(): Config {
  const transport = (process.env.MAPAIMINE_TRANSPORT ?? 'auto').toLowerCase();
  return {
    transport: transport === 'http' || transport === 'rcon' ? transport : 'auto',
    // The plugin's startup banner prints MAPAIMINE_URL including the /api/v1 suffix;
    // accept that verbatim so copy-pasting from the server log just works.
    bridgeUrl: normalizeBridgeUrl(
      process.env.MAPAIMINE_BRIDGE_URL ?? process.env.MAPAIMINE_URL ?? 'http://127.0.0.1:25599',
    ),
    token: process.env.MAPAIMINE_TOKEN,
    rcon: {
      host: process.env.MAPAIMINE_RCON_HOST ?? '127.0.0.1',
      port: num('MAPAIMINE_RCON_PORT', 25575),
      password: process.env.MAPAIMINE_RCON_PASSWORD,
    },
    contentDir: process.env.MAPAIMINE_CONTENT_DIR ?? findContentDir(),
    extraContentDirs: (process.env.MAPAIMINE_EXTRA_CONTENT ?? '')
      .split(path.delimiter)
      .map((s) => s.trim())
      .filter(Boolean),
    defaultWorld: process.env.MAPAIMINE_DEFAULT_WORLD,
    maxBlocksPerCall: num('MAPAIMINE_MAX_BLOCKS', 4_000_000),
    language: (process.env.MAPAIMINE_LANG ?? 'ru').toLowerCase() === 'en' ? 'en' : 'ru',
    requestTimeoutMs: num('MAPAIMINE_TIMEOUT_MS', 120_000),
    readOnly: bool('MAPAIMINE_READ_ONLY', false),
    debug: bool('MAPAIMINE_DEBUG', false),
  };
}

/** stderr logger — stdout is reserved for the MCP stdio channel. */
export function makeLogger(debug: boolean) {
  return {
    info: (...a: unknown[]) => console.error('[mapaimine]', ...a),
    warn: (...a: unknown[]) => console.error('[mapaimine][warn]', ...a),
    error: (...a: unknown[]) => console.error('[mapaimine][error]', ...a),
    debug: (...a: unknown[]) => {
      if (debug) console.error('[mapaimine][debug]', ...a);
    },
  };
}

export type Logger = ReturnType<typeof makeLogger>;

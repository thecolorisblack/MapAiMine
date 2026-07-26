import type { Config, Logger } from '../config.js';
import { BridgeError } from '../protocol.js';
import { HttpBridge } from './http.js';
import { RconBridge } from './rcon.js';
import type { Bridge } from './types.js';

export type { Bridge } from './types.js';

/**
 * Picks a transport. In `auto` mode the HTTP bridge wins when it answers, because
 * it is the only one that can read the world back; RCON is the graceful fallback.
 */
export async function createBridge(cfg: Config, log: Logger): Promise<{ bridge: Bridge; note: string }> {
  const tryHttp = async (): Promise<Bridge> => {
    const bridge = new HttpBridge(cfg.bridgeUrl, cfg.token, cfg.requestTimeoutMs, log);
    await bridge.connect();
    return bridge;
  };
  const tryRcon = async (): Promise<Bridge> => {
    if (!cfg.rcon.password) {
      throw new BridgeError('NO_RCON', 'RCON is not configured (MAPAIMINE_RCON_PASSWORD is unset).');
    }
    const bridge = new RconBridge(cfg.rcon.host, cfg.rcon.port, cfg.rcon.password, log);
    await bridge.connect();
    return bridge;
  };

  if (cfg.transport === 'http') {
    return { bridge: await tryHttp(), note: 'transport forced to http' };
  }
  if (cfg.transport === 'rcon') {
    return { bridge: await tryRcon(), note: 'transport forced to rcon' };
  }

  try {
    const bridge = await tryHttp();
    return { bridge, note: 'connected to the MapAiMine bridge plugin' };
  } catch (httpErr) {
    log.warn(`bridge plugin unreachable: ${(httpErr as Error).message}`);
    if (!cfg.rcon.password) throw httpErr;
    try {
      const bridge = await tryRcon();
      return {
        bridge,
        note: 'bridge plugin unreachable — fell back to RCON (world reads, undo and world creation are unavailable)',
      };
    } catch (rconErr) {
      throw new BridgeError(
        'NO_TRANSPORT',
        `Neither the bridge plugin nor RCON could be reached.\n` +
          `  HTTP: ${(httpErr as Error).message}\n  RCON: ${(rconErr as Error).message}`,
        'Check MAPAIMINE_BRIDGE_URL / MAPAIMINE_TOKEN, or MAPAIMINE_RCON_* for the fallback.',
      );
    }
  }
}

/** A bridge stub used when the server starts with no reachable Minecraft server. */
export class OfflineBridge {
  static reason(err: unknown): string {
    const e = err as BridgeError;
    return e?.message ?? String(err);
  }
}

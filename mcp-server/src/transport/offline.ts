import { BridgeError, type Capability, type Health } from '../protocol.js';
import type { Bridge } from './types.js';

/**
 * Stand-in used when no Minecraft server could be reached at startup.
 *
 * The MCP server still starts and still advertises its tools, so the agent can
 * call `mapaimine_status`, read the diagnosis and tell the user how to fix it —
 * which is far more useful than the process dying before the client connects.
 */
export class DisconnectedBridge implements Bridge {
  readonly kind = 'http' as const;

  constructor(private reason: string, private hint?: string) {}

  describe(): string {
    return `не подключено — ${this.reason}`;
  }

  private fail(): never {
    throw new BridgeError(
      'NOT_CONNECTED',
      `Нет связи с Minecraft-сервером: ${this.reason}`,
      this.hint ??
        'Проверь, что сервер запущен, плагин MapAiMine установлен, и что MAPAIMINE_BRIDGE_URL/MAPAIMINE_TOKEN заданы верно. ' +
          'Затем вызови reconnect.',
    );
  }

  async connect(): Promise<Health> { this.fail(); }
  health(): Health | null { return null; }
  supports(_cap: Capability): boolean { return false; }
  async close(): Promise<void> { /* nothing to close */ }

  async worlds(): ReturnType<Bridge['worlds']> { this.fail(); }
  async createWorld(): ReturnType<Bridge['createWorld']> { this.fail(); }
  async worldSettings(): Promise<void> { this.fail(); }
  async setSpawn(): Promise<void> { this.fail(); }
  async setBorder(): Promise<void> { this.fail(); }
  async survey(): ReturnType<Bridge['survey']> { this.fail(); }
  async probe(): ReturnType<Bridge['probe']> { this.fail(); }
  async materials(): Promise<string[]> { this.fail(); }
  async validateBlocks(): ReturnType<Bridge['validateBlocks']> { this.fail(); }
  async ops(): ReturnType<Bridge['ops']> { this.fail(); }
  async job(): ReturnType<Bridge['job']> { this.fail(); }
  async jobs(): ReturnType<Bridge['jobs']> { this.fail(); }
  async cancelJob(): Promise<void> { this.fail(); }
  async undo(): ReturnType<Bridge['undo']> { this.fail(); }
  async undoList(): ReturnType<Bridge['undoList']> { this.fail(); }
  async capture(): ReturnType<Bridge['capture']> { this.fail(); }
  async listCaptures(): Promise<string[]> { this.fail(); }
  async getCapture(): ReturnType<Bridge['getCapture']> { this.fail(); }
  async teleport(): Promise<void> { this.fail(); }
  async gamemode(): Promise<void> { this.fail(); }
  async broadcast(): Promise<void> { this.fail(); }
  async players(): ReturnType<Bridge['players']> { this.fail(); }
}

import net from 'node:net';

const PACKET_AUTH = 3;
const PACKET_COMMAND = 2;
const PACKET_RESPONSE = 0;

interface Pending {
  resolve: (value: string) => void;
  reject: (err: Error) => void;
  chunks: string[];
  sentinelId: number;
}

/**
 * Minimal Source RCON client (the protocol Minecraft servers speak on
 * `enable-rcon=true`). Implemented in-house so the MCP server keeps a zero
 * runtime-dependency footprint beyond the MCP SDK.
 */
export class RconClient {
  private socket: net.Socket | null = null;
  private buffer = Buffer.alloc(0);
  private nextId = 1;
  private pending = new Map<number, Pending>();
  private queue: Promise<unknown> = Promise.resolve();
  private authed = false;

  constructor(
    private host: string,
    private port: number,
    private password: string,
    private timeoutMs = 15_000,
  ) {}

  get connected(): boolean {
    return this.authed && !!this.socket && !this.socket.destroyed;
  }

  async connect(): Promise<void> {
    if (this.connected) return;
    await new Promise<void>((resolve, reject) => {
      const socket = net.createConnection({ host: this.host, port: this.port });
      const onError = (err: Error) => {
        socket.destroy();
        reject(new Error(`RCON connect to ${this.host}:${this.port} failed: ${err.message}`));
      };
      socket.setTimeout(this.timeoutMs);
      socket.once('error', onError);
      socket.once('timeout', () => onError(new Error('timed out')));
      socket.once('connect', () => {
        socket.off('error', onError);
        socket.setTimeout(0);
        socket.on('data', (d: Buffer) => this.onData(d));
        socket.on('error', (err) => this.failAll(err));
        socket.on('close', () => {
          this.authed = false;
          this.failAll(new Error('RCON connection closed'));
        });
        this.socket = socket;
        resolve();
      });
    });
    await this.authenticate();
  }

  private async authenticate(): Promise<void> {
    const id = this.nextId++;
    const response = await new Promise<number>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('RCON auth timed out')), this.timeoutMs);
      this.authPending = { resolve: (rid) => { clearTimeout(timer); resolve(rid); }, reject };
      this.send(id, PACKET_AUTH, this.password);
    });
    if (response === -1) throw new Error('RCON authentication failed — wrong password');
    this.authed = true;
  }

  private authPending: { resolve: (id: number) => void; reject: (e: Error) => void } | null = null;

  /** Commands are serialised: Minecraft's RCON does not like interleaved requests. */
  command(cmd: string): Promise<string> {
    const run = async () => {
      if (!this.connected) await this.connect();
      const id = this.nextId++;
      const sentinelId = this.nextId++;
      return await new Promise<string>((resolve, reject) => {
        const timer = setTimeout(() => {
          this.pending.delete(id);
          reject(new Error(`RCON command timed out: ${cmd.slice(0, 80)}`));
        }, this.timeoutMs);
        this.pending.set(id, {
          resolve: (v) => { clearTimeout(timer); resolve(v); },
          reject: (e) => { clearTimeout(timer); reject(e); },
          chunks: [],
          sentinelId,
        });
        this.send(id, PACKET_COMMAND, cmd);
        // Empty follow-up: its reply marks the end of a possibly fragmented response.
        this.send(sentinelId, PACKET_COMMAND, '');
      });
    };
    const result = this.queue.then(run, run);
    this.queue = result.catch(() => undefined);
    return result;
  }

  private send(id: number, type: number, body: string): void {
    if (!this.socket) throw new Error('RCON socket is not connected');
    const payload = Buffer.from(body, 'utf8');
    const packet = Buffer.alloc(14 + payload.length);
    packet.writeInt32LE(10 + payload.length, 0);
    packet.writeInt32LE(id, 4);
    packet.writeInt32LE(type, 8);
    payload.copy(packet, 12);
    packet.writeInt16LE(0, 12 + payload.length);
    this.socket.write(packet);
  }

  private onData(data: Buffer): void {
    this.buffer = Buffer.concat([this.buffer, data]);
    while (this.buffer.length >= 4) {
      const size = this.buffer.readInt32LE(0);
      if (this.buffer.length < size + 4) break;
      const id = this.buffer.readInt32LE(4);
      const type = this.buffer.readInt32LE(8);
      const body = this.buffer.subarray(12, 4 + size - 2).toString('utf8');
      this.buffer = this.buffer.subarray(4 + size);

      if (this.authPending && (type === PACKET_COMMAND || id === -1)) {
        const p = this.authPending;
        this.authPending = null;
        p.resolve(id);
        continue;
      }
      if (type !== PACKET_RESPONSE) continue;

      const direct = this.pending.get(id);
      if (direct) {
        direct.chunks.push(body);
        continue;
      }
      // A reply carrying a sentinel id closes the request that owns it.
      for (const [reqId, p] of this.pending) {
        if (p.sentinelId === id) {
          this.pending.delete(reqId);
          p.resolve(p.chunks.join(''));
          break;
        }
      }
    }
  }

  private failAll(err: Error): void {
    for (const [, p] of this.pending) p.reject(err);
    this.pending.clear();
    if (this.authPending) {
      this.authPending.reject(err);
      this.authPending = null;
    }
  }

  async close(): Promise<void> {
    this.authed = false;
    this.socket?.destroy();
    this.socket = null;
  }
}

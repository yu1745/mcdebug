import * as fs from 'node:fs';
import * as net from 'node:net';
import * as path from 'node:path';
import { RpcError } from './types.js';

interface RpcRequest {
  jsonrpc: '2.0';
  id: number | string;
  method: string;
  params?: unknown;
}

interface RpcResponseOk {
  jsonrpc: '2.0';
  id: number | string;
  result: unknown;
}

interface RpcResponseErr {
  jsonrpc: '2.0';
  id: number | string | null;
  error: { code: number; message: string; data?: unknown };
}

type RpcResponse = RpcResponseOk | RpcResponseErr;
type RpcMessage = RpcResponse | { jsonrpc: '2.0'; method: string; params?: unknown };

/**
 * Configuration for connecting to a running mcdebug server.
 */
export interface RpcClientOptions {
  /** 127.0.0.1 by default. */
  host?: string;
  /** Explicit port. Overrides port-file discovery. */
  port?: number;
  /** Environment variable holding the port. */
  portEnv?: string;
  /** Path to port file written by the mod. */
  portFile?: string;
  /** Connection timeout in ms. */
  timeoutMs?: number;
}

const DEFAULT_PORT_FILE_CANDIDATES = [
  // Standard: <game_dir>/mcdebug/port (also: <run>/mcdebug/port)
  path.join(process.cwd(), 'mcdebug', 'port'),
  // Search up to a few parents for the run/ dir (covers the common case where
  // the CLI is launched from mcdebug-client/ but the server is in run/).
  path.join(process.cwd(), '..', 'run', 'mcdebug', 'port'),
  path.join(process.cwd(), '..', '..', 'run', 'mcdebug', 'port'),
];

/**
 * Default port mcdebug server binds to (high, unlikely to conflict with other services).
 * Both client and server hardcode this; either side can be overridden via env/CLI/system property.
 */
export const DEFAULT_PORT = 25580;

/**
 * Discover the port the mcdebug server is listening on.
 * Order: explicit option → MCDEBUG_PORT env → port file → DEFAULT_PORT.
 */
export async function discoverPort(opts: RpcClientOptions = {}): Promise<number> {
  if (typeof opts.port === 'number') return opts.port;
  const envName = opts.portEnv ?? 'MCDEBUG_PORT';
  const envVal = process.env[envName];
  if (envVal) {
    const n = Number(envVal);
    if (Number.isFinite(n) && n > 0) return n;
  }
  const candidates = [
    ...(opts.portFile ? [opts.portFile] : []),
    ...DEFAULT_PORT_FILE_CANDIDATES,
  ];
  for (const p of candidates) {
    try {
      const txt = fs.readFileSync(p, 'utf8').trim();
      const n = Number(txt);
      if (Number.isFinite(n) && n > 0) {
        return n;
      }
    } catch {
      // try next
    }
  }
  return DEFAULT_PORT;
}

/**
 * Low-level JSON-RPC 2.0 client over TCP/NDJSON.
 * Auto-connects on first call; supports concurrent requests with an id-correlator.
 */
export class RpcClient {
  private socket: net.Socket | null = null;
  private buf = '';
  private nextId = 1;
  private pending = new Map<number | string, { resolve: (v: unknown) => void; reject: (e: unknown) => void }>();
  private subscribers = new Map<string, Set<(p: unknown) => void>>();
  private connectPromise: Promise<void> | null = null;
  private closed = false;

  constructor(private readonly opts: RpcClientOptions = {}) {}

  private async ensureConnected(): Promise<void> {
    if (this.closed) throw new Error('client closed');
    if (this.socket && !this.socket.destroyed) return;
    if (this.connectPromise) return this.connectPromise;
    this.connectPromise = (async () => {
      const port = await discoverPort(this.opts);
      const host = this.opts.host ?? '127.0.0.1';
      await new Promise<void>((resolve, reject) => {
        const sock = net.createConnection({ host, port });
        const timeout = this.opts.timeoutMs ?? 5000;
        const timer = setTimeout(() => {
          sock.destroy();
          reject(new Error(`connect timeout to ${host}:${port}`));
        }, timeout);
        sock.setEncoding('utf8');
        sock.once('connect', () => {
          clearTimeout(timer);
          this.socket = sock;
          this.setupSocketHandlers(sock);
          resolve();
        });
        sock.once('error', (err) => {
          clearTimeout(timer);
          reject(err);
        });
      });
    })();
    try {
      await this.connectPromise;
    } finally {
      this.connectPromise = null;
    }
  }

  private setupSocketHandlers(sock: net.Socket): void {
    sock.on('data', (chunk: string) => {
      this.buf += chunk;
      let nl: number;
      while ((nl = this.buf.indexOf('\n')) >= 0) {
        const line = this.buf.slice(0, nl).replace(/\r$/, '');
        this.buf = this.buf.slice(nl + 1);
        if (!line) continue;
        this.handleLine(line);
      }
    });
    sock.on('close', () => {
      this.socket = null;
      const err = new Error('connection closed');
      for (const p of this.pending.values()) p.reject(err);
      this.pending.clear();
    });
    sock.on('error', () => {
      // 'close' will follow; handled there.
    });
  }

  private handleLine(line: string): void {
    let msg: RpcMessage;
    try {
      msg = JSON.parse(line);
    } catch {
      return; // ignore malformed line
    }
    if ('method' in msg && msg.method && !('id' in msg)) {
      // server notification
      const subs = this.subscribers.get(msg.method);
      if (subs) for (const h of subs) h(msg.params);
      return;
    }
    const r = msg as RpcResponse;
    const id = r.id;
    const waiter = this.pending.get(id as number | string);
    if (!waiter) return;
    this.pending.delete(id as number | string);
    if ('result' in r) {
      waiter.resolve(r.result);
    } else if ('error' in r) {
      waiter.reject(new RpcError(r.error.code, r.error.message, r.error.data));
    }
  }

  async call<T = unknown>(method: string, params?: unknown): Promise<T> {
    await this.ensureConnected();
    const id = this.nextId++;
    const req: RpcRequest = { jsonrpc: '2.0', id, method, params };
    const line = JSON.stringify(req) + '\n';
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, {
        resolve: (v) => resolve(v as T),
        reject,
      });
      this.socket!.write(line, (err) => {
        if (err) {
          this.pending.delete(id);
          reject(err);
        }
      });
    });
  }

  /** Subscribe to a server-initiated notification (e.g. 'notify.tick'). Returns an unsubscribe fn. */
  on<T = unknown>(method: string, handler: (params: T) => void): () => void {
    let set = this.subscribers.get(method);
    if (!set) {
      set = new Set();
      this.subscribers.set(method, set);
    }
    set.add(handler as (p: unknown) => void);
    return () => {
      set!.delete(handler as (p: unknown) => void);
    };
  }

  async close(): Promise<void> {
    this.closed = true;
    if (this.socket && !this.socket.destroyed) {
      this.socket.end();
      this.socket.destroy();
    }
    this.socket = null;
  }
}

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
  /** Unix domain socket path. Overrides env and discovery-file resolution. */
  socket?: string;
  /** Environment variable holding the socket path. */
  socketEnv?: string;
  /** Path to the socket-discovery file written by the server (default: mcdebug/port). */
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
 * Discover the unix socket path the mcdebug server is listening on.
 * Order: explicit option → MCDEBUG_SOCKET env → discovery file.
 */
export async function discoverSocket(opts: RpcClientOptions = {}): Promise<string> {
  if (opts.socket) return opts.socket;
  const envName = opts.socketEnv ?? 'MCDEBUG_SOCKET';
  const envVal = process.env[envName];
  if (envVal) return envVal;
  const candidates = [
    ...(opts.portFile ? [opts.portFile] : []),
    ...DEFAULT_PORT_FILE_CANDIDATES,
  ];
  for (const p of candidates) {
    try {
      const txt = fs.readFileSync(p, 'utf8').trim();
      if (txt.length > 0) return txt;
    } catch {
      // try next
    }
  }
  throw new Error(
    'cannot discover mcdebug socket: pass --socket, set MCDEBUG_SOCKET, or run from a directory with a mcdebug/port discovery file',
  );
}

/**
 * Low-level JSON-RPC 2.0 client over unix socket/NDJSON.
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
      const socketPath = await discoverSocket(this.opts);
      await new Promise<void>((resolve, reject) => {
        const sock = net.createConnection(socketPath);
        const timeout = this.opts.timeoutMs ?? 5000;
        const timer = setTimeout(() => {
          sock.destroy();
          reject(new Error(`connect timeout to ${socketPath}`));
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

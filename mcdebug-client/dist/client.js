import * as fs from 'node:fs';
import * as net from 'node:net';
import * as path from 'node:path';
import { RpcError } from './types.js';
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
export async function discoverSocket(opts = {}) {
    if (opts.socket)
        return opts.socket;
    const envName = opts.socketEnv ?? 'MCDEBUG_SOCKET';
    const envVal = process.env[envName];
    if (envVal)
        return envVal;
    const candidates = [
        ...(opts.portFile ? [opts.portFile] : []),
        ...DEFAULT_PORT_FILE_CANDIDATES,
    ];
    for (const p of candidates) {
        try {
            const txt = fs.readFileSync(p, 'utf8').trim();
            if (txt.length > 0)
                return txt;
        }
        catch {
            // try next
        }
    }
    throw new Error('cannot discover mcdebug socket: pass --socket, set MCDEBUG_SOCKET, or run from a directory with a mcdebug/port discovery file');
}
/**
 * Low-level JSON-RPC 2.0 client over unix socket/NDJSON.
 * Auto-connects on first call; supports concurrent requests with an id-correlator.
 */
export class RpcClient {
    opts;
    socket = null;
    buf = '';
    nextId = 1;
    pending = new Map();
    subscribers = new Map();
    connectPromise = null;
    closed = false;
    constructor(opts = {}) {
        this.opts = opts;
    }
    async ensureConnected() {
        if (this.closed)
            throw new Error('client closed');
        if (this.socket && !this.socket.destroyed)
            return;
        if (this.connectPromise)
            return this.connectPromise;
        this.connectPromise = (async () => {
            const socketPath = await discoverSocket(this.opts);
            await new Promise((resolve, reject) => {
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
        }
        finally {
            this.connectPromise = null;
        }
    }
    setupSocketHandlers(sock) {
        sock.on('data', (chunk) => {
            this.buf += chunk;
            let nl;
            while ((nl = this.buf.indexOf('\n')) >= 0) {
                const line = this.buf.slice(0, nl).replace(/\r$/, '');
                this.buf = this.buf.slice(nl + 1);
                if (!line)
                    continue;
                this.handleLine(line);
            }
        });
        sock.on('close', () => {
            this.socket = null;
            const err = new Error('connection closed');
            for (const p of this.pending.values())
                p.reject(err);
            this.pending.clear();
        });
        sock.on('error', () => {
            // 'close' will follow; handled there.
        });
    }
    handleLine(line) {
        let msg;
        try {
            msg = JSON.parse(line);
        }
        catch {
            return; // ignore malformed line
        }
        if ('method' in msg && msg.method && !('id' in msg)) {
            // server notification
            const subs = this.subscribers.get(msg.method);
            if (subs)
                for (const h of subs)
                    h(msg.params);
            return;
        }
        const r = msg;
        const id = r.id;
        const waiter = this.pending.get(id);
        if (!waiter)
            return;
        this.pending.delete(id);
        if ('result' in r) {
            waiter.resolve(r.result);
        }
        else if ('error' in r) {
            waiter.reject(new RpcError(r.error.code, r.error.message, r.error.data));
        }
    }
    async call(method, params) {
        await this.ensureConnected();
        const id = this.nextId++;
        const req = { jsonrpc: '2.0', id, method, params };
        const line = JSON.stringify(req) + '\n';
        return new Promise((resolve, reject) => {
            this.pending.set(id, {
                resolve: (v) => resolve(v),
                reject,
            });
            this.socket.write(line, (err) => {
                if (err) {
                    this.pending.delete(id);
                    reject(err);
                }
            });
        });
    }
    /** Subscribe to a server-initiated notification (e.g. 'notify.tick'). Returns an unsubscribe fn. */
    on(method, handler) {
        let set = this.subscribers.get(method);
        if (!set) {
            set = new Set();
            this.subscribers.set(method, set);
        }
        set.add(handler);
        return () => {
            set.delete(handler);
        };
    }
    async close() {
        this.closed = true;
        if (this.socket && !this.socket.destroyed) {
            this.socket.end();
            this.socket.destroy();
        }
        this.socket = null;
    }
}

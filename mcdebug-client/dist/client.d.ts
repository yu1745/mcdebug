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
/**
 * Default port mcdebug server binds to (high, unlikely to conflict with other services).
 * Both client and server hardcode this; either side can be overridden via env/CLI/system property.
 */
export declare const DEFAULT_PORT = 25580;
/**
 * Discover the port the mcdebug server is listening on.
 * Order: explicit option → MCDEBUG_PORT env → port file → DEFAULT_PORT.
 */
export declare function discoverPort(opts?: RpcClientOptions): Promise<number>;
/**
 * Low-level JSON-RPC 2.0 client over TCP/NDJSON.
 * Auto-connects on first call; supports concurrent requests with an id-correlator.
 */
export declare class RpcClient {
    private readonly opts;
    private socket;
    private buf;
    private nextId;
    private pending;
    private subscribers;
    private connectPromise;
    private closed;
    constructor(opts?: RpcClientOptions);
    private ensureConnected;
    private setupSocketHandlers;
    private handleLine;
    call<T = unknown>(method: string, params?: unknown): Promise<T>;
    /** Subscribe to a server-initiated notification (e.g. 'notify.tick'). Returns an unsubscribe fn. */
    on<T = unknown>(method: string, handler: (params: T) => void): () => void;
    close(): Promise<void>;
}

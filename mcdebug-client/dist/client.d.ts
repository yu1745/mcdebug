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
/**
 * Discover the unix socket path the mcdebug server is listening on.
 * Order: explicit option → MCDEBUG_SOCKET env → discovery file.
 */
export declare function discoverSocket(opts?: RpcClientOptions): Promise<string>;
/**
 * Low-level JSON-RPC 2.0 client over unix socket/NDJSON.
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

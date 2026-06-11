export type Pos = readonly [number, number, number];
export interface Box {
    from: Pos;
    to: Pos;
}
export interface BlockStateSpec {
    /** e.g. "minecraft:furnace" */
    name: string;
    /** state property key/value pairs */
    props?: Record<string, string>;
}
export type JsonNbt = string | number | boolean | null | {
    [k: string]: JsonNbt;
} | JsonNbt[];
export interface BlockSnapshot {
    pos: Pos;
    dim: string;
    state: {
        name: string;
        props: Record<string, string>;
    };
    hasBlockEntity: boolean;
    nbt?: JsonNbt;
}
export interface ServerStatus {
    mcVersion: string;
    modVersion: string;
    modLoader: 'fabric';
    protocolVersion: number;
    dims: string[];
    players: number;
    dayTime: number;
    tick: number;
}
export interface WaitResult {
    matched: boolean;
    ranTicks: number;
    value?: JsonNbt;
}
/**
 * Transport-layer RPC error. Mirrors the server's RpcError fields.
 */
export declare class RpcError extends Error {
    readonly code: number;
    readonly data?: unknown | undefined;
    constructor(code: number, message: string, data?: unknown | undefined);
}

export type Pos = readonly [number, number, number];
export interface PosHelpers {
    offset(dx?: number, dy?: number, dz?: number): RichPos;
    east(blocks?: number): RichPos;
    west(blocks?: number): RichPos;
    up(blocks?: number): RichPos;
    down(blocks?: number): RichPos;
    south(blocks?: number): RichPos;
    north(blocks?: number): RichPos;
}
export type RichPos = Pos & PosHelpers;
export declare function pos(x: number, y: number, z: number): RichPos;
export declare function pos(value: Pos): RichPos;
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
export interface ItemStackJson {
    item: string | null;
    count: number;
    nbt?: JsonNbt | null;
}
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

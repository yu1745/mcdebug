import { RpcClient } from './client.js';
import { BlockSnapshot, BlockStateSpec, Box, JsonNbt, Pos, ServerStatus, WaitResult } from './types.js';
/**
 * High-level typed API on top of RpcClient.
 * Matches the methods described in C:\Users\wangyu\.claude\plans\typed-wandering-wall.md
 */
export declare class DebugApi {
    readonly rpc: RpcClient;
    constructor(rpc: RpcClient);
    world: {
        getBlock: (p: Pos, dimOrOpts?: string | {
            dim?: string;
            includeNbt?: boolean;
        }) => Promise<BlockSnapshot>;
        setBlock: (p: Pos, block: string, state?: BlockStateSpec, opts?: {
            flags?: number;
            dim?: string;
        }) => Promise<unknown>;
        setBlocks: (ops: Array<{
            pos: Pos;
            block: string;
            state?: BlockStateSpec;
        }>, opts?: {
            atomic?: boolean;
            dim?: string;
        }) => Promise<unknown>;
        getRegion: (box: Box, opts?: {
            includeNbt?: boolean;
            dim?: string;
        }) => Promise<{
            blocks: Array<BlockSnapshot & {
                pos: Pos;
            }>;
        }>;
        selectBlocks: (box: Box, pred: {
            block?: string;
            tag?: string;
        }, opts?: {
            includeNbt?: boolean;
            dim?: string;
        }) => Promise<{
            matches: Array<{
                pos: Pos;
                nbt?: JsonNbt;
            }>;
        }>;
        forceloadChunk: (cx: number, cz: number, opts?: {
            dim?: string;
        }) => Promise<{
            chunk: [number, number];
            forced: boolean;
            changed: boolean;
            dim: string;
        }>;
        unforceloadChunk: (cx: number, cz: number, opts?: {
            dim?: string;
        }) => Promise<{
            chunk: [number, number];
            forced: boolean;
            changed: boolean;
            dim: string;
        }>;
    };
    be: {
        getNbt: (p: Pos, dim?: string) => Promise<{
            nbt: JsonNbt;
        }>;
        setNbt: (p: Pos, nbt: JsonNbt, dim?: string) => Promise<unknown>;
        getField: (p: Pos, path: string, dim?: string) => Promise<{
            value: JsonNbt;
        }>;
        setField: (p: Pos, path: string, value: JsonNbt, dim?: string) => Promise<unknown>;
    };
    inv: {
        getSize: (p: Pos, dim?: string) => Promise<{
            size: number;
        }>;
        getSlot: (p: Pos, slot: number, dim?: string) => Promise<{
            item: string | null;
            count: number;
            nbt: JsonNbt | null;
            maxCount: number;
        }>;
        setSlot: (p: Pos, slot: number, item: string | null, count: number, nbt?: JsonNbt, dim?: string) => Promise<unknown>;
        insert: (p: Pos, item: string, count: number, opts?: {
            slot?: number;
            nbt?: JsonNbt;
            simulate?: boolean;
            dim?: string;
        }) => Promise<{
            inserted: number;
            remaining: number;
        }>;
        extract: (p: Pos, item: string, count: number, opts?: {
            slot?: number;
            simulate?: boolean;
            dim?: string;
        }) => Promise<{
            extracted: number;
            remaining: number;
        }>;
    };
    /** tick is intentionally not exposed: server drives ticks, client never advances them. */
    tick: never;
    wait: {
        /**
         * Passive wait for a condition to become true. Does NOT advance server ticks.
         * Implementation: registers a ServerTickEvents.END_SERVER_TICK callback on the server
         * that evaluates the predicate on each natural tick.
         */
        until: (predicate: string, opts?: {
            timeoutTicks?: number;
            pollIntervalTicks?: number;
        }) => Promise<WaitResult>;
    };
    scan: {
        findBlocks: (box: Box, block: string, opts?: {
            count?: boolean;
            dim?: string;
        }) => Promise<{
            positions: Pos[];
            count?: number;
        }>;
        countByBlock: (box: Box, dim?: string) => Promise<{
            counts: Record<string, number>;
        }>;
    };
    server: {
        status: () => Promise<ServerStatus>;
        listDimensions: () => Promise<{
            dims: string[];
        }>;
    };
    close(): Promise<void>;
}

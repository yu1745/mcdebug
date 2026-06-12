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
        /**
         * Place a block as if a player were clicking the side of an adjacent block.
         * Goes through the full BlockItem / ItemPlacementContext pipeline with a
         * stable fake ServerPlayerEntity as the placer, so stairs / doors / furnaces
         * / chests, etc. derive their state from `face` + `playerFacing` (NOT from
         * defaultState). Fires sounds, game events, onPlaced, and
         * Criteria.PLACED_BLOCK. May fail if the target pos is non-replaceable.
         */
        placeAsPlayer: (pos: Pos, block: string, face: "up" | "down" | "north" | "south" | "east" | "west", opts?: {
            neighbor?: Pos;
            playerFacing?: "up" | "down" | "north" | "south" | "east" | "west";
            nbt?: JsonNbt;
            dim?: string;
        }) => Promise<{
            ok: boolean;
            pos: Pos;
            neighbor: Pos;
            face: string;
            playerFacing: string;
            placer: string;
            placerUuid: string;
            previous: {
                name: string;
                props: Record<string, string>;
            };
            state: {
                name: string;
                props: Record<string, string>;
            };
        }>;
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
    fluid: {
        info: (p: Pos, opts?: {
            side?: string;
            dim?: string;
        }) => Promise<{
            side: string;
            type: string;
            supportsInsertion: boolean;
            supportsExtraction: boolean;
            parts: Array<{
                fluid: string | null;
                amount: number;
                capacity: number;
            }>;
        }>;
        get: (p: Pos, opts?: {
            side?: string;
            index?: number;
            dim?: string;
        }) => Promise<{
            index: number;
            fluid: string | null;
            amount: number;
            capacity: number;
        }>;
        insert: (p: Pos, fluid: string, amount: number, opts?: {
            side?: string;
            index?: number;
            dim?: string;
        }) => Promise<{
            index: number;
            requested: number;
            inserted: number;
            remaining: number;
        }>;
        extract: (p: Pos, amount: number, opts?: {
            side?: string;
            index?: number;
            dim?: string;
        }) => Promise<{
            index: number;
            fluid: string;
            requested: number;
            extracted: number;
            remaining: number;
        }>;
    };
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
    craft: {
        /**
         * Simulate a single craft of a 3x3 grid. Goes through the server's full
         * RecipeManager — vanilla ShapedRecipe / ShapelessRecipe, AND modded recipe
         * types (e.g. ic2_120:battery_energy_shaped, ic2_120:damage_tool_shapeless).
         *
         * Use the result and `remainder` to verify modded craft behavior:
         *  - ic2_120:battery_energy_shaped:  result.nbt.charge should equal the sum
         *                                    of all input IBatteryItem / IElectricTool
         *                                    charges (capped at output capacity).
         *  - ic2_120:damage_tool_shapeless:  compare remainder[slotOfHammer].damage
         *                                    with the input — should be +1.
         *
         * If `recipeId` is omitted, the first recipe whose matches() returns true is
         * used. If none match, returns { matched: false, candidates: [] }. If multiple
         * match, pass recipeId to disambiguate.
         */
        craft: (grid: Array<{
            item: string;
            count?: number;
            nbt?: JsonNbt;
        } | null>, opts?: {
            recipeId?: string;
            dim?: string;
        }) => Promise<{
            matched: true;
            recipeId: string;
            recipeType: string;
            result: {
                item: string | null;
                count: number;
                nbt: JsonNbt | null;
            };
            remainder: Array<{
                item: string | null;
                count: number;
                nbt: JsonNbt | null;
            }>;
        } | {
            matched: false;
            candidates: string[];
        }>;
        /**
         * Diagnostic: list every crafting recipe whose matches() returns true for the
         * given grid. Use this to find the right `recipeId` to pass to `craft()`.
         */
        find: (grid: Array<{
            item: string;
            count?: number;
            nbt?: JsonNbt;
        } | null>, opts?: {
            dim?: string;
        }) => Promise<{
            matches: Array<{
                recipeId: string;
                recipeType: string;
                output: string;
            }>;
        }>;
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
        runCommand: (command: string, opts?: {
            dim?: string;
        }) => Promise<{
            success: boolean;
            result: number;
            output: string;
        }>;
    };
    close(): Promise<void>;
}

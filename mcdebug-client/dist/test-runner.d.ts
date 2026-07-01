import { DebugApi } from './api.js';
import { RpcClientOptions } from './client.js';
import { Box, ItemStackJson, JsonNbt, Pos, RichPos, ScreenSnapshot, Side, SnapshotCaptureOptions, SnapshotDiffResult, StorageResource, Target, TraceResult } from './types.js';
export type TestFn = (ctx: TestContext) => Promise<void>;
export interface TestCase {
    name: string;
    run: TestFn;
}
export interface SetBlockOp {
    pos: Pos;
    block: string;
    props?: Record<string, string>;
}
export interface TestContext {
    api: DebugApi;
    origin: RichPos;
    pos(dx?: number, dy?: number, dz?: number): RichPos;
}
export interface TestRunnerOptions {
    client?: RpcClientOptions;
    parallelism?: number;
    parallelismEnv?: string;
    origin?: Pos;
    stride?: number;
    gridColumns?: number;
    clearMinOffset?: Pos;
    clearMaxOffset?: Pos;
    batchSize?: number;
}
export interface TestRunner {
    readonly tests: readonly TestCase[];
    test(name: string, run: TestFn): void;
    run(): Promise<void>;
}
export interface LoadTestModulesOptions {
    dir: string | URL;
    suffix?: string;
}
export declare function createTestRunner(options?: TestRunnerOptions): TestRunner;
export declare function defineTest(name: string, run: TestFn): TestCase;
export declare function defineTests(tests: readonly TestCase[]): readonly TestCase[];
export declare function loadTestModules(runner: TestRunner, options: LoadTestModulesOptions): Promise<string[]>;
export declare function offset(origin: Pos, dx?: number, dy?: number, dz?: number): RichPos;
export declare function setBlocks(ctx: TestContext, ops: SetBlockOp[]): Promise<void>;
export declare function place(ctx: TestContext, pos: Pos, block: string): Promise<void>;
export declare function placeAsPlayer(ctx: TestContext, pos: Pos, block: string, face: 'up' | 'down' | 'north' | 'south' | 'east' | 'west', opts?: {
    neighbor?: Pos;
    playerFacing?: 'up' | 'down' | 'north' | 'south' | 'east' | 'west';
}): Promise<void>;
export declare function assertBlockId(ctx: TestContext, pos: Pos, expected: string): Promise<void>;
/**
 * Read the current block id at `pos` (e.g. "minecraft:iron_ore", "minecraft:air").
 * Unlike [assertBlockId], this never throws — use it when you need the value for
 * a conditional branch rather than a hard assertion.
 */
export declare function getBlockId(ctx: TestContext, pos: Pos): Promise<string>;
/**
 * Read a block state property (e.g. "facing", "lit") at `pos`, or null if the
 * block has no such property. The value is the property's string serialization
 * (e.g. "true", "north", "5" for a pickles count).
 */
export declare function getBlockProp(ctx: TestContext, pos: Pos, name: string): Promise<string | null>;
/**
 * Assert the block at `pos` is NOT `unexpected`. Useful for verifying a block was
 * removed/changed — e.g. an ore mined away is no longer "minecraft:iron_ore".
 */
export declare function assertBlockNotId(ctx: TestContext, pos: Pos, unexpected: string): Promise<void>;
export declare function setBeField(ctx: TestContext, pos: Pos, path: string, value: JsonNbt): Promise<void>;
export declare function getBeNumber(ctx: TestContext, pos: Pos, path: string): Promise<number>;
/**
 * Read a BE field at `path` (dot-notation) as its raw JSON value — number,
 * string, boolean, null, or nested object/array. Unlike [getBeNumber], this
 * does not coerce or validate the type, so it works for string/boolean/object
 * fields (e.g. OwnerUUID string, mode flags, nested NBT).
 */
export declare function getBeField(ctx: TestContext, pos: Pos, path: string): Promise<JsonNbt>;
export declare function insertItem(ctx: TestContext, pos: Pos, item: string, count: number, slot: number): Promise<void>;
export declare function setSlot(ctx: TestContext, pos: Pos, slot: number, item: string, count: number, nbt?: JsonNbt): Promise<void>;
export declare function getSlot(ctx: TestContext, pos: Pos, slot: number): Promise<ItemStackJson>;
export declare function assertSlotHas(ctx: TestContext, pos: Pos, slot: number, item: string): Promise<void>;
export declare function assertSlotEmpty(ctx: TestContext, pos: Pos, slot: number): Promise<void>;
export declare function assertSlotCount(ctx: TestContext, pos: Pos, slot: number, expectedCount: number): Promise<void>;
export declare function invItemEquals(pos: Pos, slot: number, itemId: string): string;
export declare function invCountLessThan(pos: Pos, slot: number, count: number): string;
export declare function beFieldGreaterThan(pos: Pos, path: string, value: number): string;
/** block[x,y,z].id == "<id>" — wait until the block becomes `id`. */
export declare function blockId(pos: Pos, id: string): string;
/** block[x,y,z].id != "<id>" — wait until the block is no longer `id`
 *  (e.g. an ore was mined away, a furnace was broken). */
export declare function blockNotId(pos: Pos, id: string): string;
/** block[x,y,z].prop.<name> == <value> — wait until a block state property
 *  matches (e.g. furnace lit=true, door open=half=upper). */
export declare function blockProp(pos: Pos, name: string, value: string | boolean | number): string;
/** be[x,y,z].<path> == <value> */
export declare function beFieldEquals(pos: Pos, path: string, value: number | string | boolean | null): string;
/** be[x,y,z].<path> != <value> */
export declare function beFieldNotEquals(pos: Pos, path: string, value: number | string | boolean | null): string;
/** be[x,y,z].<path> < <value> */
export declare function beFieldLessThan(pos: Pos, path: string, value: number): string;
/** be[x,y,z].<path> <= <value> */
export declare function beFieldLessOrEqual(pos: Pos, path: string, value: number): string;
/** be[x,y,z].<path> >= <value> */
export declare function beFieldGreaterOrEqual(pos: Pos, path: string, value: number): string;
/** inv[x,y,z].<slot>.item == "<id>" — wait until a slot holds this item. */
export declare function invItem(pos: Pos, slot: number, itemId: string): string;
/** inv[x,y,z].<slot>.item != "<id>" — wait until a slot no longer holds this item. */
export declare function invItemNot(pos: Pos, slot: number, itemId: string): string;
/** inv[x,y,z].<slot>.count == <n> */
export declare function invCountEquals(pos: Pos, slot: number, count: number): string;
/** inv[x,y,z].<slot>.count > <n> */
export declare function invCountGreaterThan(pos: Pos, slot: number, count: number): string;
/** inv[x,y,z].<slot>.count >= <n> */
export declare function invCountGreaterOrEqual(pos: Pos, slot: number, count: number): string;
/** inv[x,y,z].<slot>.count <= <n> */
export declare function invCountLessOrEqual(pos: Pos, slot: number, count: number): string;
/** tick == <n> — wait until the server reaches this absolute tick. */
export declare function tickEquals(tick: number): string;
/** tick >= <n> — wait until the server reaches at least this tick. */
export declare function tickGreaterOrEqual(tick: number): string;
export declare function waitUntil(ctx: TestContext, predicate: string, timeoutTicks: number): Promise<void>;
export declare function waitTicks(ctx: TestContext, ticks: number): Promise<void>;
export declare function fluidInsert(ctx: TestContext, pos: Pos, fluid: string, amount: number): Promise<number>;
export declare function fluidGet(ctx: TestContext, pos: Pos, index: number): Promise<{
    index: number;
    fluid: string | null;
    amount: number;
    capacity: number;
}>;
export declare function fluidExtract(ctx: TestContext, pos: Pos, amount: number, index: number): Promise<void>;
export declare function blockTarget(pos: Pos, dim?: string): Target;
export declare function entityTarget(uuid: string, dim?: string): Target;
export declare function itemTarget(stack: ItemStackJson): Target;
export declare function expectStorageAmount(ctx: TestContext, target: Target, handle: string, expected: number, opts?: {
    side?: Side;
    resource?: StorageResource;
}): Promise<void>;
export declare function waitStorageAtLeast(ctx: TestContext, target: Target, handle: string, minimum: number, opts?: {
    side?: Side;
    resource?: StorageResource;
    timeoutTicks?: number;
    pollIntervalTicks?: number;
}): Promise<number>;
export declare function withTrace<T>(ctx: TestContext, options: SnapshotCaptureOptions & {
    intervalTicks?: number;
}, run: () => Promise<T>): Promise<{
    result: T;
    trace: TraceResult;
}>;
export declare function openMachineScreen(ctx: TestContext, pos: Pos, opts?: {
    dim?: string;
    side?: Side;
}): Promise<ScreenSnapshot>;
export declare function snapshotDiff(ctx: TestContext, before: JsonNbt, after: JsonNbt): Promise<SnapshotDiffResult>;
export declare function traceBoxAround(pos: Pos, radius?: number): Box;

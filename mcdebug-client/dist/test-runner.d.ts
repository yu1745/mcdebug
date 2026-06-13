import { DebugApi } from './api.js';
import { RpcClientOptions } from './client.js';
import { ItemStackJson, JsonNbt, Pos } from './types.js';
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
    origin: Pos;
    pos(dx?: number, dy?: number, dz?: number): Pos;
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
export declare function offset(origin: Pos, dx?: number, dy?: number, dz?: number): Pos;
export declare function setBlocks(ctx: TestContext, ops: SetBlockOp[]): Promise<void>;
export declare function place(ctx: TestContext, pos: Pos, block: string): Promise<void>;
export declare function placeAsPlayer(ctx: TestContext, pos: Pos, block: string, face: 'up' | 'down' | 'north' | 'south' | 'east' | 'west', opts?: {
    neighbor?: Pos;
    playerFacing?: 'up' | 'down' | 'north' | 'south' | 'east' | 'west';
}): Promise<void>;
export declare function assertBlockId(ctx: TestContext, pos: Pos, expected: string): Promise<void>;
export declare function setBeField(ctx: TestContext, pos: Pos, path: string, value: JsonNbt): Promise<void>;
export declare function getBeNumber(ctx: TestContext, pos: Pos, path: string): Promise<number>;
export declare function insertItem(ctx: TestContext, pos: Pos, item: string, count: number, slot: number): Promise<void>;
export declare function setSlot(ctx: TestContext, pos: Pos, slot: number, item: string, count: number, nbt?: JsonNbt): Promise<void>;
export declare function getSlot(ctx: TestContext, pos: Pos, slot: number): Promise<ItemStackJson>;
export declare function assertSlotHas(ctx: TestContext, pos: Pos, slot: number, item: string): Promise<void>;
export declare function assertSlotEmpty(ctx: TestContext, pos: Pos, slot: number): Promise<void>;
export declare function assertSlotCount(ctx: TestContext, pos: Pos, slot: number, expectedCount: number): Promise<void>;
export declare function invItemEquals(pos: Pos, slot: number, itemId: string): string;
export declare function invCountLessThan(pos: Pos, slot: number, count: number): string;
export declare function beFieldGreaterThan(pos: Pos, path: string, value: number): string;
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

import { DebugApi } from './api.js';
import { RpcClient, RpcClientOptions } from './client.js';
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

export function createTestRunner(options: TestRunnerOptions = {}): TestRunner {
  const tests: TestCase[] = [];
  return {
    get tests() {
      return tests;
    },
    test(name: string, run: TestFn) {
      tests.push({ name, run });
    },
    run: () => runTests(tests, options),
  };
}

export function offset(origin: Pos, dx = 0, dy = 0, dz = 0): Pos {
  return [origin[0] + dx, origin[1] + dy, origin[2] + dz] as const;
}

function createApi(opts: RpcClientOptions = {}): DebugApi {
  return new DebugApi(new RpcClient(opts));
}

function blockBox(origin: Pos, options: TestRunnerOptions): { min: Pos; max: Pos } {
  const minOffset = options.clearMinOffset ?? ([-8, -1, -8] as const);
  const maxOffset = options.clearMaxOffset ?? ([9, 6, 9] as const);
  return {
    min: offset(origin, minOffset[0], minOffset[1], minOffset[2]),
    max: offset(origin, maxOffset[0], maxOffset[1], maxOffset[2]),
  };
}

function* positionsInBox(min: Pos, max: Pos): Generator<Pos> {
  for (let x = min[0]; x <= max[0]; x++) {
    for (let y = min[1]; y <= max[1]; y++) {
      for (let z = min[2]; z <= max[2]; z++) {
        yield [x, y, z] as const;
      }
    }
  }
}

function chunksForBox(min: Pos, max: Pos): Array<[number, number]> {
  const chunks = new Set<string>();
  for (const [x, , z] of positionsInBox(min, max)) {
    chunks.add(`${Math.floor(x / 16)},${Math.floor(z / 16)}`);
  }
  return [...chunks].map((key) => key.split(',').map(Number) as [number, number]);
}

async function clearArea(api: DebugApi, origin: Pos, options: TestRunnerOptions): Promise<void> {
  const { min, max } = blockBox(origin, options);
  const ops = [...positionsInBox(min, max)].map((pos) => ({ pos, block: 'minecraft:air' }));
  const batchSize = options.batchSize ?? 512;
  for (let i = 0; i < ops.length; i += batchSize) {
    await api.world.setBlocks(ops.slice(i, i + batchSize));
  }
}

async function prepareArea(api: DebugApi, origin: Pos, options: TestRunnerOptions): Promise<Array<[number, number]>> {
  const { min, max } = blockBox(origin, options);
  const chunks = chunksForBox(min, max);
  for (const [cx, cz] of chunks) await api.world.forceloadChunk(cx, cz);
  await clearArea(api, origin, options);
  return chunks;
}

async function cleanupArea(api: DebugApi, origin: Pos, chunks: Array<[number, number]>, options: TestRunnerOptions): Promise<void> {
  await clearArea(api, origin, options);
  for (const [cx, cz] of chunks) await api.world.unforceloadChunk(cx, cz);
}

function originFor(index: number, options: TestRunnerOptions): Pos {
  const base = options.origin ?? ([100, 64, 100] as const);
  const stride = options.stride ?? 32;
  const columns = options.gridColumns ?? 16;
  const col = index % columns;
  const row = Math.floor(index / columns);
  return [base[0] + col * stride, base[1], base[2] + row * stride] as const;
}

async function runOne(testCase: TestCase, index: number, options: TestRunnerOptions): Promise<{ ok: boolean; durationMs: number; error?: unknown }> {
  const api = createApi(options.client);
  const origin = originFor(index, options);
  const ctx: TestContext = {
    api,
    origin,
    pos: (dx = 0, dy = 0, dz = 0) => offset(origin, dx, dy, dz),
  };
  const start = Date.now();
  let chunks: Array<[number, number]> = [];
  try {
    chunks = await prepareArea(api, origin, options);
    await testCase.run(ctx);
    return { ok: true, durationMs: Date.now() - start };
  } catch (error) {
    return { ok: false, durationMs: Date.now() - start, error };
  } finally {
    try {
      await cleanupArea(api, origin, chunks, options);
    } finally {
      await api.close();
    }
  }
}

async function runTests(tests: readonly TestCase[], options: TestRunnerOptions): Promise<void> {
  const envName = options.parallelismEnv ?? 'MCDEBUG_TEST_PARALLELISM';
  const envParallelism = Number(process.env[envName] ?? '');
  const configured = options.parallelism ?? envParallelism;
  const concurrency = Number.isFinite(configured) && configured > 0 ? configured : 128;
  const queue = [...tests.entries()];
  let failed = 0;
  console.log(`[==========] Running ${tests.length} mcdebug TS test(s)`);

  async function worker(): Promise<void> {
    while (queue.length > 0) {
      const next = queue.shift();
      if (!next) return;
      const [index, testCase] = next;
      const result = await runOne(testCase, index, options);
      if (result.ok) {
        console.log(`[       OK ] ${testCase.name} (${result.durationMs}ms)`);
      } else {
        failed++;
        const message = result.error instanceof Error ? result.error.stack ?? result.error.message : String(result.error);
        console.error(`[  FAILED  ] ${testCase.name} (${result.durationMs}ms)`);
        console.error(message);
      }
    }
  }

  await Promise.all(Array.from({ length: Math.min(concurrency, tests.length) }, () => worker()));
  console.log(`[==========] ${tests.length} test(s) reported`);
  if (failed > 0) {
    console.error(`[  FAILED  ] ${failed}/${tests.length} test(s) failed`);
    process.exitCode = 1;
  }
}

export async function setBlocks(ctx: TestContext, ops: SetBlockOp[]): Promise<void> {
  await ctx.api.world.setBlocks(
    ops.map((op) => ({ pos: op.pos, block: op.block, state: op.props ? { name: op.block, props: op.props } : undefined })),
  );
}

export async function place(ctx: TestContext, pos: Pos, block: string): Promise<void> {
  await ctx.api.world.setBlock(pos, block);
}

export async function placeAsPlayer(
  ctx: TestContext,
  pos: Pos,
  block: string,
  face: 'up' | 'down' | 'north' | 'south' | 'east' | 'west',
  opts: { neighbor?: Pos; playerFacing?: 'up' | 'down' | 'north' | 'south' | 'east' | 'west' } = {},
): Promise<void> {
  await ctx.api.world.placeAsPlayer(pos, block, face, opts);
}

export async function assertBlockId(ctx: TestContext, pos: Pos, expected: string): Promise<void> {
  const block = await ctx.api.world.getBlock(pos);
  const actual = block.state.name;
  if (actual !== expected) throw new Error(`expected block ${expected} at ${pos}, got ${actual}`);
}

export async function setBeField(ctx: TestContext, pos: Pos, path: string, value: JsonNbt): Promise<void> {
  await ctx.api.be.setField(pos, path, value);
}

export async function getBeNumber(ctx: TestContext, pos: Pos, path: string): Promise<number> {
  const result = await ctx.api.be.getField(pos, path);
  if (typeof result.value !== 'number') throw new Error(`expected numeric BE field ${path}, got ${JSON.stringify(result.value)}`);
  return result.value;
}

export async function insertItem(ctx: TestContext, pos: Pos, item: string, count: number, slot: number): Promise<void> {
  await ctx.api.inv.insert(pos, item, count, { slot });
}

export async function setSlot(ctx: TestContext, pos: Pos, slot: number, item: string, count: number, nbt?: JsonNbt): Promise<void> {
  await ctx.api.inv.setSlot(pos, slot, item, count, nbt);
}

export async function getSlot(ctx: TestContext, pos: Pos, slot: number): Promise<ItemStackJson> {
  return (await ctx.api.inv.getSlot(pos, slot)).slot;
}

export async function assertSlotHas(ctx: TestContext, pos: Pos, slot: number, item: string): Promise<void> {
  const stack = await getSlot(ctx, pos, slot);
  if (stack.item !== item) throw new Error(`expected slot ${slot} at ${pos} to contain ${item}, got ${stack.item ?? 'empty'}`);
}

export async function assertSlotEmpty(ctx: TestContext, pos: Pos, slot: number): Promise<void> {
  const stack = await getSlot(ctx, pos, slot);
  if (stack.item !== null || stack.count !== 0) throw new Error(`expected slot ${slot} at ${pos} to be empty, got ${JSON.stringify(stack)}`);
}

export async function assertSlotCount(ctx: TestContext, pos: Pos, slot: number, expectedCount: number): Promise<void> {
  const stack = await getSlot(ctx, pos, slot);
  if (stack.count !== expectedCount) throw new Error(`expected slot ${slot} at ${pos} count ${expectedCount}, got ${stack.count}`);
}

export function invItemEquals(pos: Pos, slot: number, itemId: string): string {
  return `inv[${pos[0]},${pos[1]},${pos[2]}].${slot}.item == "${itemId}"`;
}

export function invCountLessThan(pos: Pos, slot: number, count: number): string {
  return `inv[${pos[0]},${pos[1]},${pos[2]}].${slot}.count < ${count}`;
}

export function beFieldGreaterThan(pos: Pos, path: string, value: number): string {
  return `be[${pos[0]},${pos[1]},${pos[2]}].${path} > ${value}`;
}

export async function waitUntil(ctx: TestContext, predicate: string, timeoutTicks: number): Promise<void> {
  await ctx.api.wait.until(predicate, { timeoutTicks });
}

export async function waitTicks(ctx: TestContext, ticks: number): Promise<void> {
  const status = await ctx.api.server.status();
  await waitUntil(ctx, `tick >= ${status.tick + ticks}`, ticks + 20);
}

export async function fluidInsert(ctx: TestContext, pos: Pos, fluid: string, amount: number): Promise<number> {
  const result = await ctx.api.fluid.insert(pos, fluid, amount);
  return result.inserted;
}

export async function fluidGet(ctx: TestContext, pos: Pos, index: number) {
  return ctx.api.fluid.get(pos, { index });
}

export async function fluidExtract(ctx: TestContext, pos: Pos, amount: number, index: number): Promise<void> {
  await ctx.api.fluid.extract(pos, amount, { index });
}

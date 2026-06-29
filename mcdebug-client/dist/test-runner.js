import { readdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { DebugApi } from './api.js';
import { RpcClient } from './client.js';
import { pos as makePos } from './types.js';
export function createTestRunner(options = {}) {
    const tests = [];
    return {
        get tests() {
            return tests;
        },
        test(name, run) {
            tests.push({ name, run });
        },
        run: () => runTests(tests, options),
    };
}
export function defineTest(name, run) {
    return { name, run };
}
export function defineTests(tests) {
    return tests;
}
export async function loadTestModules(runner, options) {
    const suffix = options.suffix ?? '.test.ts';
    const dir = options.dir instanceof URL ? fileURLToPath(options.dir) : options.dir;
    const files = await collectTestFiles(resolve(dir), suffix);
    for (const file of files) {
        const mod = (await import(pathToFileURL(file).href));
        const tests = collectExportedTests(mod);
        if (tests.length === 0) {
            throw new Error(`mcdebug test module ${file} did not export any tests`);
        }
        for (const test of tests)
            runner.test(test.name, test.run);
    }
    return files;
}
function collectExportedTests(mod) {
    const tests = [];
    const visit = (value) => {
        if (Array.isArray(value)) {
            for (const item of value)
                visit(item);
            return;
        }
        if (isTestCase(value))
            tests.push(value);
    };
    for (const value of Object.values(mod))
        visit(value);
    return tests;
}
function isTestCase(value) {
    return (typeof value === 'object' &&
        value !== null &&
        typeof value.name === 'string' &&
        typeof value.run === 'function');
}
async function collectTestFiles(dir, suffix) {
    const entries = await readdir(dir, { withFileTypes: true });
    const files = await Promise.all(entries.map(async (entry) => {
        const path = resolve(dir, entry.name);
        if (entry.isDirectory())
            return collectTestFiles(path, suffix);
        if (entry.isFile() && entry.name.endsWith(suffix))
            return [path];
        return [];
    }));
    return files.flat().sort();
}
export function offset(origin, dx = 0, dy = 0, dz = 0) {
    return makePos(origin[0] + dx, origin[1] + dy, origin[2] + dz);
}
function createApi(opts = {}) {
    return new DebugApi(new RpcClient(opts));
}
function blockBox(origin, options) {
    const minOffset = options.clearMinOffset ?? [-8, -1, -8];
    const maxOffset = options.clearMaxOffset ?? [9, 6, 9];
    return {
        min: offset(origin, minOffset[0], minOffset[1], minOffset[2]),
        max: offset(origin, maxOffset[0], maxOffset[1], maxOffset[2]),
    };
}
function* positionsInBox(min, max) {
    for (let x = min[0]; x <= max[0]; x++) {
        for (let y = min[1]; y <= max[1]; y++) {
            for (let z = min[2]; z <= max[2]; z++) {
                yield [x, y, z];
            }
        }
    }
}
function chunksForBox(min, max) {
    const chunks = new Set();
    for (const [x, , z] of positionsInBox(min, max)) {
        chunks.add(`${Math.floor(x / 16)},${Math.floor(z / 16)}`);
    }
    return [...chunks].map((key) => key.split(',').map(Number));
}
async function clearArea(api, origin, options) {
    const { min, max } = blockBox(origin, options);
    const ops = [...positionsInBox(min, max)].map((pos) => ({ pos, block: 'minecraft:air' }));
    const batchSize = options.batchSize ?? 512;
    for (let i = 0; i < ops.length; i += batchSize) {
        await api.world.setBlocks(ops.slice(i, i + batchSize));
    }
}
async function prepareArea(api, origin, options) {
    const { min, max } = blockBox(origin, options);
    const chunks = chunksForBox(min, max);
    for (const [cx, cz] of chunks)
        await api.world.forceloadChunk(cx, cz);
    await clearArea(api, origin, options);
    return chunks;
}
async function cleanupArea(api, origin, chunks, options) {
    await clearArea(api, origin, options);
    for (const [cx, cz] of chunks)
        await api.world.unforceloadChunk(cx, cz);
}
function originFor(index, options) {
    const base = options.origin ?? [100, 64, 100];
    const stride = options.stride ?? 32;
    const columns = options.gridColumns ?? 16;
    const col = index % columns;
    const row = Math.floor(index / columns);
    return makePos(base[0] + col * stride, base[1], base[2] + row * stride);
}
async function runOne(testCase, index, options) {
    const api = createApi(options.client);
    const origin = originFor(index, options);
    const ctx = {
        api,
        origin,
        pos: (dx = 0, dy = 0, dz = 0) => makePos(offset(origin, dx, dy, dz)),
    };
    const start = Date.now();
    let chunks = [];
    try {
        chunks = await prepareArea(api, origin, options);
        await testCase.run(ctx);
        return { ok: true, durationMs: Date.now() - start };
    }
    catch (error) {
        return { ok: false, durationMs: Date.now() - start, error };
    }
    finally {
        try {
            await cleanupArea(api, origin, chunks, options);
        }
        finally {
            await api.close();
        }
    }
}
async function runTests(tests, options) {
    const envName = options.parallelismEnv ?? 'MCDEBUG_TEST_PARALLELISM';
    const envParallelism = Number(process.env[envName] ?? '');
    const configured = options.parallelism ?? envParallelism;
    const concurrency = Number.isFinite(configured) && configured > 0 ? configured : 128;
    const queue = [...tests.entries()];
    let failed = 0;
    console.log(`[==========] Running ${tests.length} mcdebug TS test(s)`);
    async function worker() {
        while (queue.length > 0) {
            const next = queue.shift();
            if (!next)
                return;
            const [index, testCase] = next;
            const result = await runOne(testCase, index, options);
            if (result.ok) {
                console.log(`[       OK ] ${testCase.name} (${result.durationMs}ms)`);
            }
            else {
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
export async function setBlocks(ctx, ops) {
    await ctx.api.world.setBlocks(ops.map((op) => ({ pos: op.pos, block: op.block, state: op.props ? { name: op.block, props: op.props } : undefined })));
}
export async function place(ctx, pos, block) {
    await ctx.api.world.setBlock(pos, block);
}
export async function placeAsPlayer(ctx, pos, block, face, opts = {}) {
    await ctx.api.world.placeAsPlayer(pos, block, face, opts);
}
export async function assertBlockId(ctx, pos, expected) {
    const block = await ctx.api.world.getBlock(pos);
    const actual = block.state.name;
    if (actual !== expected)
        throw new Error(`expected block ${expected} at ${pos}, got ${actual}`);
}
/**
 * Read the current block id at `pos` (e.g. "minecraft:iron_ore", "minecraft:air").
 * Unlike [assertBlockId], this never throws — use it when you need the value for
 * a conditional branch rather than a hard assertion.
 */
export async function getBlockId(ctx, pos) {
    const block = await ctx.api.world.getBlock(pos);
    return block.state.name;
}
/**
 * Read a block state property (e.g. "facing", "lit") at `pos`, or null if the
 * block has no such property. The value is the property's string serialization
 * (e.g. "true", "north", "5" for a pickles count).
 */
export async function getBlockProp(ctx, pos, name) {
    const block = await ctx.api.world.getBlock(pos);
    return block.state.props[name] ?? null;
}
/**
 * Assert the block at `pos` is NOT `unexpected`. Useful for verifying a block was
 * removed/changed — e.g. an ore mined away is no longer "minecraft:iron_ore".
 */
export async function assertBlockNotId(ctx, pos, unexpected) {
    const actual = await getBlockId(ctx, pos);
    if (actual === unexpected) {
        throw new Error(`expected block at ${pos} to NOT be ${unexpected}, but it was`);
    }
}
export async function setBeField(ctx, pos, path, value) {
    await ctx.api.be.setField(pos, path, value);
}
export async function getBeNumber(ctx, pos, path) {
    const result = await ctx.api.be.getField(pos, path);
    if (typeof result.value !== 'number')
        throw new Error(`expected numeric BE field ${path}, got ${JSON.stringify(result.value)}`);
    return result.value;
}
/**
 * Read a BE field at `path` (dot-notation) as its raw JSON value — number,
 * string, boolean, null, or nested object/array. Unlike [getBeNumber], this
 * does not coerce or validate the type, so it works for string/boolean/object
 * fields (e.g. OwnerUUID string, mode flags, nested NBT).
 */
export async function getBeField(ctx, pos, path) {
    const result = await ctx.api.be.getField(pos, path);
    return result.value;
}
export async function insertItem(ctx, pos, item, count, slot) {
    await ctx.api.inv.insert(pos, item, count, { slot });
}
export async function setSlot(ctx, pos, slot, item, count, nbt) {
    await ctx.api.inv.setSlot(pos, slot, item, count, nbt);
}
export async function getSlot(ctx, pos, slot) {
    return (await ctx.api.inv.getSlot(pos, slot)).slot;
}
export async function assertSlotHas(ctx, pos, slot, item) {
    const stack = await getSlot(ctx, pos, slot);
    if (stack.item !== item)
        throw new Error(`expected slot ${slot} at ${pos} to contain ${item}, got ${stack.item ?? 'empty'}`);
}
export async function assertSlotEmpty(ctx, pos, slot) {
    const stack = await getSlot(ctx, pos, slot);
    if (stack.item !== null || stack.count !== 0)
        throw new Error(`expected slot ${slot} at ${pos} to be empty, got ${JSON.stringify(stack)}`);
}
export async function assertSlotCount(ctx, pos, slot, expectedCount) {
    const stack = await getSlot(ctx, pos, slot);
    if (stack.count !== expectedCount)
        throw new Error(`expected slot ${slot} at ${pos} count ${expectedCount}, got ${stack.count}`);
}
export function invItemEquals(pos, slot, itemId) {
    return `inv[${pos[0]},${pos[1]},${pos[2]}].${slot}.item == "${itemId}"`;
}
export function invCountLessThan(pos, slot, count) {
    return `inv[${pos[0]},${pos[1]},${pos[2]}].${slot}.count < ${count}`;
}
export function beFieldGreaterThan(pos, path, value) {
    return `be[${pos[0]},${pos[1]},${pos[2]}].${path} > ${value}`;
}
// ---- predicate builders (string form, passed to waitUntil) ----
// The server wait.until grammar (WaitOps.kt) accepts:
//   tick <op> <value>
//   block[x,y,z].id            <op> <value>      (id is the default path)
//   block[x,y,z].prop.<name>   <op> <value>
//   be[x,y,z].<jsonPointer>    <op> <value>
//   inv[x,y,z].size            <op> <value>
//   inv[x,y,z].<slot>.item|count|maxCount|nbt.<path>  <op> <value>
// ops: == != < <= > >=   values: number | "string" | true | false | null
/** Format a literal for the wait.until predicate grammar. Strings get quoted;
 *  numbers/booleans/null pass through. */
function fmtLit(value) {
    if (typeof value === 'string')
        return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
    return String(value);
}
// --- block predicates ---
/** block[x,y,z].id == "<id>" — wait until the block becomes `id`. */
export function blockId(pos, id) {
    return `block[${pos[0]},${pos[1]},${pos[2]}].id == ${fmtLit(id)}`;
}
/** block[x,y,z].id != "<id>" — wait until the block is no longer `id`
 *  (e.g. an ore was mined away, a furnace was broken). */
export function blockNotId(pos, id) {
    return `block[${pos[0]},${pos[1]},${pos[2]}].id != ${fmtLit(id)}`;
}
/** block[x,y,z].prop.<name> == <value> — wait until a block state property
 *  matches (e.g. furnace lit=true, door open=half=upper). */
export function blockProp(pos, name, value) {
    return `block[${pos[0]},${pos[1]},${pos[2]}].prop.${name} == ${fmtLit(value)}`;
}
// --- block-entity predicates (full op coverage; previously only > was exposed) ---
/** be[x,y,z].<path> == <value> */
export function beFieldEquals(pos, path, value) {
    return `be[${pos[0]},${pos[1]},${pos[2]}].${path} == ${fmtLit(value)}`;
}
/** be[x,y,z].<path> != <value> */
export function beFieldNotEquals(pos, path, value) {
    return `be[${pos[0]},${pos[1]},${pos[2]}].${path} != ${fmtLit(value)}`;
}
/** be[x,y,z].<path> < <value> */
export function beFieldLessThan(pos, path, value) {
    return `be[${pos[0]},${pos[1]},${pos[2]}].${path} < ${value}`;
}
/** be[x,y,z].<path> <= <value> */
export function beFieldLessOrEqual(pos, path, value) {
    return `be[${pos[0]},${pos[1]},${pos[2]}].${path} <= ${value}`;
}
/** be[x,y,z].<path> >= <value> */
export function beFieldGreaterOrEqual(pos, path, value) {
    return `be[${pos[0]},${pos[1]},${pos[2]}].${path} >= ${value}`;
}
// --- inventory predicates (full op coverage for item/count) ---
/** inv[x,y,z].<slot>.item == "<id>" — wait until a slot holds this item. */
export function invItem(pos, slot, itemId) {
    return `inv[${pos[0]},${pos[1]},${pos[2]}].${slot}.item == ${fmtLit(itemId)}`;
}
/** inv[x,y,z].<slot>.item != "<id>" — wait until a slot no longer holds this item. */
export function invItemNot(pos, slot, itemId) {
    return `inv[${pos[0]},${pos[1]},${pos[2]}].${slot}.item != ${fmtLit(itemId)}`;
}
/** inv[x,y,z].<slot>.count == <n> */
export function invCountEquals(pos, slot, count) {
    return `inv[${pos[0]},${pos[1]},${pos[2]}].${slot}.count == ${count}`;
}
/** inv[x,y,z].<slot>.count > <n> */
export function invCountGreaterThan(pos, slot, count) {
    return `inv[${pos[0]},${pos[1]},${pos[2]}].${slot}.count > ${count}`;
}
/** inv[x,y,z].<slot>.count >= <n> */
export function invCountGreaterOrEqual(pos, slot, count) {
    return `inv[${pos[0]},${pos[1]},${pos[2]}].${slot}.count >= ${count}`;
}
/** inv[x,y,z].<slot>.count <= <n> */
export function invCountLessOrEqual(pos, slot, count) {
    return `inv[${pos[0]},${pos[1]},${pos[2]}].${slot}.count <= ${count}`;
}
// --- tick predicate ---
/** tick == <n> — wait until the server reaches this absolute tick. */
export function tickEquals(tick) {
    return `tick == ${tick}`;
}
/** tick >= <n> — wait until the server reaches at least this tick. */
export function tickGreaterOrEqual(tick) {
    return `tick >= ${tick}`;
}
export async function waitUntil(ctx, predicate, timeoutTicks) {
    await ctx.api.wait.until(predicate, { timeoutTicks });
}
export async function waitTicks(ctx, ticks) {
    const status = await ctx.api.server.status();
    await waitUntil(ctx, `tick >= ${status.tick + ticks}`, ticks + 20);
}
export async function fluidInsert(ctx, pos, fluid, amount) {
    const result = await ctx.api.fluid.insert(pos, fluid, amount);
    return result.inserted;
}
export async function fluidGet(ctx, pos, index) {
    return ctx.api.fluid.get(pos, { index });
}
export async function fluidExtract(ctx, pos, amount, index) {
    await ctx.api.fluid.extract(pos, amount, { index });
}

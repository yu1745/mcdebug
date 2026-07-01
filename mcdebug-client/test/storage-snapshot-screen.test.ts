import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createApi, pos, cleanupBlock, forceloadAt } from './helpers.js';

const P = (dx: number, dy: number, dz: number) => pos(dx + 120, dy, dz);

describe('storage, snapshot, trace, and screen ops', () => {
  let api: ReturnType<typeof createApi>;
  const chest = P(0, 0, 0);
  const furnace = P(2, 0, 0);
  const tracePos = P(4, 0, 0);
  const cartPos = P(6, 0, 0);

  before(async () => {
    api = createApi();
    await forceloadAt(api, chest);
    await forceloadAt(api, furnace);
    await forceloadAt(api, tracePos);
    await forceloadAt(api, cartPos);
    await cleanupBlock(api, chest);
    await cleanupBlock(api, furnace);
    await cleanupBlock(api, tracePos);
    await api.server.runCommand(`kill @e[type=minecraft:chest_minecart,x=${cartPos[0]},y=${cartPos[1]},z=${cartPos[2]},distance=..3]`);
  });

  after(async () => {
    await cleanupBlock(api, chest);
    await cleanupBlock(api, furnace);
    await cleanupBlock(api, tracePos);
    await api.server.runCommand(`kill @e[type=minecraft:chest_minecart,x=${cartPos[0]},y=${cartPos[1]},z=${cartPos[2]},distance=..3]`);
    await api.close();
  });

  it('lists, inserts, reads, and extracts vanilla inventory storage', async () => {
    await api.world.setBlock(chest, 'minecraft:chest');
    const target = { kind: 'block', pos: chest } as const;

    const listed = await api.storage.list(target);
    assert.ok(listed.handles.some((h) => h.handle === 'vanilla:inventory' && h.kind === 'item'));

    const inserted = await api.storage.insert(
      target,
      'vanilla:inventory',
      { kind: 'item', item: 'minecraft:cobblestone' },
      16,
    );
    assert.equal(inserted.inserted, 16);
    assert.equal(inserted.remaining, 0);

    const storage = await api.storage.get(target, 'vanilla:inventory');
    assert.equal(storage.kind, 'item');
    assert.equal(storage.slots[0].stack.item, 'minecraft:cobblestone');
    assert.equal(storage.slots[0].stack.count, 16);

    const extracted = await api.storage.extract(
      target,
      'vanilla:inventory',
      { kind: 'item', item: 'minecraft:cobblestone' },
      5,
    );
    assert.equal(extracted.extracted, 5);
    const afterExtract = await api.storage.get(target, 'vanilla:inventory');
    assert.equal(afterExtract.kind, 'item');
    assert.equal(afterExtract.slots[0].stack.count, 11);
  });

  it('exposes sided furnace item storage', async () => {
    await api.world.setBlock(furnace, 'minecraft:furnace');
    const target = { kind: 'block', pos: furnace } as const;

    const top = await api.storage.list(target, { side: 'up' });
    const vanilla = top.handles.find((h) => h.handle === 'vanilla:inventory');
    assert.ok(vanilla);
    assert.equal(vanilla.kind, 'item');
    assert.ok(vanilla.slots >= 1);
  });

  it('supports by-value item fluid targets', async () => {
    const target = { kind: 'item', stack: { item: 'minecraft:water_bucket', count: 1 } } as const;

    const listed = await api.storage.list(target);
    assert.ok(listed.handles.some((h) => h.handle === 'fabric:fluid' && h.kind === 'fluid'));

    const extracted = await api.storage.extract(
      target,
      'fabric:fluid',
      { kind: 'fluid', fluid: 'minecraft:water' },
      81000,
    );
    assert.equal(extracted.extracted, 81000);
    assert.equal(extracted.targetAfter?.kind, 'item');
    if (extracted.targetAfter?.kind !== 'item') assert.fail('expected item targetAfter');
    assert.equal(extracted.targetAfter.stack.item, 'minecraft:bucket');
  });

  it('supports entity inventory targets', async () => {
    const summoned = await api.server.runCommand(`summon minecraft:chest_minecart ${cartPos[0] + 0.5} ${cartPos[1]} ${cartPos[2] + 0.5}`);
    assert.equal(summoned.success, true, summoned.output);
    const found = await api.scan.findEntities(
      {
        from: [cartPos[0] - 1, cartPos[1] - 1, cartPos[2] - 1],
        to: [cartPos[0] + 1, cartPos[1] + 1, cartPos[2] + 1],
      },
      { type: 'minecraft:chest_minecart' },
    );
    assert.equal(found.count, 1);

    const target = { kind: 'entity', uuid: found.entities[0].uuid } as const;
    const listed = await api.storage.list(target);
    assert.ok(listed.handles.some((h) => h.handle === 'vanilla:inventory' && h.kind === 'item'));

    const inserted = await api.storage.insert(
      target,
      'vanilla:inventory',
      { kind: 'item', item: 'minecraft:coal' },
      7,
    );
    assert.equal(inserted.inserted, 7);

    const storage = await api.storage.get(target, 'vanilla:inventory');
    assert.equal(storage.kind, 'item');
    assert.equal(storage.slots[0].stack.item, 'minecraft:coal');
    assert.equal(storage.slots[0].stack.count, 7);
  });

  it('captures snapshots and diffs JSON structure', async () => {
    await cleanupBlock(api, chest);
    const box = { from: chest, to: chest };
    const before = await api.snapshot.capture({ box, include: ['block', 'inventory'] });

    await api.world.setBlock(chest, 'minecraft:chest');
    await api.storage.insert(
      { kind: 'block', pos: chest },
      'vanilla:inventory',
      { kind: 'item', item: 'minecraft:stone' },
      3,
    );
    const after = await api.snapshot.capture({ box, include: ['block', 'inventory'] });
    const diff = await api.snapshot.diff(before, after);

    assert.equal(diff.equal, false);
    assert.ok(diff.changeCount > 0);
  });

  it('records trace frames on natural ticks', async () => {
    await cleanupBlock(api, tracePos);
    const trace = await api.trace.start({
      box: { from: tracePos, to: tracePos },
      include: ['block'],
      intervalTicks: 1,
    });
    await api.world.setBlock(tracePos, 'minecraft:stone');
    const status = await api.server.status();
    await api.wait.until(`tick >= ${status.tick + 2}`, { timeoutTicks: 20 });

    const stopped = await api.trace.stop(trace.traceId);
    assert.equal(stopped.active, false);
    assert.ok(stopped.frames.length >= 2);
  });

  it('opens and closes a real server screen handler', async () => {
    await api.world.setBlock(furnace, 'minecraft:furnace');

    const opened = await api.screen.openBlock(furnace);
    assert.equal(opened.handlerType, 'minecraft:furnace');
    assert.ok(opened.slots.length >= 39);
    assert.equal(opened.properties.length, 4);

    const moved = await api.screen.quickMove(opened.screenId, 0);
    assert.equal(moved.screenId, opened.screenId);

    const closed = await api.screen.close(opened.screenId);
    assert.equal(closed.closed, true);
  });
});

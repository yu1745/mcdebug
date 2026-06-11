import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createApi, pos, cleanupBlock, forceloadAt } from './helpers.js';

// Use offset 20 to avoid world.test + block-entity.test positions
const P = (name: string, dx: number, dy: number, dz: number) => pos(dx + 20, dy, dz);

describe('inventory ops', () => {
  let api: ReturnType<typeof createApi>;
  const p = P('inv', 0, 0, 0);

  before(async () => {
    api = createApi();
    await forceloadAt(api, p);
    await api.world.setBlock(p, 'minecraft:furnace');
  });

  after(async () => {
    await cleanupBlock(api, p);
    await api.close();
  });

  it('getSlot on empty slot', async () => {
    const r = await api.inv.getSlot(p, 0);
    assert.equal(r.slot.item, null);
    assert.equal(r.slot.count, 0);
  });

  it('setSlot + getSlot roundtrip', async () => {
    await api.inv.setSlot(p, 0, 'minecraft:iron_ore', 16);
    const r = await api.inv.getSlot(p, 0);
    assert.equal(r.slot.item, 'minecraft:iron_ore');
    assert.equal(r.slot.count, 16);
  });

  it('insert stacks onto matching slot', async () => {
    await api.inv.setSlot(p, 0, 'minecraft:iron_ore', 16);
    const r = await api.inv.insert(p, 'minecraft:iron_ore', 8);
    assert.equal(r.inserted, 8);
    assert.equal(r.remaining, 0);
    const after = await api.inv.getSlot(p, 0);
    assert.equal(after.slot.count, 24);
  });

  it('extract reduces count', async () => {
    await api.inv.setSlot(p, 0, 'minecraft:iron_ore', 16);
    const r = await api.inv.extract(p, 'minecraft:iron_ore', 5);
    assert.equal(r.extracted, 5);
    assert.equal(r.remaining, 0);
    const after = await api.inv.getSlot(p, 0);
    assert.equal(after.slot.count, 11);
  });

  it('extract --simulate does not modify', async () => {
    await api.inv.setSlot(p, 0, 'minecraft:iron_ore', 16);
    const before = await api.inv.getSlot(p, 0);
    const r = await api.inv.extract(p, 'minecraft:iron_ore', 3, { simulate: true });
    assert.equal(r.extracted, 3);
    const after = await api.inv.getSlot(p, 0);
    assert.equal(after.slot.count, before.slot.count);
  });

  it('setSlot air clears slot', async () => {
    await api.inv.setSlot(p, 0, 'minecraft:air', 0);
    const r = await api.inv.getSlot(p, 0);
    assert.equal(r.slot.item, null);
    assert.equal(r.slot.count, 0);
  });
});

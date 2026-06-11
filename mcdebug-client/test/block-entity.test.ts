import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createApi, pos, cleanupBlock, forceloadAt } from './helpers.js';

describe('block-entity ops', () => {
  let api: ReturnType<typeof createApi>;
  const p = pos(0, 0, 0); // use a stable position for all tests in this group

  before(async () => {
    api = createApi();
    await forceloadAt(api, p);
    await api.world.setBlock(p, 'minecraft:furnace');
  });

  after(async () => {
    await cleanupBlock(api, p);
    await api.close();
  });

  it('getNbt returns furnace fields', async () => {
    const r = await api.be.getNbt(p);
    assert.ok(r.nbt != null);
    const nbt = r.nbt as Record<string, unknown>;
    assert.ok('CookTime' in nbt);
    assert.ok('BurnTime' in nbt);
    assert.ok('Items' in nbt);
    assert.ok('CookTimeTotal' in nbt);
  });

  it('getField reads CookTime', async () => {
    const r = await api.be.getField(p, 'CookTime');
    assert.ok(r.value != null);
    assert.equal(typeof (r.value as number), 'number');
  });

  it('setField + getField roundtrip', async () => {
    await api.be.setField(p, 'CookTime', 0);
    const r = await api.be.getField(p, 'CookTime');
    assert.equal(r.value, 0);
  });

  it('getField throws on invalid path', async () => {
    // non-existent deep path should return null, not throw
    const r = await api.be.getField(p, 'Items.99.nonexistent');
    assert.equal(r.value, null);
  });
});

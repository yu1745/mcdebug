import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createApi, pos, cleanupBlock, forceloadAt } from './helpers.js';

const P = (dx: number, dy: number, dz: number) => pos(dx + 90, dy, dz);

describe('world ops', () => {
  let api: ReturnType<typeof createApi>;

  before(() => { api = createApi(); });
  after(async () => { await api.close(); });

  it('place + get roundtrip', async () => {
    const p = P(0, 0, 0);
    await forceloadAt(api, p);
    try {
      await api.world.setBlock(p, 'minecraft:stone');
      const snap = await api.world.getBlock(p);
      assert.equal(snap.state.name, 'minecraft:stone');
    } finally {
      await cleanupBlock(api, p);
    }
  });

  it('get with includeNbt on furnace', async () => {
    const p = P(1, 0, 0);
    await forceloadAt(api, p);
    try {
      await api.world.setBlock(p, 'minecraft:furnace');
      const snap = await api.world.getBlock(p, { includeNbt: true });
      assert.equal(snap.state.name, 'minecraft:furnace');
      assert.equal(snap.hasBlockEntity, true);
      assert.ok(snap.nbt != null);
    } finally {
      await cleanupBlock(api, p);
    }
  });

  it('remove sets block to air', async () => {
    const p = P(2, 0, 0);
    await forceloadAt(api, p);
    try {
      await api.world.setBlock(p, 'minecraft:stone');
      await api.world.setBlock(p, 'minecraft:air');
      const snap = await api.world.getBlock(p);
      assert.equal(snap.state.name, 'minecraft:air');
    } finally {
      await cleanupBlock(api, p);
    }
  });

  it('findBlocks finds placed blocks', async () => {
    const p1 = P(3, 0, 0);
    const p2 = P(4, 0, 0);
    await forceloadAt(api, p1);
    try {
      await api.world.setBlock(p1, 'minecraft:diamond_ore');
      await api.world.setBlock(p2, 'minecraft:diamond_ore');
      const r = await api.scan.findBlocks(
        { from: P(3, -5, 0), to: P(5, 5, 0) },
        'minecraft:diamond_ore',
        { count: true },
      );
      assert.equal(r.positions.length, 2);
      assert.equal(r.count, 2);
    } finally {
      await cleanupBlock(api, p1);
      await cleanupBlock(api, p2);
    }
  });

  it('countByBlock groups by block id', async () => {
    const p1 = P(6, 0, 0);
    const p2 = P(7, 0, 0);
    await forceloadAt(api, p1);
    try {
      await api.world.setBlock(p1, 'minecraft:diamond_ore');
      await api.world.setBlock(p2, 'minecraft:diamond_ore');
      const r = await api.scan.countByBlock(
        { from: P(6, -5, 0), to: P(8, 5, 0) },
      );
      assert.ok('minecraft:diamond_ore' in r.counts);
      assert.equal(r.counts['minecraft:diamond_ore'], 2);
    } finally {
      await cleanupBlock(api, p1);
      await cleanupBlock(api, p2);
    }
  });
});

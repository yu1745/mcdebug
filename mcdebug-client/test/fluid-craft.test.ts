import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createApi, pos, cleanupBlock, forceloadAt } from './helpers.js';

const P = (dx: number, dy: number, dz: number) => pos(dx + 70, dy, dz);

describe('fluid and craft ops', () => {
  let api: ReturnType<typeof createApi>;
  const tank = P(0, 0, 0);

  before(async () => {
    api = createApi();
    await forceloadAt(api, tank);
    await cleanupBlock(api, tank);
  });

  after(async () => {
    await cleanupBlock(api, tank);
    await api.close();
  });

  it('reads, inserts, gets, and extracts fluid storage', async () => {
    await api.world.setBlock(tank, 'ic2_120:bronze_tank');

    const info = await api.fluid.info(tank);
    assert.equal(info.type, 'SingleVariantStorage');
    assert.equal(info.supportsInsertion, true);
    assert.equal(info.supportsExtraction, true);

    const inserted = await api.fluid.insert(tank, 'minecraft:water', 81000);
    assert.equal(inserted.inserted, 81000);
    assert.equal(inserted.remaining, 0);

    const current = await api.fluid.get(tank);
    assert.equal(current.fluid, 'minecraft:water');
    assert.equal(current.amount, 81000);

    const extracted = await api.fluid.extract(tank, 1000);
    assert.equal(extracted.fluid, 'minecraft:water');
    assert.equal(extracted.extracted, 1000);
    assert.equal(extracted.remaining, 0);
  });

  it('finds and executes vanilla crafting recipes', async () => {
    const grid = [
      { item: 'minecraft:oak_planks', count: 1 },
      null,
      null,
      { item: 'minecraft:oak_planks', count: 1 },
      null,
      null,
      null,
      null,
      null,
    ];

    const found = await api.craft.find(grid);
    assert.equal(found.matches.length, 1);
    assert.equal(found.matches[0].recipeId, 'minecraft:stick');

    const crafted = await api.craft.craft(grid);
    assert.equal(crafted.matched, true);
    if (!crafted.matched) assert.fail('expected craft match');
    assert.equal(crafted.result.item, 'minecraft:stick');
    assert.equal(crafted.result.count, 4);
  });
});

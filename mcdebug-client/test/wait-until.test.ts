import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createApi, pos, cleanupBlock, forceloadAt } from './helpers.js';
import { RpcError } from '../src/types.js';

describe('wait-until', () => {
  let api: ReturnType<typeof createApi>;
  const p = pos(0, 0, 0);

  before(async () => {
    api = createApi();
    await forceloadAt(api, p);
    await api.world.setBlock(p, 'minecraft:furnace');
  });

  after(async () => {
    await cleanupBlock(api, p);
    await api.close();
  });

  it('waits for furnace to smelt iron ore to ingot', async () => {
    // Load fuel first, then input
    await api.inv.setSlot(p, 1, 'minecraft:coal', 1);
    await api.inv.setSlot(p, 0, 'minecraft:iron_ore', 1);

    const coords = `${p[0]},${p[1]},${p[2]}`;
    const r = await api.wait.until(`inv[${coords}].2.item == "minecraft:iron_ingot"`, {
      timeoutTicks: 600,
    });
    assert.equal(r.matched, true);
    assert.ok(typeof r.ranTicks === 'number');
    assert.ok(r.ranTicks >= 0);
  });

  it('times out and throws TickTimeout', async () => {
    // Use a predicate that will never match
    try {
      await api.wait.until('tick >= 999999', { timeoutTicks: 5 });
      assert.fail('expected TickTimeout error');
    } catch (e) {
      assert.ok(e instanceof RpcError, `expected RpcError, got ${(e as Error).constructor.name}`);
      assert.equal((e as RpcError).code, -32006);
    }
  });
});

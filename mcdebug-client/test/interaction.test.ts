import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createApi, pos, cleanupBlock, forceloadAt } from './helpers.js';
import type { DebugApi } from '../src/api.js';
import type { Box, Pos } from '../src/types.js';

const P = (dx: number, dy: number, dz: number) => pos(dx + 40, dy, dz);

async function liveEntityUuid(
  api: DebugApi,
  box: Box,
  type: string,
): Promise<string> {
  const result = await api.scan.findEntities(box, { type });
  const entity = result.entities.find((e) => (e.health ?? 1) > 0);
  assert.ok(entity, `expected live ${type} in test box`);
  return entity.uuid;
}

describe('player interaction ops', () => {
  let api: ReturnType<typeof createApi>;
  const support = P(0, 0, 0);
  const placed = P(0, 1, 0);
  const lever = P(2, 0, 0);
  const breakBlock = P(3, 0, 0);
  const tempBlocks: Pos[] = [support, placed, lever, breakBlock];

  before(async () => {
    api = createApi();
    await forceloadAt(api, support);
    for (const p of tempBlocks) await cleanupBlock(api, p);
    await api.server.runCommand('kill @e[type=minecraft:cow,x=140,y=64,z=100,distance=..16]');
    await api.server.runCommand('kill @e[type=minecraft:iron_golem,x=140,y=64,z=100,distance=..16]');
  });

  after(async () => {
    for (const p of tempBlocks) await cleanupBlock(api, p);
    await api.server.runCommand('kill @e[type=minecraft:cow,x=140,y=64,z=100,distance=..16]');
    await api.server.runCommand('kill @e[type=minecraft:iron_golem,x=140,y=64,z=100,distance=..16]');
    await api.close();
  });

  it('placeAsPlayer places contextual blocks', async () => {
    await api.world.setBlock(support, 'minecraft:stone');
    const result = await api.world.placeAsPlayer(placed, 'minecraft:torch', 'up', {
      neighbor: support,
      playerFacing: 'south',
    });
    const block = await api.world.getBlock(placed);
    assert.equal(result.ok, true);
    assert.equal(block.state.name, 'minecraft:torch');
  });

  it('useOnBlock right-clicks block behavior', async () => {
    await api.server.runCommand('setblock 142 64 100 minecraft:lever[face=floor,facing=north,powered=false]');
    const result = await api.world.useOnBlock(lever, 'up');
    const block = await api.world.getBlock(lever);
    assert.equal(result.success, true);
    assert.equal(result.blockConsumed, true);
    assert.equal(block.state.props.powered, 'true');
  });

  it('attackBlock left-clicks and breaks a block', async () => {
    await api.world.setBlock(breakBlock, 'minecraft:glass');
    const result = await api.world.attackBlock(breakBlock, 'up');
    const block = await api.world.getBlock(breakBlock);
    assert.equal(result.broken, true);
    assert.equal(block.state.name, 'minecraft:air');
  });

  it('useItem right-clicks item-in-air behavior', async () => {
    const result = await api.world.useItem('ic2_120:nano_saber', {
      nbt: { Energy: 160000 },
    });
    assert.equal(result.success, true);
    assert.equal(result.itemAfter.item, 'ic2_120:nano_saber');
    assert.equal((result.itemAfter.nbt as Record<string, unknown>).NanoSaberActive, 1);
  });

  it('interactEntity right-clicks entity item behavior', async () => {
    await api.server.runCommand('kill @e[type=minecraft:cow,x=145,y=64,z=100,distance=..4]');
    await api.server.runCommand('summon minecraft:cow 145 64 100 {NoAI:1b,NoGravity:1b,Health:10.0f}');
    const uuid = await liveEntityUuid(
      api,
      { from: P(4, -1, -1), to: P(6, 1, 1) },
      'minecraft:cow',
    );
    const result = await api.world.interactEntity(uuid, { item: 'minecraft:bucket' });
    assert.equal(result.success, true);
    assert.equal(result.itemAfter.item, 'minecraft:milk_bucket');
  });

  it('attackEntity applies fully charged survival weapon damage', async () => {
    await api.server.runCommand('kill @e[type=minecraft:cow,x=146,y=64,z=100,distance=..4]');
    await api.server.runCommand('summon minecraft:cow 146 64 100 {NoAI:1b,NoGravity:1b,Health:10.0f}');
    const uuid = await liveEntityUuid(
      api,
      { from: P(5, -1, -1), to: P(7, 1, 1) },
      'minecraft:cow',
    );
    const result = await api.world.attackEntity(uuid, { item: 'minecraft:diamond_sword' });
    assert.equal(result.eventConsumed, false);
    assert.equal(result.attackDamageBefore, 7);
    assert.equal(result.attackCooldownBefore, 1);
    assert.equal(result.entityHealth, 3);
    assert.equal(result.itemAfter.item, 'minecraft:diamond_sword');
    assert.deepEqual(result.itemAfter.nbt, { Damage: 1 });
  });
});

import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createApi } from './helpers.js';
import { version } from '../src/version.js';

describe('server.status', () => {
  let api: ReturnType<typeof createApi>;

  before(() => { api = createApi(); });

  after(async () => { await api.close(); });

  it('returns valid server info', async () => {
    const s = await api.server.status();
    assert.equal(s.mcVersion, '1.20.1');
    assert.equal(s.modVersion, version);
    assert.equal(s.modLoader, 'fabric');
    assert.ok(s.dims.includes('minecraft:overworld'));
    assert.ok(s.dims.includes('minecraft:the_nether'));
    assert.ok(s.dims.includes('minecraft:the_end'));
    assert.ok(typeof s.tick === 'number');
    assert.ok(s.tick > 0);
  });
});

import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createApi } from './helpers.js';

describe('forceload ops', () => {
  let api: ReturnType<typeof createApi>;

  before(() => { api = createApi(); });

  after(async () => { await api.close(); });

  it('forceload a chunk', async () => {
    const r = await api.world.forceloadChunk(6, 6);
    assert.equal(r.forced, true);
    assert.equal(r.chunk[0], 6);
    assert.equal(r.chunk[1], 6);
  });

  it('unforceload a chunk', async () => {
    // Use a different chunk (8,8) so we don't break other tests that need (6,6)
    await api.world.forceloadChunk(8, 8);
    const r = await api.world.unforceloadChunk(8, 8);
    assert.equal(r.forced, false);
    assert.equal(r.changed, true);
  });

  it('duplicate forceload does not error', async () => {
    await api.world.forceloadChunk(7, 7);
    const r = await api.world.forceloadChunk(7, 7);
    // Second call: already forced, changed may be false but no error
    assert.equal(r.forced, true);
    // Cleanup
    await api.world.unforceloadChunk(7, 7);
  });
});

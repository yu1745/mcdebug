import { RpcClient } from '../src/client.js';
import { DebugApi } from '../src/api.js';
import type { Pos } from '../src/types.js';

const SOCKET = process.env.MCDEBUG_SOCKET;

/** Test area origin — far from spawn (0,0) to avoid leftover interference. */
const TEST_ORIGIN: Pos = [100, 64, 100];

/** Create a DebugApi connected to the test server. Caller must call api.close() when done. */
export function createApi(): DebugApi {
  return new DebugApi(new RpcClient({ socket: SOCKET, timeoutMs: 5000 }));
}

/** The base position for all test fixtures. */
export function origin(): Pos {
  return TEST_ORIGIN;
}

/** Compute an absolute position relative to the test origin. */
export function pos(dx: number, dy: number, dz: number): Pos {
  return [TEST_ORIGIN[0] + dx, TEST_ORIGIN[1] + dy, TEST_ORIGIN[2] + dz] as Pos;
}

/** Derive chunk coordinates from a block position. */
export function chunkOf(pos: Pos): [number, number] {
  return [pos[0] >> 4, pos[2] >> 4];
}

/** Force-load the chunk containing the given block position so its BEs tick. */
export async function forceloadAt(api: DebugApi, pos: Pos): Promise<void> {
  const [cx, cz] = chunkOf(pos);
  await api.world.forceloadChunk(cx, cz);
}

/** Set a block to air — lightweight cleanup. */
export async function cleanupBlock(api: DebugApi, pos: Pos): Promise<void> {
  await api.world.setBlock(pos, 'minecraft:air');
}

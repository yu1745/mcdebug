import { RpcClient } from './client.js';
import {
  BlockSnapshot,
  BlockStateSpec,
  Box,
  JsonNbt,
  Pos,
  ServerStatus,
  WaitResult,
} from './types.js';

/**
 * High-level typed API on top of RpcClient.
 * Matches the methods described in C:\Users\wangyu\.claude\plans\typed-wandering-wall.md
 */
export class DebugApi {
  constructor(public readonly rpc: RpcClient) {}

  world = {
    getBlock: (p: Pos, dimOrOpts?: string | { dim?: string; includeNbt?: boolean }) => {
      const opts = typeof dimOrOpts === 'string' ? { dim: dimOrOpts } : (dimOrOpts ?? {});
      return this.rpc.call<BlockSnapshot>('world.getBlock', { pos: p, dim: opts.dim, includeNbt: opts.includeNbt ?? false });
    },
    setBlock: (
      p: Pos,
      block: string,
      state?: BlockStateSpec,
      opts?: { flags?: number; dim?: string },
    ) =>
      this.rpc.call('world.setBlock', {
        pos: p,
        block,
        stateProps: state?.props,
        flags: opts?.flags,
        dim: opts?.dim,
      }),
    setBlocks: (
      ops: Array<{ pos: Pos; block: string; state?: BlockStateSpec }>,
      opts?: { atomic?: boolean; dim?: string },
    ) =>
      this.rpc.call('world.setBlocks', {
        ops: ops.map((o) => ({ pos: o.pos, block: o.block, stateProps: o.state?.props })),
        atomic: opts?.atomic,
        dim: opts?.dim,
      }),
    getRegion: (box: Box, opts?: { includeNbt?: boolean; dim?: string }) =>
      this.rpc.call<{ blocks: Array<BlockSnapshot & { pos: Pos }> }>('world.getRegion', {
        box,
        includeNbt: opts?.includeNbt,
        dim: opts?.dim,
      }),
    selectBlocks: (
      box: Box,
      pred: { block?: string; tag?: string },
      opts?: { includeNbt?: boolean; dim?: string },
    ) =>
      this.rpc.call<{ matches: Array<{ pos: Pos; nbt?: JsonNbt }> }>('world.selectBlocks', {
        box,
        predicate: pred,
        includeNbt: opts?.includeNbt,
        dim: opts?.dim,
      }),
    forceloadChunk: (cx: number, cz: number, opts?: { dim?: string }) =>
      this.rpc.call<{ chunk: [number, number]; forced: boolean; changed: boolean; dim: string }>('world.forceloadChunk', { chunk: [cx, cz], dim: opts?.dim }),
    unforceloadChunk: (cx: number, cz: number, opts?: { dim?: string }) =>
      this.rpc.call<{ chunk: [number, number]; forced: boolean; changed: boolean; dim: string }>('world.unforceloadChunk', { chunk: [cx, cz], dim: opts?.dim }),
  };

  be = {
    getNbt: (p: Pos, dim?: string) =>
      this.rpc.call<{ nbt: JsonNbt }>('be.getNbt', { pos: p, dim }),
    setNbt: (p: Pos, nbt: JsonNbt, dim?: string) =>
      this.rpc.call('be.setNbt', { pos: p, nbt, dim }),
    getField: (p: Pos, path: string, dim?: string) =>
      this.rpc.call<{ value: JsonNbt }>('be.getField', { pos: p, path, dim }),
    setField: (p: Pos, path: string, value: JsonNbt, dim?: string) =>
      this.rpc.call('be.setField', { pos: p, path, value, dim }),
  };

  inv = {
    getSize: (p: Pos, dim?: string) =>
      this.rpc.call<{ size: number }>('inv.getSize', { pos: p, dim }),
    getSlot: (p: Pos, slot: number, dim?: string) =>
      this.rpc.call<{ item: string | null; count: number; nbt: JsonNbt | null; maxCount: number }>(
        'inv.getSlot',
        { pos: p, slot, dim },
      ),
    setSlot: (p: Pos, slot: number, item: string | null, count: number, nbt?: JsonNbt, dim?: string) =>
      this.rpc.call('inv.setSlot', { pos: p, slot, item, count, nbt, dim }),
    insert: (
      p: Pos,
      item: string,
      count: number,
      opts?: { slot?: number; nbt?: JsonNbt; simulate?: boolean; dim?: string },
    ) =>
      this.rpc.call<{ inserted: number; remaining: number }>('inv.insert', {
        pos: p,
        item,
        count,
        nbt: opts?.nbt,
        slot: opts?.slot,
        simulate: opts?.simulate,
        dim: opts?.dim,
      }),
    extract: (
      p: Pos,
      item: string,
      count: number,
      opts?: { slot?: number; simulate?: boolean; dim?: string },
    ) =>
      this.rpc.call<{ extracted: number; remaining: number }>('inv.extract', {
        pos: p,
        item,
        count,
        slot: opts?.slot,
        simulate: opts?.simulate,
        dim: opts?.dim,
      }),
  };

  /** tick is intentionally not exposed: server drives ticks, client never advances them. */
  tick = undefined as never;

  fluid = {
    info: (
      p: Pos,
      opts?: { side?: string; dim?: string },
    ) =>
      this.rpc.call<{
        side: string;
        type: string;
        supportsInsertion: boolean;
        supportsExtraction: boolean;
        parts: Array<{ fluid: string | null; amount: number; capacity: number }>;
      }>('fluid.info', { pos: p, side: opts?.side, dim: opts?.dim }),
    get: (
      p: Pos,
      opts?: { side?: string; index?: number; dim?: string },
    ) =>
      this.rpc.call<{ index: number; fluid: string | null; amount: number; capacity: number }>(
        'fluid.get',
        { pos: p, side: opts?.side, index: opts?.index, dim: opts?.dim },
      ),
    insert: (
      p: Pos,
      fluid: string,
      amount: number,
      opts?: { side?: string; index?: number; dim?: string },
    ) =>
      this.rpc.call<{ index: number; requested: number; inserted: number; remaining: number }>(
        'fluid.insert',
        { pos: p, side: opts?.side, index: opts?.index, fluid, amount, dim: opts?.dim },
      ),
    extract: (
      p: Pos,
      amount: number,
      opts?: { side?: string; index?: number; dim?: string },
    ) =>
      this.rpc.call<{ index: number; fluid: string; requested: number; extracted: number; remaining: number }>(
        'fluid.extract',
        { pos: p, side: opts?.side, index: opts?.index, amount, dim: opts?.dim },
      ),
  };

  wait = {
    /**
     * Passive wait for a condition to become true. Does NOT advance server ticks.
     * Implementation: registers a ServerTickEvents.END_SERVER_TICK callback on the server
     * that evaluates the predicate on each natural tick.
     */
    until: (predicate: string, opts?: { timeoutTicks?: number; pollIntervalTicks?: number }) =>
      this.rpc.call<WaitResult>('wait.until', {
        predicate,
        timeoutTicks: opts?.timeoutTicks,
        pollIntervalTicks: opts?.pollIntervalTicks,
      }),
  };

  scan = {
    findBlocks: (box: Box, block: string, opts?: { count?: boolean; dim?: string }) =>
      this.rpc.call<{ positions: Pos[]; count?: number }>('scan.findBlocks', {
        box,
        block,
        count: opts?.count,
        dim: opts?.dim,
      }),
    countByBlock: (box: Box, dim?: string) =>
      this.rpc.call<{ counts: Record<string, number> }>('scan.countByBlock', { box, dim }),
  };

  server = {
    status: () => this.rpc.call<ServerStatus>('server.status'),
    listDimensions: () => this.rpc.call<{ dims: string[] }>('server.listDimensions'),
    runCommand: (
      command: string,
      opts?: { dim?: string },
    ) =>
      this.rpc.call<{ success: boolean; result: number; output: string }>(
        'server.runCommand',
        { command, dim: opts?.dim },
      ),
  };

  async close(): Promise<void> {
    await this.rpc.close();
  }
}

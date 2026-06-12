/**
 * High-level typed API on top of RpcClient.
 * Matches the methods described in C:\Users\wangyu\.claude\plans\typed-wandering-wall.md
 */
export class DebugApi {
    rpc;
    constructor(rpc) {
        this.rpc = rpc;
    }
    world = {
        getBlock: (p, dimOrOpts) => {
            const opts = typeof dimOrOpts === 'string' ? { dim: dimOrOpts } : (dimOrOpts ?? {});
            return this.rpc.call('world.getBlock', { pos: p, dim: opts.dim, includeNbt: opts.includeNbt ?? false });
        },
        setBlock: (p, block, state, opts) => this.rpc.call('world.setBlock', {
            pos: p,
            block,
            stateProps: state?.props,
            flags: opts?.flags,
            dim: opts?.dim,
        }),
        setBlocks: (ops, opts) => this.rpc.call('world.setBlocks', {
            ops: ops.map((o) => ({ pos: o.pos, block: o.block, stateProps: o.state?.props })),
            atomic: opts?.atomic,
            dim: opts?.dim,
        }),
        /**
         * Place a block as if a player were clicking the side of an adjacent block.
         * Goes through the full BlockItem / ItemPlacementContext pipeline with a
         * stable fake ServerPlayerEntity as the placer, so stairs / doors / furnaces
         * / chests, etc. derive their state from `face` + `playerFacing` (NOT from
         * defaultState). Fires sounds, game events, onPlaced, and
         * Criteria.PLACED_BLOCK. May fail if the target pos is non-replaceable.
         */
        placeAsPlayer: (pos, block, face, opts) => this.rpc.call('world.placeAsPlayer', {
            pos,
            block,
            face,
            neighbor: opts?.neighbor,
            playerFacing: opts?.playerFacing,
            nbt: opts?.nbt,
            dim: opts?.dim,
        }),
        getRegion: (box, opts) => this.rpc.call('world.getRegion', {
            box,
            includeNbt: opts?.includeNbt,
            dim: opts?.dim,
        }),
        selectBlocks: (box, pred, opts) => this.rpc.call('world.selectBlocks', {
            box,
            predicate: pred,
            includeNbt: opts?.includeNbt,
            dim: opts?.dim,
        }),
        forceloadChunk: (cx, cz, opts) => this.rpc.call('world.forceloadChunk', { chunk: [cx, cz], dim: opts?.dim }),
        unforceloadChunk: (cx, cz, opts) => this.rpc.call('world.unforceloadChunk', { chunk: [cx, cz], dim: opts?.dim }),
    };
    be = {
        getNbt: (p, dim) => this.rpc.call('be.getNbt', { pos: p, dim }),
        setNbt: (p, nbt, dim) => this.rpc.call('be.setNbt', { pos: p, nbt, dim }),
        getField: (p, path, dim) => this.rpc.call('be.getField', { pos: p, path, dim }),
        setField: (p, path, value, dim) => this.rpc.call('be.setField', { pos: p, path, value, dim }),
    };
    inv = {
        getSize: (p, dim) => this.rpc.call('inv.getSize', { pos: p, dim }),
        getSlot: (p, slot, dim) => this.rpc.call('inv.getSlot', { pos: p, slot, dim }),
        setSlot: (p, slot, item, count, nbt, dim) => this.rpc.call('inv.setSlot', { pos: p, slot, item, count, nbt, dim }),
        insert: (p, item, count, opts) => this.rpc.call('inv.insert', {
            pos: p,
            item,
            count,
            nbt: opts?.nbt,
            slot: opts?.slot,
            simulate: opts?.simulate,
            dim: opts?.dim,
        }),
        extract: (p, item, count, opts) => this.rpc.call('inv.extract', {
            pos: p,
            item,
            count,
            slot: opts?.slot,
            simulate: opts?.simulate,
            dim: opts?.dim,
        }),
    };
    /** tick is intentionally not exposed: server drives ticks, client never advances them. */
    tick = undefined;
    fluid = {
        info: (p, opts) => this.rpc.call('fluid.info', { pos: p, side: opts?.side, dim: opts?.dim }),
        get: (p, opts) => this.rpc.call('fluid.get', { pos: p, side: opts?.side, index: opts?.index, dim: opts?.dim }),
        insert: (p, fluid, amount, opts) => this.rpc.call('fluid.insert', { pos: p, side: opts?.side, index: opts?.index, fluid, amount, dim: opts?.dim }),
        extract: (p, amount, opts) => this.rpc.call('fluid.extract', { pos: p, side: opts?.side, index: opts?.index, amount, dim: opts?.dim }),
    };
    wait = {
        /**
         * Passive wait for a condition to become true. Does NOT advance server ticks.
         * Implementation: registers a ServerTickEvents.END_SERVER_TICK callback on the server
         * that evaluates the predicate on each natural tick.
         */
        until: (predicate, opts) => this.rpc.call('wait.until', {
            predicate,
            timeoutTicks: opts?.timeoutTicks,
            pollIntervalTicks: opts?.pollIntervalTicks,
        }),
    };
    scan = {
        findBlocks: (box, block, opts) => this.rpc.call('scan.findBlocks', {
            box,
            block,
            count: opts?.count,
            dim: opts?.dim,
        }),
        countByBlock: (box, dim) => this.rpc.call('scan.countByBlock', { box, dim }),
    };
    server = {
        status: () => this.rpc.call('server.status'),
        listDimensions: () => this.rpc.call('server.listDimensions'),
        runCommand: (command, opts) => this.rpc.call('server.runCommand', { command, dim: opts?.dim }),
    };
    async close() {
        await this.rpc.close();
    }
}

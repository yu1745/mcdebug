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
            flags: opts?.flags,
            dim: opts?.dim,
        }),
        fillBox: (box, block, state, opts) => this.rpc.call('world.fillBox', {
            box,
            block,
            stateProps: state?.props,
            flags: opts?.flags,
            dim: opts?.dim,
            maxBlocks: opts?.maxBlocks,
        }),
        clearBox: (box, opts) => this.rpc.call('world.clearBox', {
            box,
            flags: opts?.flags,
            dim: opts?.dim,
            maxBlocks: opts?.maxBlocks,
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
        /**
         * Simulate right-clicking (using) a block with an item in hand.
         * Mirrors the full ServerPlayerInteractionManager.interactBlock pipeline:
         *   0. Fabric API UseBlockCallback (mod handlers like IC2 wrench register here)
         *   1. BlockState.onUse — block handles (lever toggle, button press, door open)
         *   2. ItemStack.useOnBlock — item handles if block returned PASS
         * Vanilla sneaking check: shift+right-click with item → skip block.onUse.
         * Uses the same stable fake player as placeAsPlayer (creative + invulnerable).
         */
        useOnBlock: (pos, face, opts) => this.rpc.call('world.useOnBlock', {
            pos,
            face,
            item: opts?.item,
            count: opts?.count,
            nbt: opts?.nbt,
            sneaking: opts?.sneaking,
            playerFacing: opts?.playerFacing,
            gamemode: opts?.gamemode,
            dim: opts?.dim,
        }),
        /**
         * Simulate right-clicking with the item in air.
         * Triggers Item.use(world, player, hand), for tools like nano saber that
         * toggle state without a block/entity target.
         */
        useItem: (item, opts) => this.rpc.call('world.useItem', {
            item,
            count: opts?.count,
            nbt: opts?.nbt,
            sneaking: opts?.sneaking,
            dim: opts?.dim,
        }),
        /**
         * Simulate right-clicking a ranged weapon and holding it for `holdTicks`
         * before releasing — the full vanilla item-use lifecycle used by bows,
         * crossbows, tridents and any modded ranged weapon (use → usageTick × N →
         * onStoppedUsing). Weapons that fire directly from use() (IC2 mining laser,
         * TConstruct shuriken/throwing axe) need holdTicks=0.
         *
         * Projectiles are real entities spawned into the world; observe them with
         * find-entities / wait-until.
         */
        useItemHold: (item, opts) => this.rpc.call('world.useItemHold', {
            item,
            count: opts?.count,
            nbt: opts?.nbt,
            ammo: opts?.ammo,
            ammoCount: opts?.ammoCount,
            targetUuid: opts?.targetUuid,
            direction: opts?.direction,
            holdTicks: opts?.holdTicks,
            repeat: opts?.repeat,
            playerPos: opts?.playerPos,
            dim: opts?.dim,
        }),
        /**
         * Simulate left-clicking (attacking) a block.
         * Mirrors the full ServerPlayerInteractionManager.processBlockBreakingAction pipeline:
         *   0. Fabric API AttackBlockCallback (mod handlers like IC2 wrench disassembly)
         *   1. Block.onBlockBreakStart, then break in creative mode
         */
        attackBlock: (pos, face, opts) => this.rpc.call('world.attackBlock', {
            pos,
            face,
            item: opts?.item,
            count: opts?.count,
            nbt: opts?.nbt,
            armor: opts?.armor,
            gamemode: opts?.gamemode,
            dim: opts?.dim,
        }),
        /**
         * Simulate right-clicking (using) an entity with an item in hand.
         * Mirrors PlayerEntity.interact(entity, hand):
         *   0. Fabric API UseEntityCallback
         *   1. entity.interact(player, hand)
         *   2. item.useOnEntity(player, livingEntity, hand) — e.g. bucket milks cow
         */
        interactEntity: (entityUuid, opts) => this.rpc.call('world.interactEntity', {
            entityUuid,
            item: opts?.item,
            count: opts?.count,
            nbt: opts?.nbt,
            sneaking: opts?.sneaking,
            playerFacing: opts?.playerFacing,
            dim: opts?.dim,
        }),
        /**
         * Simulate left-clicking (attacking) an entity.
         * Mirrors PlayerEntity.attack(entity):
         *   0. Fabric API AttackEntityCallback
         *   1. PlayerEntity.attack(entity) — damage, knockback, sweep, etc.
         */
        attackEntity: (entityUuid, opts) => this.rpc.call('world.attackEntity', {
            entityUuid,
            item: opts?.item,
            count: opts?.count,
            nbt: opts?.nbt,
            armor: opts?.armor,
            playerFacing: opts?.playerFacing,
            dim: opts?.dim,
        }),
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
    storage = {
        list: (target, opts) => this.rpc.call('storage.list', { target, side: opts?.side }),
        get: (target, handle, opts) => this.rpc.call('storage.get', { target, handle, side: opts?.side }),
        insert: (target, handle, resource, amount, opts) => this.rpc.call('storage.insert', {
            target,
            handle,
            resource,
            amount,
            side: opts?.side,
            simulate: opts?.simulate,
        }),
        extract: (target, handle, resource, amount, opts) => this.rpc.call('storage.extract', {
            target,
            handle,
            resource,
            amount,
            side: opts?.side,
            simulate: opts?.simulate,
        }),
        transfer: (from, to, resource, amount, opts) => this.rpc.call('storage.transfer', {
            from,
            to,
            resource,
            amount,
            fromSide: opts?.fromSide,
            toSide: opts?.toSide,
            simulate: opts?.simulate,
        }),
    };
    snapshot = {
        capture: (options) => this.rpc.call('snapshot.capture', options),
        diff: (before, after) => this.rpc.call('snapshot.diff', { before, after }),
    };
    trace = {
        start: (options) => this.rpc.call('trace.start', options),
        stop: (traceId) => this.rpc.call('trace.stop', { traceId }),
        get: (traceId) => this.rpc.call('trace.get', { traceId }),
    };
    screen = {
        openBlock: (pos, opts) => this.rpc.call('screen.openBlock', {
            pos,
            dim: opts?.dim,
            player: opts?.player,
            side: opts?.side,
        }),
        snapshot: (screenId) => this.rpc.call('screen.snapshot', { screenId }),
        setPlayerSlot: (screenId, slot, stack) => this.rpc.call('screen.setPlayerSlot', { screenId, slot, stack }),
        clickSlot: (screenId, slot, button, actionType) => this.rpc.call('screen.clickSlot', { screenId, slot, button, actionType }),
        quickMove: (screenId, slot) => this.rpc.call('screen.quickMove', { screenId, slot }),
        close: (screenId) => this.rpc.call('screen.close', { screenId }),
    };
    redstone = {
        getPower: (pos, opts) => this.rpc.call('redstone.getPower', { pos, side: opts?.side, dim: opts?.dim }),
        isPowered: (pos, opts) => this.rpc.call('redstone.isPowered', { pos, dim: opts?.dim }),
        setLever: (pos, powered, opts) => this.rpc.call('redstone.setLever', { pos, powered, dim: opts?.dim }),
        pulse: (pos, ticks = 2, opts) => this.rpc.call('redstone.pulse', {
            pos,
            ticks,
            dim: opts?.dim,
        }),
        notifyNeighbors: (pos, opts) => this.rpc.call('redstone.notifyNeighbors', {
            pos,
            dim: opts?.dim,
        }),
    };
    entity = {
        spawn: (type, pos, opts) => this.rpc.call('entity.spawn', {
            type,
            pos,
            dim: opts?.dim,
            yaw: opts?.yaw,
            pitch: opts?.pitch,
            nbt: opts?.nbt,
            stack: opts?.stack,
            includeNbt: opts?.includeNbt,
        }),
        getNbt: (uuid, opts) => this.rpc.call('entity.getNbt', { uuid, dim: opts?.dim }),
        setNbt: (uuid, nbt, opts) => this.rpc.call('entity.setNbt', { uuid, nbt, dim: opts?.dim, replace: opts?.replace }),
        teleport: (uuid, pos, opts) => this.rpc.call('entity.teleport', {
            uuid,
            pos,
            dim: opts?.dim,
            toDim: opts?.toDim,
            yaw: opts?.yaw,
            pitch: opts?.pitch,
            includeNbt: opts?.includeNbt,
        }),
        remove: (uuid, opts) => this.rpc.call('entity.remove', {
            uuid,
            dim: opts?.dim,
            includeNbt: opts?.includeNbt,
        }),
        listItems: (box, opts) => this.rpc.call('entity.listItems', {
            box,
            dim: opts?.dim,
            item: opts?.item,
            includeNbt: opts?.includeNbt,
        }),
        collectItems: (box, opts) => this.rpc.call('entity.collectItems', {
            box,
            dim: opts?.dim,
            item: opts?.item,
            remove: opts?.remove,
            includeNbt: opts?.includeNbt,
        }),
    };
    fixture = {
        capture: (box, opts) => this.rpc.call('fixture.capture', { box, dim: opts?.dim, includeNbt: opts?.includeNbt }),
        load: (fixture, opts) => this.rpc.call('fixture.load', {
            fixture,
            origin: opts?.origin,
            dim: opts?.dim,
            flags: opts?.flags,
        }),
    };
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
    craft = {
        /**
         * Simulate a single craft of a 3x3 grid. Goes through the server's full
         * RecipeManager — vanilla ShapedRecipe / ShapelessRecipe, AND modded recipe
         * types (e.g. ic2_120:battery_energy_shaped, ic2_120:damage_tool_shapeless).
         *
         * Use the result and `remainder` to verify modded craft behavior:
         *  - ic2_120:battery_energy_shaped:  result.nbt.charge should equal the sum
         *                                    of all input IBatteryItem / IElectricTool
         *                                    charges (capped at output capacity).
         *  - ic2_120:damage_tool_shapeless:  compare remainder[slotOfHammer].damage
         *                                    with the input — should be +1.
         *
         * If `recipeId` is omitted, the first recipe whose matches() returns true is
         * used. If none match, returns { matched: false, candidates: [] }. If multiple
         * match, pass recipeId to disambiguate.
         */
        craft: (grid, opts) => this.rpc.call('craft.craft', { grid, recipeId: opts?.recipeId, dim: opts?.dim }),
        /**
         * Diagnostic: list every crafting recipe whose matches() returns true for the
         * given grid. Use this to find the right `recipeId` to pass to `craft()`.
         */
        find: (grid, opts) => this.rpc.call('craft.find', { grid, dim: opts?.dim }),
    };
    scan = {
        findBlocks: (box, block, opts) => this.rpc.call('scan.findBlocks', {
            box,
            block,
            count: opts?.count,
            dim: opts?.dim,
        }),
        countByBlock: (box, dim) => this.rpc.call('scan.countByBlock', { box, dim }),
        findEntities: (box, opts) => this.rpc.call('scan.findEntities', {
            box,
            type: opts?.type,
            includeNbt: opts?.includeNbt,
            dim: opts?.dim,
        }),
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

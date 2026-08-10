import { RpcClient } from './client.js';
import {
  BlockSnapshot,
  BlockStateSpec,
  Box,
  Direction,
  EntitySnapshot,
  EntitySpawnOptions,
  FixtureJson,
  ItemStackJson,
  JsonNbt,
  Pos,
  RedstonePowerSnapshot,
  ScreenSnapshot,
  ServerStatus,
  Side,
  SnapshotCaptureOptions,
  SnapshotDiffResult,
  StorageGetResult,
  StorageListResult,
  StorageMoveResult,
  StorageResource,
  Target,
  TraceResult,
  TraceStartOptions,
  TraceStartResult,
  WaitResult,
  WorldBoxEditResult,
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
      opts?: { flags?: number; dim?: string },
    ) =>
      this.rpc.call('world.setBlocks', {
        ops: ops.map((o) => ({ pos: o.pos, block: o.block, stateProps: o.state?.props })),
        flags: opts?.flags,
          dim: opts?.dim,
        }),
    fillBox: (
      box: Box,
      block: string,
      state?: BlockStateSpec,
      opts?: { flags?: number; dim?: string; maxBlocks?: number },
    ) =>
      this.rpc.call<WorldBoxEditResult>('world.fillBox', {
        box,
        block,
        stateProps: state?.props,
        flags: opts?.flags,
        dim: opts?.dim,
        maxBlocks: opts?.maxBlocks,
      }),
    clearBox: (
      box: Box,
      opts?: { flags?: number; dim?: string; maxBlocks?: number },
    ) =>
      this.rpc.call<WorldBoxEditResult>('world.clearBox', {
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
    placeAsPlayer: (
      pos: Pos,
      block: string,
      face: 'up' | 'down' | 'north' | 'south' | 'east' | 'west',
      opts?: {
        neighbor?: Pos;
        playerFacing?: 'up' | 'down' | 'north' | 'south' | 'east' | 'west';
        nbt?: JsonNbt;
        dim?: string;
      },
    ) =>
      this.rpc.call<{
        ok: boolean;
        pos: Pos;
        neighbor: Pos;
        face: string;
        playerFacing: string;
        placer: string;
        placerUuid: string;
        previous: { name: string; props: Record<string, string> };
        state: { name: string; props: Record<string, string> };
      }>('world.placeAsPlayer', {
        pos,
        block,
        face,
        neighbor: opts?.neighbor,
        playerFacing: opts?.playerFacing,
        nbt: opts?.nbt,
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
      pred: { block?: string },
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
    /**
     * Simulate right-clicking (using) a block with an item in hand.
     * Mirrors the full ServerPlayerInteractionManager.interactBlock pipeline:
     *   0. Fabric API UseBlockCallback (mod handlers like IC2 wrench register here)
     *   1. BlockState.onUse — block handles (lever toggle, button press, door open)
     *   2. ItemStack.useOnBlock — item handles if block returned PASS
     * Vanilla sneaking check: shift+right-click with item → skip block.onUse.
     * Uses the same stable fake player as placeAsPlayer (creative + invulnerable).
     */
    useOnBlock: (
      pos: Pos,
      face: 'up' | 'down' | 'north' | 'south' | 'east' | 'west',
      opts?: {
        item?: string;
        count?: number;
        nbt?: JsonNbt;
        sneaking?: boolean;
        playerFacing?: 'north' | 'south' | 'east' | 'west';
        gamemode?: string;
        dim?: string;
      },
    ) =>
      this.rpc.call<{
        success: boolean;
        action: string;
        pos: Pos;
        face: string;
        sneaking: boolean;
        playerFacing: string;
        eventConsumed: boolean;
        blockConsumed: boolean;
        itemConsumed: boolean;
        itemBefore: ItemStackJson;
        itemAfter: ItemStackJson;
        blockState: { name: string; props: Record<string, string> };
      }>('world.useOnBlock', {
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
    useItem: (
      item: string,
      opts?: {
        count?: number;
        nbt?: JsonNbt;
        sneaking?: boolean;
        dim?: string;
      },
    ) =>
      this.rpc.call<{
        success: boolean;
        action: string;
        sneaking: boolean;
        itemBefore: ItemStackJson;
        itemAfter: ItemStackJson;
      }>('world.useItem', {
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
    useItemHold: (
      item: string,
      opts?: {
        count?: number;
        nbt?: JsonNbt;
        ammo?: string;
        ammoCount?: number;
        targetUuid?: string;
        direction?: 'north' | 'south' | 'east' | 'west' | 'up' | 'down';
        holdTicks?: number;
        repeat?: number;
        playerPos?: Pos;
        dim?: string;
      },
    ) =>
      this.rpc.call<{
        cycles: Array<{
          cycle: number;
          useResult: string;
          usingItem: boolean;
          stopped: boolean;
          stillUsingAfterRelease: boolean;
          itemAfter: ItemStackJson;
        }>;
        projectiles: Array<{
          type: string;
          uuid: string;
          pos: [number, number, number];
          velocity: [number, number, number];
        }>;
        ammo: ItemStackJson;
        itemBefore: ItemStackJson;
      }>('world.useItemHold', {
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
    attackBlock: (
      pos: Pos,
      face: 'up' | 'down' | 'north' | 'south' | 'east' | 'west',
      opts?: {
        item?: string;
        count?: number;
        nbt?: JsonNbt;
        armor?: Partial<Record<'head' | 'chest' | 'legs' | 'feet', { item: string; count?: number; nbt?: JsonNbt }>>;
        gamemode?: 'survival' | 'creative';
        dim?: string;
      },
    ) =>
      this.rpc.call<{
        broken: boolean;
        eventConsumed: boolean;
        pos: Pos;
        face: string;
        itemBefore: ItemStackJson;
        itemAfter: ItemStackJson;
        blockState: { name: string; props: Record<string, string> };
      }>('world.attackBlock', {
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
    interactEntity: (
      entityUuid: string,
      opts?: {
        item?: string;
        count?: number;
        nbt?: JsonNbt;
        sneaking?: boolean;
        playerFacing?: 'north' | 'south' | 'east' | 'west';
        dim?: string;
      },
    ) =>
      this.rpc.call<{
        success: boolean;
        action: string;
        entityType: string;
        entityUuid: string;
        entityPos: Pos;
        sneaking: boolean;
        playerFacing: string;
        eventConsumed: boolean;
        entityConsumed: boolean;
        itemConsumed: boolean;
        itemBefore: ItemStackJson;
        itemAfter: ItemStackJson;
      }>('world.interactEntity', {
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
    attackEntity: (
      entityUuid: string,
      opts?: {
        item?: string;
        count?: number;
        nbt?: JsonNbt;
        armor?: Partial<Record<'head' | 'chest' | 'legs' | 'feet', { item: string; count?: number; nbt?: JsonNbt }>>;
        playerFacing?: 'north' | 'south' | 'east' | 'west';
        dim?: string;
      },
    ) =>
      this.rpc.call<{
        success: boolean;
        entityType: string;
        entityUuid: string;
        entityPos: Pos;
        eventConsumed: boolean;
        entityHealth?: number;
        entityMaxHealth?: number;
        entityDead: boolean;
        itemBefore: ItemStackJson;
        itemAfter: ItemStackJson;
        attackDamageBefore: number;
        attackSpeedBefore: number;
        attackCooldownBefore: number;
      }>('world.attackEntity', {
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
    getNbt: (p: Pos, dim?: string) =>
      this.rpc.call<{ nbt: JsonNbt }>('be.getNbt', { pos: p, dim }),
    setNbt: (p: Pos, nbt: JsonNbt, dim?: string) =>
      this.rpc.call('be.setNbt', { pos: p, nbt, dim }),
    getField: (p: Pos, path: string, dim?: string) =>
      this.rpc.call<{ value: JsonNbt }>('be.getField', { pos: p, path, dim }),
    setField: (p: Pos, path: string, value: JsonNbt, dim?: string) =>
      this.rpc.call('be.setField', { pos: p, path, value, dim }),
    /** Tick the block entity N times (default 1) — same path as natural ticks (BlockEntityTicker). */
    tick: (p: Pos, ticks = 1, dim?: string) =>
      this.rpc.call<{ pos: Pos; dim: string; ticked: number }>('be.tick', { pos: p, ticks, dim }),
  };

  inv = {
    getSize: (p: Pos, dim?: string) =>
      this.rpc.call<{ size: number }>('inv.getSize', { pos: p, dim }),
    getSlot: (p: Pos, slot: number, dim?: string) =>
      this.rpc.call<{ slot: ItemStackJson; maxCount: number }>(
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

  storage = {
    list: (target: Target, opts?: { side?: Side }) =>
      this.rpc.call<StorageListResult>('storage.list', { target, side: opts?.side }),
    get: (target: Target, handle: string, opts?: { side?: Side }) =>
      this.rpc.call<StorageGetResult>('storage.get', { target, handle, side: opts?.side }),
    insert: (
      target: Target,
      handle: string,
      resource: StorageResource,
      amount: number,
      opts?: { side?: Side; simulate?: boolean },
    ) =>
      this.rpc.call<StorageMoveResult>('storage.insert', {
        target,
        handle,
        resource,
        amount,
        side: opts?.side,
        simulate: opts?.simulate,
      }),
    extract: (
      target: Target,
      handle: string,
      resource: StorageResource,
      amount: number,
      opts?: { side?: Side; simulate?: boolean },
    ) =>
      this.rpc.call<StorageMoveResult>('storage.extract', {
        target,
        handle,
        resource,
        amount,
        side: opts?.side,
        simulate: opts?.simulate,
      }),
    transfer: (
      from: Target,
      to: Target,
      resource: StorageResource,
      amount: number,
      opts?: { fromSide?: Side; toSide?: Side; simulate?: boolean },
    ) =>
      this.rpc.call<StorageMoveResult>('storage.transfer', {
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
    capture: (options: SnapshotCaptureOptions) =>
      this.rpc.call<JsonNbt>('snapshot.capture', options),
    diff: (before: JsonNbt, after: JsonNbt) =>
      this.rpc.call<SnapshotDiffResult>('snapshot.diff', { before, after }),
  };

  trace = {
    start: (options: TraceStartOptions) =>
      this.rpc.call<TraceStartResult>('trace.start', options),
    stop: (traceId: string) =>
      this.rpc.call<TraceResult>('trace.stop', { traceId }),
    get: (traceId: string) =>
      this.rpc.call<TraceResult>('trace.get', { traceId }),
  };

  screen = {
    openBlock: (pos: Pos, opts?: { dim?: string; player?: 'fake'; side?: Side }) =>
      this.rpc.call<ScreenSnapshot>('screen.openBlock', {
        pos,
        dim: opts?.dim,
        player: opts?.player,
        side: opts?.side,
      }),
    snapshot: (screenId: string) =>
      this.rpc.call<ScreenSnapshot>('screen.snapshot', { screenId }),
    setPlayerSlot: (screenId: string, slot: number, stack: ItemStackJson) =>
      this.rpc.call<ScreenSnapshot>('screen.setPlayerSlot', { screenId, slot, stack }),
    clickSlot: (
      screenId: string,
      slot: number,
      button: number,
      actionType: 'pickup' | 'quick_move' | 'swap' | 'clone' | 'throw' | 'quick_craft' | 'pickup_all',
    ) =>
      this.rpc.call<ScreenSnapshot>('screen.clickSlot', { screenId, slot, button, actionType }),
    quickMove: (screenId: string, slot: number) =>
      this.rpc.call<ScreenSnapshot>('screen.quickMove', { screenId, slot }),
    close: (screenId: string) =>
      this.rpc.call<{ screenId: string; closed: boolean }>('screen.close', { screenId }),
  };

  redstone = {
    getPower: (pos: Pos, opts?: { side?: Direction; dim?: string }) =>
      this.rpc.call<RedstonePowerSnapshot>('redstone.getPower', { pos, side: opts?.side, dim: opts?.dim }),
    isPowered: (pos: Pos, opts?: { dim?: string }) =>
      this.rpc.call<{ pos: Pos; dim: string; powered: boolean; received: number }>('redstone.isPowered', { pos, dim: opts?.dim }),
    setLever: (pos: Pos, powered: boolean, opts?: { dim?: string }) =>
      this.rpc.call<{ pos: Pos; dim: string; changed: boolean; powered: boolean; state: BlockSnapshot['state'] }>(
        'redstone.setLever',
        { pos, powered, dim: opts?.dim },
      ),
    pulse: (pos: Pos, ticks = 2, opts?: { dim?: string }) =>
      this.rpc.call<{ pos: Pos; dim: string; powered: boolean; offTick: number }>('redstone.pulse', {
        pos,
        ticks,
        dim: opts?.dim,
      }),
    notifyNeighbors: (pos: Pos, opts?: { dim?: string }) =>
      this.rpc.call<{ pos: Pos; dim: string; notified: boolean; state: BlockSnapshot['state'] }>('redstone.notifyNeighbors', {
        pos,
        dim: opts?.dim,
      }),
  };

  entity = {
    spawn: (type: string, pos: Pos, opts?: EntitySpawnOptions) =>
      this.rpc.call<{ spawned: boolean; entity: EntitySnapshot }>('entity.spawn', {
        type,
        pos,
        dim: opts?.dim,
        yaw: opts?.yaw,
        pitch: opts?.pitch,
        nbt: opts?.nbt,
        stack: opts?.stack,
        includeNbt: opts?.includeNbt,
      }),
    getNbt: (uuid: string, opts?: { dim?: string }) =>
      this.rpc.call<{ entity: EntitySnapshot; nbt: JsonNbt }>('entity.getNbt', { uuid, dim: opts?.dim }),
    setNbt: (uuid: string, nbt: JsonNbt, opts?: { dim?: string; replace?: boolean }) =>
      this.rpc.call<{ entity: EntitySnapshot }>('entity.setNbt', { uuid, nbt, dim: opts?.dim, replace: opts?.replace }),
    teleport: (uuid: string, pos: Pos, opts?: { dim?: string; toDim?: string; yaw?: number; pitch?: number; includeNbt?: boolean }) =>
      this.rpc.call<{ teleported: boolean; entity: EntitySnapshot }>('entity.teleport', {
        uuid,
        pos,
        dim: opts?.dim,
        toDim: opts?.toDim,
        yaw: opts?.yaw,
        pitch: opts?.pitch,
        includeNbt: opts?.includeNbt,
      }),
    remove: (uuid: string, opts?: { dim?: string; includeNbt?: boolean }) =>
      this.rpc.call<{ removed: boolean; entity: EntitySnapshot }>('entity.remove', {
        uuid,
        dim: opts?.dim,
        includeNbt: opts?.includeNbt,
      }),
    listItems: (box: Box, opts?: { dim?: string; item?: string; includeNbt?: boolean }) =>
      this.rpc.call<{ count: number; items: EntitySnapshot[] }>('entity.listItems', {
        box,
        dim: opts?.dim,
        item: opts?.item,
        includeNbt: opts?.includeNbt,
      }),
    collectItems: (box: Box, opts?: { dim?: string; item?: string; remove?: boolean; includeNbt?: boolean }) =>
      this.rpc.call<{ count: number; removed: boolean; items: EntitySnapshot[] }>('entity.collectItems', {
        box,
        dim: opts?.dim,
        item: opts?.item,
        remove: opts?.remove,
        includeNbt: opts?.includeNbt,
      }),
  };

  fixture = {
    capture: (box: Box, opts?: { dim?: string; includeNbt?: boolean }) =>
      this.rpc.call<FixtureJson>('fixture.capture', { box, dim: opts?.dim, includeNbt: opts?.includeNbt }),
    load: (fixture: FixtureJson, opts?: { origin?: Pos; dim?: string; flags?: number }) =>
      this.rpc.call<{ placed: number; blockEntities: number; dim: string; origin: Pos }>('fixture.load', {
        fixture,
        origin: opts?.origin,
        dim: opts?.dim,
        flags: opts?.flags,
      }),
  };

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
    craft: (
      grid: Array<{ item: string; count?: number; nbt?: JsonNbt } | null>,
      opts?: { recipeId?: string; dim?: string },
    ) =>
      this.rpc.call<{
        matched: true;
        recipeId: string;
        recipeType: string;
        result: ItemStackJson;
        remainder: Array<ItemStackJson>;
      } | {
        matched: false;
        candidates: string[];
      }>('craft.craft', { grid, recipeId: opts?.recipeId, dim: opts?.dim }),

    /**
     * Diagnostic: list every crafting recipe whose matches() returns true for the
     * given grid. Use this to find the right `recipeId` to pass to `craft()`.
     */
    find: (grid: Array<{ item: string; count?: number; nbt?: JsonNbt } | null>, opts?: { dim?: string }) =>
      this.rpc.call<{
        matches: Array<{ recipeId: string; recipeType: string; output: string }>;
      }>('craft.find', { grid, dim: opts?.dim }),
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
    findEntities: (box: Box, opts?: { type?: string; includeNbt?: boolean; dim?: string }) =>
      this.rpc.call<{ entities: Array<{ type: string; uuid: string; x: number; y: number; z: number; health?: number; maxHealth?: number; nbt?: JsonNbt }>; count: number }>('scan.findEntities', {
        box,
        type: opts?.type,
        includeNbt: opts?.includeNbt,
        dim: opts?.dim,
      }),
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

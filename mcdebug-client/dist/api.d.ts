import { RpcClient } from './client.js';
import { BlockSnapshot, BlockStateSpec, Box, Direction, EntitySnapshot, EntitySpawnOptions, FixtureJson, ItemStackJson, JsonNbt, Pos, RedstonePowerSnapshot, ScreenSnapshot, ServerStatus, Side, SnapshotCaptureOptions, SnapshotDiffResult, StorageGetResult, StorageListResult, StorageMoveResult, StorageResource, Target, TraceResult, TraceStartOptions, TraceStartResult, WaitResult, WorldBoxEditResult } from './types.js';
/**
 * High-level typed API on top of RpcClient.
 * Matches the methods described in C:\Users\wangyu\.claude\plans\typed-wandering-wall.md
 */
export declare class DebugApi {
    readonly rpc: RpcClient;
    constructor(rpc: RpcClient);
    world: {
        getBlock: (p: Pos, dimOrOpts?: string | {
            dim?: string;
            includeNbt?: boolean;
        }) => Promise<BlockSnapshot>;
        setBlock: (p: Pos, block: string, state?: BlockStateSpec, opts?: {
            flags?: number;
            dim?: string;
        }) => Promise<unknown>;
        setBlocks: (ops: Array<{
            pos: Pos;
            block: string;
            state?: BlockStateSpec;
        }>, opts?: {
            flags?: number;
            dim?: string;
        }) => Promise<unknown>;
        fillBox: (box: Box, block: string, state?: BlockStateSpec, opts?: {
            flags?: number;
            dim?: string;
            maxBlocks?: number;
        }) => Promise<WorldBoxEditResult>;
        clearBox: (box: Box, opts?: {
            flags?: number;
            dim?: string;
            maxBlocks?: number;
        }) => Promise<WorldBoxEditResult>;
        /**
         * Place a block as if a player were clicking the side of an adjacent block.
         * Goes through the full BlockItem / ItemPlacementContext pipeline with a
         * stable fake ServerPlayerEntity as the placer, so stairs / doors / furnaces
         * / chests, etc. derive their state from `face` + `playerFacing` (NOT from
         * defaultState). Fires sounds, game events, onPlaced, and
         * Criteria.PLACED_BLOCK. May fail if the target pos is non-replaceable.
         */
        placeAsPlayer: (pos: Pos, block: string, face: "up" | "down" | "north" | "south" | "east" | "west", opts?: {
            neighbor?: Pos;
            playerFacing?: "up" | "down" | "north" | "south" | "east" | "west";
            nbt?: JsonNbt;
            dim?: string;
        }) => Promise<{
            ok: boolean;
            pos: Pos;
            neighbor: Pos;
            face: string;
            playerFacing: string;
            placer: string;
            placerUuid: string;
            previous: {
                name: string;
                props: Record<string, string>;
            };
            state: {
                name: string;
                props: Record<string, string>;
            };
        }>;
        getRegion: (box: Box, opts?: {
            includeNbt?: boolean;
            dim?: string;
        }) => Promise<{
            blocks: Array<BlockSnapshot & {
                pos: Pos;
            }>;
        }>;
        selectBlocks: (box: Box, pred: {
            block?: string;
        }, opts?: {
            includeNbt?: boolean;
            dim?: string;
        }) => Promise<{
            matches: Array<{
                pos: Pos;
                nbt?: JsonNbt;
            }>;
        }>;
        forceloadChunk: (cx: number, cz: number, opts?: {
            dim?: string;
        }) => Promise<{
            chunk: [number, number];
            forced: boolean;
            changed: boolean;
            dim: string;
        }>;
        unforceloadChunk: (cx: number, cz: number, opts?: {
            dim?: string;
        }) => Promise<{
            chunk: [number, number];
            forced: boolean;
            changed: boolean;
            dim: string;
        }>;
        /**
         * Simulate right-clicking (using) a block with an item in hand.
         * Mirrors the full ServerPlayerInteractionManager.interactBlock pipeline:
         *   0. Fabric API UseBlockCallback (mod handlers like IC2 wrench register here)
         *   1. BlockState.onUse — block handles (lever toggle, button press, door open)
         *   2. ItemStack.useOnBlock — item handles if block returned PASS
         * Vanilla sneaking check: shift+right-click with item → skip block.onUse.
         * Uses the same stable fake player as placeAsPlayer (creative + invulnerable).
         */
        useOnBlock: (pos: Pos, face: "up" | "down" | "north" | "south" | "east" | "west", opts?: {
            item?: string;
            count?: number;
            nbt?: JsonNbt;
            sneaking?: boolean;
            playerFacing?: "north" | "south" | "east" | "west";
            gamemode?: string;
            dim?: string;
        }) => Promise<{
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
            blockState: {
                name: string;
                props: Record<string, string>;
            };
        }>;
        /**
         * Simulate right-clicking with the item in air.
         * Triggers Item.use(world, player, hand), for tools like nano saber that
         * toggle state without a block/entity target.
         */
        useItem: (item: string, opts?: {
            count?: number;
            nbt?: JsonNbt;
            sneaking?: boolean;
            dim?: string;
        }) => Promise<{
            success: boolean;
            action: string;
            sneaking: boolean;
            itemBefore: ItemStackJson;
            itemAfter: ItemStackJson;
        }>;
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
        useItemHold: (item: string, opts?: {
            count?: number;
            nbt?: JsonNbt;
            ammo?: string;
            ammoCount?: number;
            targetUuid?: string;
            direction?: "north" | "south" | "east" | "west" | "up" | "down";
            holdTicks?: number;
            repeat?: number;
            playerPos?: Pos;
            dim?: string;
        }) => Promise<{
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
        }>;
        /**
         * Simulate left-clicking (attacking) a block.
         * Mirrors the full ServerPlayerInteractionManager.processBlockBreakingAction pipeline:
         *   0. Fabric API AttackBlockCallback (mod handlers like IC2 wrench disassembly)
         *   1. Block.onBlockBreakStart, then break in creative mode
         */
        attackBlock: (pos: Pos, face: "up" | "down" | "north" | "south" | "east" | "west", opts?: {
            item?: string;
            count?: number;
            nbt?: JsonNbt;
            armor?: Partial<Record<"head" | "chest" | "legs" | "feet", {
                item: string;
                count?: number;
                nbt?: JsonNbt;
            }>>;
            gamemode?: "survival" | "creative";
            dim?: string;
        }) => Promise<{
            broken: boolean;
            eventConsumed: boolean;
            pos: Pos;
            face: string;
            itemBefore: ItemStackJson;
            itemAfter: ItemStackJson;
            blockState: {
                name: string;
                props: Record<string, string>;
            };
        }>;
        /**
         * Simulate right-clicking (using) an entity with an item in hand.
         * Mirrors PlayerEntity.interact(entity, hand):
         *   0. Fabric API UseEntityCallback
         *   1. entity.interact(player, hand)
         *   2. item.useOnEntity(player, livingEntity, hand) — e.g. bucket milks cow
         */
        interactEntity: (entityUuid: string, opts?: {
            item?: string;
            count?: number;
            nbt?: JsonNbt;
            sneaking?: boolean;
            playerFacing?: "north" | "south" | "east" | "west";
            dim?: string;
        }) => Promise<{
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
        }>;
        /**
         * Simulate left-clicking (attacking) an entity.
         * Mirrors PlayerEntity.attack(entity):
         *   0. Fabric API AttackEntityCallback
         *   1. PlayerEntity.attack(entity) — damage, knockback, sweep, etc.
         */
        attackEntity: (entityUuid: string, opts?: {
            item?: string;
            count?: number;
            nbt?: JsonNbt;
            armor?: Partial<Record<"head" | "chest" | "legs" | "feet", {
                item: string;
                count?: number;
                nbt?: JsonNbt;
            }>>;
            playerFacing?: "north" | "south" | "east" | "west";
            dim?: string;
        }) => Promise<{
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
        }>;
    };
    be: {
        getNbt: (p: Pos, dim?: string) => Promise<{
            nbt: JsonNbt;
        }>;
        setNbt: (p: Pos, nbt: JsonNbt, dim?: string) => Promise<unknown>;
        getField: (p: Pos, path: string, dim?: string) => Promise<{
            value: JsonNbt;
        }>;
        setField: (p: Pos, path: string, value: JsonNbt, dim?: string) => Promise<unknown>;
    };
    inv: {
        getSize: (p: Pos, dim?: string) => Promise<{
            size: number;
        }>;
        getSlot: (p: Pos, slot: number, dim?: string) => Promise<{
            slot: ItemStackJson;
            maxCount: number;
        }>;
        setSlot: (p: Pos, slot: number, item: string | null, count: number, nbt?: JsonNbt, dim?: string) => Promise<unknown>;
        insert: (p: Pos, item: string, count: number, opts?: {
            slot?: number;
            nbt?: JsonNbt;
            simulate?: boolean;
            dim?: string;
        }) => Promise<{
            inserted: number;
            remaining: number;
        }>;
        extract: (p: Pos, item: string, count: number, opts?: {
            slot?: number;
            simulate?: boolean;
            dim?: string;
        }) => Promise<{
            extracted: number;
            remaining: number;
        }>;
    };
    /** tick is intentionally not exposed: server drives ticks, client never advances them. */
    tick: never;
    storage: {
        list: (target: Target, opts?: {
            side?: Side;
        }) => Promise<StorageListResult>;
        get: (target: Target, handle: string, opts?: {
            side?: Side;
        }) => Promise<StorageGetResult>;
        insert: (target: Target, handle: string, resource: StorageResource, amount: number, opts?: {
            side?: Side;
            simulate?: boolean;
        }) => Promise<StorageMoveResult>;
        extract: (target: Target, handle: string, resource: StorageResource, amount: number, opts?: {
            side?: Side;
            simulate?: boolean;
        }) => Promise<StorageMoveResult>;
        transfer: (from: Target, to: Target, resource: StorageResource, amount: number, opts?: {
            fromSide?: Side;
            toSide?: Side;
            simulate?: boolean;
        }) => Promise<StorageMoveResult>;
    };
    snapshot: {
        capture: (options: SnapshotCaptureOptions) => Promise<JsonNbt>;
        diff: (before: JsonNbt, after: JsonNbt) => Promise<SnapshotDiffResult>;
    };
    trace: {
        start: (options: TraceStartOptions) => Promise<TraceStartResult>;
        stop: (traceId: string) => Promise<TraceResult>;
        get: (traceId: string) => Promise<TraceResult>;
    };
    screen: {
        openBlock: (pos: Pos, opts?: {
            dim?: string;
            player?: "fake";
            side?: Side;
        }) => Promise<ScreenSnapshot>;
        snapshot: (screenId: string) => Promise<ScreenSnapshot>;
        setPlayerSlot: (screenId: string, slot: number, stack: ItemStackJson) => Promise<ScreenSnapshot>;
        clickSlot: (screenId: string, slot: number, button: number, actionType: "pickup" | "quick_move" | "swap" | "clone" | "throw" | "quick_craft" | "pickup_all") => Promise<ScreenSnapshot>;
        quickMove: (screenId: string, slot: number) => Promise<ScreenSnapshot>;
        close: (screenId: string) => Promise<{
            screenId: string;
            closed: boolean;
        }>;
    };
    redstone: {
        getPower: (pos: Pos, opts?: {
            side?: Direction;
            dim?: string;
        }) => Promise<RedstonePowerSnapshot>;
        isPowered: (pos: Pos, opts?: {
            dim?: string;
        }) => Promise<{
            pos: Pos;
            dim: string;
            powered: boolean;
            received: number;
        }>;
        setLever: (pos: Pos, powered: boolean, opts?: {
            dim?: string;
        }) => Promise<{
            pos: Pos;
            dim: string;
            changed: boolean;
            powered: boolean;
            state: BlockSnapshot["state"];
        }>;
        pulse: (pos: Pos, ticks?: number, opts?: {
            dim?: string;
        }) => Promise<{
            pos: Pos;
            dim: string;
            powered: boolean;
            offTick: number;
        }>;
        notifyNeighbors: (pos: Pos, opts?: {
            dim?: string;
        }) => Promise<{
            pos: Pos;
            dim: string;
            notified: boolean;
            state: BlockSnapshot["state"];
        }>;
    };
    entity: {
        spawn: (type: string, pos: Pos, opts?: EntitySpawnOptions) => Promise<{
            spawned: boolean;
            entity: EntitySnapshot;
        }>;
        getNbt: (uuid: string, opts?: {
            dim?: string;
        }) => Promise<{
            entity: EntitySnapshot;
            nbt: JsonNbt;
        }>;
        setNbt: (uuid: string, nbt: JsonNbt, opts?: {
            dim?: string;
            replace?: boolean;
        }) => Promise<{
            entity: EntitySnapshot;
        }>;
        teleport: (uuid: string, pos: Pos, opts?: {
            dim?: string;
            toDim?: string;
            yaw?: number;
            pitch?: number;
            includeNbt?: boolean;
        }) => Promise<{
            teleported: boolean;
            entity: EntitySnapshot;
        }>;
        remove: (uuid: string, opts?: {
            dim?: string;
            includeNbt?: boolean;
        }) => Promise<{
            removed: boolean;
            entity: EntitySnapshot;
        }>;
        listItems: (box: Box, opts?: {
            dim?: string;
            item?: string;
            includeNbt?: boolean;
        }) => Promise<{
            count: number;
            items: EntitySnapshot[];
        }>;
        collectItems: (box: Box, opts?: {
            dim?: string;
            item?: string;
            remove?: boolean;
            includeNbt?: boolean;
        }) => Promise<{
            count: number;
            removed: boolean;
            items: EntitySnapshot[];
        }>;
    };
    fixture: {
        capture: (box: Box, opts?: {
            dim?: string;
            includeNbt?: boolean;
        }) => Promise<FixtureJson>;
        load: (fixture: FixtureJson, opts?: {
            origin?: Pos;
            dim?: string;
            flags?: number;
        }) => Promise<{
            placed: number;
            blockEntities: number;
            dim: string;
            origin: Pos;
        }>;
    };
    fluid: {
        info: (p: Pos, opts?: {
            side?: string;
            dim?: string;
        }) => Promise<{
            side: string;
            type: string;
            supportsInsertion: boolean;
            supportsExtraction: boolean;
            parts: Array<{
                fluid: string | null;
                amount: number;
                capacity: number;
            }>;
        }>;
        get: (p: Pos, opts?: {
            side?: string;
            index?: number;
            dim?: string;
        }) => Promise<{
            index: number;
            fluid: string | null;
            amount: number;
            capacity: number;
        }>;
        insert: (p: Pos, fluid: string, amount: number, opts?: {
            side?: string;
            index?: number;
            dim?: string;
        }) => Promise<{
            index: number;
            requested: number;
            inserted: number;
            remaining: number;
        }>;
        extract: (p: Pos, amount: number, opts?: {
            side?: string;
            index?: number;
            dim?: string;
        }) => Promise<{
            index: number;
            fluid: string;
            requested: number;
            extracted: number;
            remaining: number;
        }>;
    };
    wait: {
        /**
         * Passive wait for a condition to become true. Does NOT advance server ticks.
         * Implementation: registers a ServerTickEvents.END_SERVER_TICK callback on the server
         * that evaluates the predicate on each natural tick.
         */
        until: (predicate: string, opts?: {
            timeoutTicks?: number;
            pollIntervalTicks?: number;
        }) => Promise<WaitResult>;
    };
    craft: {
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
        craft: (grid: Array<{
            item: string;
            count?: number;
            nbt?: JsonNbt;
        } | null>, opts?: {
            recipeId?: string;
            dim?: string;
        }) => Promise<{
            matched: true;
            recipeId: string;
            recipeType: string;
            result: ItemStackJson;
            remainder: Array<ItemStackJson>;
        } | {
            matched: false;
            candidates: string[];
        }>;
        /**
         * Diagnostic: list every crafting recipe whose matches() returns true for the
         * given grid. Use this to find the right `recipeId` to pass to `craft()`.
         */
        find: (grid: Array<{
            item: string;
            count?: number;
            nbt?: JsonNbt;
        } | null>, opts?: {
            dim?: string;
        }) => Promise<{
            matches: Array<{
                recipeId: string;
                recipeType: string;
                output: string;
            }>;
        }>;
    };
    scan: {
        findBlocks: (box: Box, block: string, opts?: {
            count?: boolean;
            dim?: string;
        }) => Promise<{
            positions: Pos[];
            count?: number;
        }>;
        countByBlock: (box: Box, dim?: string) => Promise<{
            counts: Record<string, number>;
        }>;
        findEntities: (box: Box, opts?: {
            type?: string;
            includeNbt?: boolean;
            dim?: string;
        }) => Promise<{
            entities: Array<{
                type: string;
                uuid: string;
                x: number;
                y: number;
                z: number;
                health?: number;
                maxHealth?: number;
                nbt?: JsonNbt;
            }>;
            count: number;
        }>;
    };
    server: {
        status: () => Promise<ServerStatus>;
        listDimensions: () => Promise<{
            dims: string[];
        }>;
        runCommand: (command: string, opts?: {
            dim?: string;
        }) => Promise<{
            success: boolean;
            result: number;
            output: string;
        }>;
    };
    close(): Promise<void>;
}

package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.util.NbtJson
import com.mcdebug.util.ServerContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import com.google.common.collect.LinkedHashMultimap
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.core.Holder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import java.util.UUID
import java.util.concurrent.CompletableFuture
import com.mcdebug.loader.Loader

/**
 * `world.*` handlers that act as a (fake) player: place, use items, break/attack blocks,
 * interact with and attack entities. Registered by [WorldOps]; split out because the
 * player plumbing (armor, attribute modifiers, reach, use ticks) is most of the code.
 */
internal object WorldPlayerActions {

    private val LOGGER: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("mcdebug-worldops")

    /**
     * Place a block as if a player were clicking the side of an adjacent block.
     *
     * Goes through the full BlockItem / ItemPlacementContext pipeline so directional
     * blocks (stairs, doors, furnaces, ...) derive their state from the supplied
     * face / playerFacing instead of returning defaultState. Fires sounds, game
     * events, onPlaced, and Criteria.PLACED_BLOCK. Can fail (canPlace()=false)
     * if the target pos is non-replaceable.
     *
     * The placer is a stable fake ServerPlayer (UUID 8c0a4d6e-..., name
     * "[mcdebug_fake_player]"), spawned into the world and shared across all calls
     * and all dims, so mods that record "placed by" see a consistent identity.
     * Game mode defaults to SURVIVAL (the placed item is consumed from the hand);
     * pass `gamemode=creative` to place without consuming. Advancement / stat
     * triggers fire but on the fake player's tracker (not a real player's), so
     * they have no observable effect.
     *
     * Semantics of `playerFacing`: it is the direction the player is LOOKING when
     * they click — NOT the direction you want the placed block to face. Vanilla
     * blocks consume this differently:
     *   - stairs / doors / beds / banners:    block.facing = playerFacing
     *   - furnace / chest / dispenser / etc.:  block.facing = playerFacing.opposite
     *   - glass / stone / other non-directional: ignored
     * So to place a furnace whose `facing` should be `south`, pass
     * `playerFacing=north` (player looks north, furnace faces the opposite).
     *
     * Params:
     *   pos            [x,y,z] required   placement target
     *   block          string  required   block id (e.g. "minecraft:furnace")
     *   face           string  required   which side of `neighbor` was clicked:
     *                                    one of up|down|north|south|east|west
     *   neighbor       [x,y,z] optional   block that was clicked; default = pos - face
     *   playerFacing   string  optional   which way the player was looking when they
     *                                    clicked; default = face.opposite if
     *                                    horizontal, else north
     *   nbt            object  optional   ItemStack NBT (BlockEntityTag for chest,
     *                                    BlockStateTag for forcing state, etc.)
     *   gamemode       string  optional   survival (default) or creative
     *   dim            string  optional
     */
    fun placeAsPlayer(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val blockId = p.requireString("block")
            val face = p.requireDirection("face")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))

            val neighbor: BlockPos = p.posOrNull("neighbor")
                ?: pos.relative(face.opposite)

            val playerFacing: Direction = p.getDirectionOrNull("playerFacing")
                ?: face.opposite.let { if (it.axis == Direction.Axis.Y) Direction.NORTH else it }

            val block = ServerContext.blockById(server, blockId)
            val blockItem = block.asItem() as? BlockItem
                ?: throw RpcException(
                    RpcErrors.INVALID_PARAMS,
                    "block $blockId has no BlockItem (cannot use placeAsPlayer; try setBlock)"
                )

            val stack = ItemStack(blockItem, 1)
            val nbtEl = p.get("nbt")
            if (nbtEl != null && !nbtEl.isJsonNull) {
                ServerContext.stackSetNbt(stack, NbtJson.fromJson(nbtEl) as? CompoundTag)
            }

            val gamemode = FakePlayerPool.gameModeFrom(p)
            var ok = false
            var itemAfter = ItemStack.EMPTY
            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                // Auto-position: keep the current spot if it can reach the click and does
                // not obstruct the placement target; otherwise teleport out of the way.
                positionForPlacement(
                    fakePlayer, pos, neighbor, face,
                    yawFor(playerFacing), pitchFor(playerFacing),
                )

                // Click point: center of the neighbor face that was hit, offset by face*0.5
                // (the EXACT world point the raycast would have struck).
                val hitPos = Vec3(
                    neighbor.x + 0.5 + face.stepX * 0.5,
                    neighbor.y + 0.5 + face.stepY * 0.5,
                    neighbor.z + 0.5 + face.stepZ * 0.5
                )
                val hitResult = BlockHitResult(hitPos, face, neighbor, false)
                fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, stack.copy())

                // Real path: the full right-click pipeline
                // (ServerPlayerInteractionManager.interactBlock) — the exact method a real
                // player's click goes through: UseBlockCallback (fabric mixin at HEAD) →
                // BlockState.onUse on the clicked neighbor → ItemStack.useOnBlock →
                // BlockItem.place, plus cooldown check and Criteria.ITEM_USED_ON_BLOCK.
                // Note: if the neighbor block consumes the click itself (button, lever,
                // chest, ...), the placement is skipped — exactly like a real player.
                val before = world.getBlockState(pos)
                val result: InteractionResult = fakePlayer.gameMode.useItemOn(
                    fakePlayer, world, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hitResult
                )
                val after = world.getBlockState(pos)
                ok = result.consumesAction()
                itemAfter = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND)

                JsonObject().apply {
                    add("pos", ServerContext.posAsJson(pos))
                    add("neighbor", ServerContext.posAsJson(neighbor))
                    addProperty("ok", ok)
                    addProperty("face", face.getName())
                    addProperty("playerFacing", playerFacing.getName())
                    addProperty("placer", fakePlayer.name.string)
                    addProperty("placerUuid", fakePlayer.getStringUUID())
                    addProperty("gamemode", gamemode.name.lowercase())
                    add("itemAfter", ServerContext.itemStackToJson(itemAfter))
                    add("previous", ServerContext.blockStateToJson(before))
                    add("state", ServerContext.blockStateToJson(after))
                }
            }
        }

    /**
     * Simulate right-clicking with the held item without a block/entity target.
     *
     * This triggers Item.use(world, player, hand), which is needed for tools that
     * toggle state on air use, such as IC2's nano saber.
     */
    fun useItem(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val itemId = p.requireString("item")
            val count = p.getIntOr("count", 1)
            val itemBefore = ServerContext.itemStackFromJson(server, itemId, count, p.get("nbt"))

            val sneaking = p.getBoolOrFalse("sneaking")
            val gamemode = FakePlayerPool.gameModeFrom(p)

            var result: InteractionResult = InteractionResult.PASS
            var itemAfter = ItemStack.EMPTY

            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                fakePlayer.isShiftKeyDown = sneaking
                fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, itemBefore.copy())

                // Real path: ServerPlayerInteractionManager.interactItem — the exact
                // method a real right-click (use item) goes through: UseItemCallback
                // (fabric mixin at HEAD), cooldown check, ItemStack.use, creative
                // count/damage restore, inventory sync.
                result = fakePlayer.gameMode.useItem(
                    fakePlayer, world, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND
                )
                itemAfter = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND).copy()

                fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                fakePlayer.isShiftKeyDown = false
                fakePlayer.isSprinting = false
            }

            JsonObject().apply {
                addProperty("success", result.consumesAction())
                addProperty("action", result.name.lowercase())
                addProperty("sneaking", sneaking)
                addProperty("gamemode", gamemode.name.lowercase())
                add("itemBefore", ServerContext.itemStackToJson(itemBefore))
                add("itemAfter", ServerContext.itemStackToJson(itemAfter))
            }
        }

    /**
     * Simulate right-clicking with the held item and holding it for a number of
     * ticks before releasing — the full vanilla item-use lifecycle used by bows,
     * crossbows, tridents and any modded ranged weapon that follows the vanilla
     * protocol (use → usageTick × N → onStoppedUsing).
     *
     * Mirrors what a real client does on the server side:
     *   1. ServerPlayerInteractionManager.interactItem — the "right-click press"
     *      (UseItemCallback, cooldown check, creative count restore). A ranged weapon
     *      either starts using (setCurrentHand) or fires directly (IC2 mining
     *      laser, TConstruct shuriken/throwing axe).
     *   2. For weapons that entered the using state: usageTick is invoked once per
     *      held tick (crossbow loading, bow draw) — driven here by reflecting
     *      LivingEntity.tickActiveItemStack, the exact same method the server
     *      calls from LivingEntity.tick() every tick.
     *   3. Player.stopUsingItem() — the "release". This calls
     *      Item.onStoppedUsing (bow fires, crossbow finishes loading).
     *
     * Projectiles are real entities spawned into the world; they fly on normal
     * server ticks and can be observed with find-entities / wait-until.
     *
     * Params:
     *   item          string  required   item id of the ranged weapon
     *   count         int     optional   stack size (default 1)
     *   nbt           object  optional   item NBT (tool data, charge, mode, ...)
     *   ammo          string  optional   ammo item id to place in the offhand /
     *                                    inventory (bow arrows, crossbow bolts).
     *                                    Omit for weapons that need no ammo.
     *   ammoCount     int     optional   ammo stack size (default 64)
     *   targetUuid    string  optional   UUID of the entity to aim at. The fake
     *                                    player looks at the target's eyes, then
     *                                    yaw/pitch feed the weapon's shot vector.
     *   direction     string  optional   fixed facing (north/south/east/west/up/
     *                                    down) when no target is given.
     *   holdTicks     int     optional   ticks to hold right-click before release
     *                                    (0 = short tap). Default 20.
     *   repeat        int     optional   full use cycles (crossbows need 2:
     *                                    load, then fire). Default 1.
     *   playerPos     [x,y,z] optional   fake player position; default 5 blocks
     *                                    in front of the target (or world spawn
     *                                    when no target).
     *   dim           string  optional
     *
     * Returns:
     *   cycles        array of per-cycle results: action (use/stopped), result,
     *                 usingItem (true if the weapon entered the using state),
     *                 itemAfter
     *   projectiles   array of entities spawned by this call (type, uuid, pos,
     *                 velocity) — diffed against the world entity list
     *   ammo          ammo stack as seen by the weapon (empty if none)
     */
    fun useItemHold(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val itemId = p.requireString("item")
            val count = p.getIntOr("count", 1)
            val itemBefore = ServerContext.itemStackFromJson(server, itemId, count, p.get("nbt"))
            val holdTicks = p.getIntOr("holdTicks", 20)
            val repeat = p.getIntOr("repeat", 1)
            val ammoCount = p.getIntOr("ammoCount", 64)
            val targetUuid = p.getStringOrNull("targetUuid")
            val direction = p.getDirectionOrNull("direction")
            val playerPos = p.get("playerPos")?.takeIf { it.isJsonArray }?.let { ServerContext.pos(it.getAsJsonArray()) }

            var target: Entity? = null
            if (targetUuid != null) {
                target = try {
                    world.getEntity(UUID.fromString(targetUuid))
                } catch (e: IllegalArgumentException) {
                    null
                } ?: throw RpcException(RpcErrors.INVALID_PARAMS, "target entity not found: $targetUuid")
            }
            if (target == null && direction == null) {
                throw RpcException(RpcErrors.INVALID_PARAMS, "one of targetUuid or direction is required")
            }

            // Snapshot projectiles before the call so we can diff afterwards.
            val worldBox = net.minecraft.world.phys.AABB(
                -30_000_000.0, -30_000_000.0, -30_000_000.0,
                30_000_000.0, 30_000_000.0, 30_000_000.0
            )
            val projFilter: (net.minecraft.world.entity.Entity) -> Boolean = { it is net.minecraft.world.entity.projectile.Projectile }
            val projectilesBefore = world.getEntities(null, worldBox, projFilter).map { it.uuid }.toSet()

            val gamemode = FakePlayerPool.gameModeFrom(p)
            val cycles = JsonArray()
            var ammoStack = ItemStack.EMPTY

            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                val oldMainHand = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND)
                val oldOffHand = fakePlayer.getItemInHand(InteractionHand.OFF_HAND)

                try {
                    // Main hand = hotbar selectedSlot; pin it to 0 so the held
                    // weapon is always written to a known slot (and ammo in slot 8
                    // can never collide with it).
                    fakePlayer.inventory.selected = 0
                    fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, itemBefore.copy())
                    primeStackForGameplay(world, fakePlayer, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND), 0, true)

                    // Ammo goes in the offhand (vanilla getProjectileType searches
                    // main hand, then offhand, then inventory) and also a copy in
                    // hotbar slot 8 for mods that only scan the inventory (e.g.
                    // TConstruct BowAmmoModifierHook). NOTE: hotbar slot 0 is the
                    // main hand (selectedSlot defaults to 0) — never write ammo
                    // there or it overwrites the held weapon.
                    p.getStringOrNull("ammo")?.let { ammoId ->
                        val ammo = ServerContext.itemStackFromJson(server, ammoId, ammoCount, null)
                        fakePlayer.setItemInHand(InteractionHand.OFF_HAND, ammo.copy())
                        fakePlayer.inventory.setItem(8, ammo.copy())
                        ammoStack = ammo.copy()
                    }

                    // Position + aim. With a target we look at its eyes so the
                    // weapon's shot vector (derived from yaw/pitch) points at it.
                    if (target != null) {
                        val eye = target.getEyePosition()
                        if (playerPos != null) {
                            fakePlayer.moveTo(
                                playerPos.x + 0.5, playerPos.y.toDouble(), playerPos.z + 0.5,
                                fakePlayer.yRot, fakePlayer.xRot
                            )
                        } else {
                            // Default: 5 blocks in front of the target, at its eye level.
                            val toPlayer = fakePlayer.position().subtract(eye).normalize()
                            val stand = eye.add(toPlayer.scale(5.0))
                            fakePlayer.moveTo(stand.x, stand.y, stand.z, fakePlayer.yRot, fakePlayer.xRot)
                        }
                        fakePlayer.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, eye)
                    } else {
                        val dir = direction!!
                        val yaw = when (dir) {
                            Direction.NORTH -> 180f
                            Direction.SOUTH -> 0f
                            Direction.EAST -> -90f
                            Direction.WEST -> 90f
                            Direction.UP, Direction.DOWN -> fakePlayer.yRot
                        }
                        val pitch = when (dir) {
                            Direction.UP -> -90f
                            Direction.DOWN -> 90f
                            else -> 0f
                        }
                        if (playerPos != null) {
                            fakePlayer.moveTo(
                                playerPos.x + 0.5, playerPos.y.toDouble(), playerPos.z + 0.5, yaw, pitch
                            )
                        } else {
                            val spawn = world.getSharedSpawnPos()
                            fakePlayer.moveTo(spawn.x + 0.5, spawn.y + 1.0, spawn.z + 0.5, yaw, pitch)
                        }
                    }
                    fakePlayer.setOnGround(true)
                    fakePlayer.fallDistance = 0f
                    fakePlayer.setDeltaMovement(0.0, 0.0, 0.0)
                    fakePlayer.isSprinting = false

                    // Drive the use cycles.
                    var held = itemBefore.copy()
                    for (cycle in 0 until repeat) {
                        val cycleJson = JsonObject()
                        // 1. Right-click press — through the real interaction-manager
                        //    path (UseItemCallback, cooldown check, creative count restore).
                        val useResult = fakePlayer.gameMode.useItem(
                            fakePlayer, world, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND
                        )
                        held = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND)
                        cycleJson.addProperty("cycle", cycle)
                        cycleJson.addProperty("useResult", useResult.name.lowercase())
                        cycleJson.addProperty("usingItem", fakePlayer.isUsingItem())
                        cycleJson.addProperty("mainHand", fakePlayer.getItemInHand(InteractionHand.MAIN_HAND).item.toString())
                        cycleJson.addProperty("offHand", fakePlayer.getItemInHand(InteractionHand.OFF_HAND).item.toString())
                        cycleJson.addProperty("projectileType", fakePlayer.getProjectile(fakePlayer.getItemInHand(InteractionHand.MAIN_HAND)).item.toString())

                        // 2. Hold: drive usageTick once per held tick. Reflecting
                        //    LivingEntity.tickActiveItemStack mirrors the exact
                        //    method the server calls from LivingEntity.tick().
                        if (fakePlayer.isUsingItem() && holdTicks > 0) {
                            for (t in 0 until holdTicks) {
                                invokeTickActiveItemStack(fakePlayer)
                            }
                            // 3. Release: onStoppedUsing (bow fires, crossbow loads).
                            fakePlayer.stopUsingItem()
                            cycleJson.addProperty("stopped", true)
                        } else {
                            cycleJson.addProperty("stopped", false)
                        }
                        cycleJson.addProperty("stillUsingAfterRelease", fakePlayer.isUsingItem())
                        cycleJson.addProperty("useTimeLeft", fakePlayer.useItemRemainingTicks)
                        cycleJson.addProperty("tickMethodFound", TICK_ACTIVE_ITEM_STACK_METHOD != null)
                        cycleJson.add("itemAfter", ServerContext.itemStackToJson(held))
                        cycles.add(cycleJson)

                        // Crossbow: after loading, the next use() call sees
                        // isCharged and fires. If the item was consumed or the
                        // stack changed, pick up the new stack for the next cycle.
                        if (held.isEmpty) break
                    }

                    // Collect projectiles spawned by this call.
                    val projectiles = JsonArray()
                    world.getEntities(null, worldBox, projFilter)
                        .filter { it.uuid !in projectilesBefore }
                        .forEach { proj ->
                            val obj = JsonObject()
                            obj.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(proj.type).toString())
                            obj.addProperty("uuid", proj.getStringUUID())
                            obj.add("pos", JsonArray().apply {
                                add(proj.x); add(proj.y); add(proj.z)
                            })
                            obj.add("velocity", JsonArray().apply {
                                add(proj.deltaMovement.x); add(proj.deltaMovement.y); add(proj.deltaMovement.z)
                            })
                            projectiles.add(obj)
                        }

                    // Cleanup: restore hand stacks and using state.
                    if (fakePlayer.isUsingItem()) {
                        fakePlayer.stopUsingItem()
                    }
                    fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, oldMainHand)
                    fakePlayer.setItemInHand(InteractionHand.OFF_HAND, oldOffHand)
                    fakePlayer.inventory.setItem(8, ItemStack.EMPTY)
                    fakePlayer.isShiftKeyDown = false

                    JsonObject().apply {
                        add("cycles", cycles)
                        add("projectiles", projectiles)
                        add("ammo", ServerContext.itemStackToJson(ammoStack))
                        add("itemBefore", ServerContext.itemStackToJson(itemBefore))
                        addProperty("gamemode", gamemode.name.lowercase())
                    }
                } catch (e: Throwable) {
                    // Best-effort cleanup so the pooled fake player stays sane.
                    runCatching {
                        if (fakePlayer.isUsingItem()) fakePlayer.stopUsingItem()
                        fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, oldMainHand)
                        fakePlayer.setItemInHand(InteractionHand.OFF_HAND, oldOffHand)
                        fakePlayer.inventory.setItem(8, ItemStack.EMPTY)
                        fakePlayer.isShiftKeyDown = false
                    }
                    throw e
                }
            }
        }

    /**
     * Simulate right-clicking (using) a block with an item in hand.
     *
     * Routes through the real ServerPlayerInteractionManager.interactBlock — the exact
     * method a real player's right-click goes through. The pipeline is:
     *   0. UseBlockCallback (fabric mixin at HEAD of interactBlock) — mod handlers
     *      (wrench, etc.) register here. If non-PASS, the interaction is consumed and
     *      vanilla phases are skipped.
     *   1. BlockState.onUse(world, player, hand, hit) — the block handles the interaction
     *      (e.g. lever toggles, button presses, door opens, chest/furnace opens GUI).
     *      Skipped if the player is sneaking with an item in hand (vanilla sneaking rule).
     *   2. If the block returns PASS, ItemStack.useOnBlock(context) — the item handles it
     *      (e.g. bucket picks up water, flint-and-steel ignites, fluid cell injects fluid).
     *
     * The fake player is positioned just outside the target face. Its facing direction
     * is controlled by the `playerFacing` param (default: looking at the clicked face).
     * Game mode defaults to SURVIVAL (stacks are consumed / containers drain properly);
     * pass `gamemode=creative` to preserve stacks.
     *
     * Params:
     *   pos            [x,y,z] required   block being right-clicked
     *   face           string  required   which face to click: up|down|north|south|east|west
     *   item           string  optional   item id to hold (default: empty hand)
     *   count          int     optional   stack size (default 1)
     *   nbt            object  optional   item NBT (e.g. {"Energy": 30000} for batteries)
     *   sneaking       bool    optional   player.isShiftKeyDown (default false)
     *   playerFacing   string  optional   which direction the player is facing (one of
     *                                    north|south|east|west). Controls player.horizontalFacing
     *                                    which mods read for rotation. Default: face.opposite
     *                                    (player looks at the clicked face).
     *   gamemode       string  optional   survival (default) or creative
     *   dim            string  optional
     *
     * Returns:
     *   success         boolean  true if any handler consumed the interaction
     *   action          string   "success" | "fail" | "pass"
     *   eventConsumed   boolean  the UseBlockCallback consumed the click with no vanilla
     *                             side effect (e.g. a protection mod denying). Heuristic:
     *                             non-PASS result with item/state/screen unchanged.
     *   blockConsumed   boolean  BlockState.onUse handled it (lever, chest, ...) or the
     *                             block state changed. Heuristic, see code.
     *   itemConsumed    boolean  ItemStack.useOnBlock handled it and changed the stack.
     *   sneaking        boolean  the effective sneaking state used
     *   playerFacing    string   the effective player facing direction used
     *   gamemode        string   the resolved game mode
     *   itemBefore      object   item stack before interaction
     *   itemAfter       object   item stack after interaction
     *   blockState      object   block state AFTER the interaction
     */
    fun useOnBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val face = p.requireDirection("face")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))

            // Parse held item (optional — empty hand if omitted)
            val itemBefore: ItemStack = if (p.has("item") && !p.get("item").isJsonNull) {
                val itemId = p.requireString("item")
                val count = p.getIntOr("count", 1)
                val nbtEl = p.get("nbt")
                ServerContext.itemStackFromJson(server, itemId, count, nbtEl)
            } else {
                ItemStack.EMPTY
            }

            // Set up fake player with the item in main hand
            val sneaking = p.getBoolOrFalse("sneaking")
            // Interaction game mode: default survival (held item is consumed / containers
            // are properly drained); "creative" preserves stacks (vanilla creative rules).
            val gamemode = FakePlayerPool.gameModeFrom(p)

            // Player facing: explicit override or default to looking at the clicked face
            val playerFacing = p.getDirectionOrNull("playerFacing")
                ?: face.opposite.let { if (it.axis == Direction.Axis.Y) Direction.NORTH else it }

            lateinit var eventResult: InteractionResult
            lateinit var blockResult: InteractionResult
            lateinit var itemResult: InteractionResult
            var itemAfter: ItemStack = ItemStack.EMPTY
            var creativeModeSnapshot: Boolean? = null

            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, itemBefore.copy())
                fakePlayer.isShiftKeyDown = sneaking

                // Auto-position: keep the current spot if it can reach the click and does
                // not obstruct the block this interaction may place (pos + face);
                // otherwise teleport to the far side of the clicked block. yaw follows
                // playerFacing so player.horizontalFacing matches it.
                positionForPlacement(
                    fakePlayer, pos.relative(face), pos, face,
                    yawFor(playerFacing), pitchFor(face.opposite),
                )

                // Hit point: center of the target face
                val hitPos = Vec3(
                    pos.x + 0.5 + face.stepX * 0.5,
                    pos.y + 0.5 + face.stepY * 0.5,
                    pos.z + 0.5 + face.stepZ * 0.5
                )
                val hitResult = BlockHitResult(hitPos, face, pos, false)

                val stateBefore = world.getBlockState(pos)
                val itemBeforeSnapshot = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND).copy()

                // Real path: ServerPlayerInteractionManager.interactBlock — the exact method
                // a real right-click goes through: UseBlockCallback (fabric mixin at HEAD)
                // → BlockState.onUse (with the vanilla sneaking rule) → ItemStack.useOnBlock,
                // plus the cooldown check and Criteria.ITEM_USED_ON_BLOCK.
                val result = fakePlayer.gameMode.useItemOn(
                    fakePlayer, world, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hitResult
                )

                val stateAfter = world.getBlockState(pos)
                itemAfter = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND).copy()
                creativeModeSnapshot = fakePlayer.abilities.instabuild

                // The phase breakdown is inferred from observable side effects (the vanilla
                // method does not expose per-phase results):
                //   - eventConsumed: the callback consumed the click with no vanilla side
                //     effect (e.g. a protection mod denying → FAIL, nothing changed).
                //   - itemConsumed: the held stack changed (bucket pickup, survival placement...).
                //   - blockConsumed: block.onUse handled it (lever, chest...) or the block
                //     state changed (creative placement).
                val itemChanged = !ItemStack.matches(itemBeforeSnapshot, itemAfter)
                val stateChanged = stateBefore != stateAfter
                val screenOpened = fakePlayer.containerMenu !== fakePlayer.inventoryMenu
                if (screenOpened) {
                    // block.onUse opened a container GUI — close it so the shared fake
                    // player does not keep a stale open screen between calls.
                    fakePlayer.closeContainer()
                }

                eventResult = if (result != InteractionResult.PASS && !itemChanged && !stateChanged && !screenOpened) {
                    result
                } else {
                    InteractionResult.PASS
                }
                blockResult = if (result.consumesAction() && !itemChanged && eventResult == InteractionResult.PASS) result else InteractionResult.PASS
                itemResult = if (result.consumesAction() && itemChanged) result else InteractionResult.PASS

                // Clean up: clear hand and reset sneaking (mode/abilities restored by the pool).
                fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                fakePlayer.isShiftKeyDown = false
            }

            val success = eventResult.consumesAction() || blockResult.consumesAction() || itemResult.consumesAction()
            val consumed = when {
                eventResult.consumesAction() || eventResult == InteractionResult.FAIL -> eventResult
                blockResult.consumesAction() -> blockResult
                itemResult.consumesAction() -> itemResult
                else -> InteractionResult.PASS
            }

            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("success", success)
                addProperty("action", consumed.name.lowercase())
                addProperty("face", face.getName())
                addProperty("sneaking", sneaking)
                addProperty("playerFacing", playerFacing.getName())
                addProperty("gamemode", gamemode.name.lowercase())
                addProperty("creativeMode", creativeModeSnapshot ?: false)
                addProperty("eventConsumed", eventResult.consumesAction() || eventResult == InteractionResult.FAIL)
                addProperty("blockConsumed", blockResult.consumesAction())
                addProperty("itemConsumed", itemResult.consumesAction())
                add("itemBefore", ServerContext.itemStackToJson(itemBefore))
                add("itemAfter", ServerContext.itemStackToJson(itemAfter))
                add("blockState", ServerContext.blockStateToJson(world.getBlockState(pos)))
            }
        }

    /**
     * Simulate left-clicking (attacking) a block — the full real mining gesture.
     *
     * Routes through ServerPlayerInteractionManager.processBlockBreakingAction with
     * the exact sequence a real player sends: START_DESTROY_BLOCK → (hold: the same
     * interactionManager.update() the server tick runs, fast-forwarded until the
     * vanilla >= 70% stop-check passes) → STOP_DESTROY_BLOCK. AttackBlockCallback
     * fires automatically (fabric mixin at HEAD of processBlockBreakingAction); a
     * consuming handler cancels everything.
     *
     * Semantics:
     *   - creative: the block breaks instantly (vanilla creative destroy).
     *   - survival: the block breaks when the stop-check passes; the held tool is
     *     used for mining speed and the final tryBreakBlock loot/damage.
     *   - unbreakable blocks (bedrock, barrier): nothing happens; the block is kept.
     *
     * Params:
     *   pos            [x,y,z] required   block being left-clicked
     *   face           string  required   which face was hit
     *   item           string  optional   item id to hold (default: empty hand)
     *   count          int     optional   stack size (default 1)
     *   nbt            object  optional   item NBT
     *   armor          object  optional   armor stacks keyed by head/chest/legs/feet;
     *                                    each value is {item,count,nbt}
     *   gamemode       string  optional   survival (default) or creative
     *   dim            string  optional
     *
     * Returns:
     *   broken           boolean  true if the block was broken
     *   eventConsumed    boolean  true if a Fabric API AttackBlockCallback handler
     *                              consumed the interaction (e.g. IC2 wrench disassembly)
     *                              — inferred: not broken and block state unchanged
     *   pos, face, gamemode, itemBefore, itemAfter, blockState
     */
    /**
     * Simulate a player breaking a block through the real server-side break
     * pipeline (ServerPlayerInteractionManager.tryBreakBlock).
     *
     * Unlike `attackBlock` (which simulates the mining gesture START→hold→STOP), this
     * breaks the block immediately through the vanilla tryBreakBlock path: canMine
     * check, creative instant break / survival drops + tool postMine, break events.
     * A cancelled break (protection mods) returns broken=false and the block is kept.
     *
     * Params (same shape as attackBlock):
     *   pos            [x,y,z] required
     *   face           string  required   which face was hit (used for aim/placement)
     *   item           string  optional   item id to hold (default: empty hand)
     *   count          int     optional
     *   nbt            object  optional
     *   armor          object  optional
     *   gamemode       string  optional   survival or creative (default survival)
     *   dim            string  optional
     *
     * Returns:
     *   broken           boolean  true if the block was actually broken
     *   pos, face, itemBefore, itemAfter, blockState
     */
    fun breakBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val face = p.requireDirection("face")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val gamemode = FakePlayerPool.gameModeFrom(p)

            val itemBefore: ItemStack = if (p.has("item") && !p.get("item").isJsonNull) {
                val itemId = p.requireString("item")
                val count = p.getIntOr("count", 1)
                val nbtEl = p.get("nbt")
                ServerContext.itemStackFromJson(server, itemId, count, nbtEl)
            } else {
                ItemStack.EMPTY
            }

            var broken = false
            var itemAfter = ItemStack.EMPTY

            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                val oldArmor = installArmor(server, fakePlayer, p)

                try {
                    fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, itemBefore.copy())
                    primeStackForGameplay(world, fakePlayer, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND), 0, true)

                    val lookDir = face.opposite
                    fakePlayer.moveTo(
                        pos.x + 0.5 + face.stepX * 1.0,
                        pos.y + 0.5 + face.stepY * 1.0,
                        pos.z + 0.5 + face.stepZ * 1.0,
                        yawFor(lookDir), pitchFor(lookDir)
                    )

                    // Real path: ServerPlayerInteractionManager.tryBreakBlock — the full
                    // server-side break pipeline (canMine check, creative instant break /
                    // survival drops + tool postMine, break events).
                    if (!world.getBlockState(pos).isAir) {
                        broken = fakePlayer.gameMode.destroyBlock(pos)
                    }

                    itemAfter = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND).copy()
                } finally {
                    fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                    restoreArmor(fakePlayer, oldArmor)
                    fakePlayer.isShiftKeyDown = false
                }
            }

            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("face", face.getName())
                addProperty("broken", broken)
                addProperty("gamemode", gamemode.name.lowercase())
                add("itemBefore", ServerContext.itemStackToJson(itemBefore))
                add("itemAfter", ServerContext.itemStackToJson(itemAfter))
                add("blockState", ServerContext.blockStateToJson(world.getBlockState(pos)))
            }
        }

    fun attackBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val face = p.requireDirection("face")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))

            val itemBefore: ItemStack = if (p.has("item") && !p.get("item").isJsonNull) {
                val itemId = p.requireString("item")
                val count = p.getIntOr("count", 1)
                val nbtEl = p.get("nbt")
                ServerContext.itemStackFromJson(server, itemId, count, nbtEl)
            } else {
                ItemStack.EMPTY
            }

            var broken = false
            var eventConsumed = false
            var itemAfter = ItemStack.EMPTY
            val gamemode = FakePlayerPool.gameModeFrom(p)

            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                val oldArmor = installArmor(server, fakePlayer, p)

                try {
                    fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, itemBefore.copy())
                    primeStackForGameplay(world, fakePlayer, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND), 0, true)

                    val lookDir = face.opposite
                    fakePlayer.moveTo(
                        pos.x + 0.5 + face.stepX * 1.0,
                        pos.y + 0.5 + face.stepY * 1.0,
                        pos.z + 0.5 + face.stepZ * 1.0,
                        yawFor(lookDir), pitchFor(lookDir)
                    )

                    val stateBefore = world.getBlockState(pos)
                    if (!stateBefore.isAir) {
                        val im = fakePlayer.gameMode
                        // Real mining gesture: START → hold (fast-forwarded through the same
                        // interactionManager.update() the server tick runs) → STOP.
                        // AttackBlockCallback fires automatically (fabric mixin at HEAD of
                        // processBlockBreakingAction); a consuming handler cancels everything.
                        im.handleBlockBreakAction(
                            pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, face, world.maxBuildHeight, 0
                        )
                        if (!world.getBlockState(pos).isAir) {
                            // Survival: mine until the vanilla stop-check (>= 70% progress) passes.
                            val delta = stateBefore.getDestroyProgress(fakePlayer, world, pos)
                            val holdTicks = if (delta > 0f) {
                                ceil(0.7 / delta - 1.0).toInt().coerceAtLeast(0)
                            } else 0
                            repeat(holdTicks) { im.tick() }
                            im.handleBlockBreakAction(
                                pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, face, world.maxBuildHeight, 0
                            )
                        }
                    }

                    val stateAfter = world.getBlockState(pos)
                    broken = !stateBefore.isAir && stateAfter.isAir
                    // A consuming AttackBlockCallback cancels the whole method, leaving the
                    // block untouched (same observable as a refused break).
                    eventConsumed = !broken && stateAfter == stateBefore
                    itemAfter = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND).copy()
                } finally {
                    fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                    restoreArmor(fakePlayer, oldArmor)
                    fakePlayer.isShiftKeyDown = false
                }
            }

            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("face", face.getName())
                addProperty("broken", broken)
                addProperty("eventConsumed", eventConsumed)
                addProperty("gamemode", gamemode.name.lowercase())
                add("itemBefore", ServerContext.itemStackToJson(itemBefore))
                add("itemAfter", ServerContext.itemStackToJson(itemAfter))
                add("blockState", ServerContext.blockStateToJson(world.getBlockState(pos)))
            }
        }

    /**
     * Simulate right-clicking (using) an entity with an item in hand.
     *
     * Routes through the real Player.interact(entity, hand) — the method a real
     * player's right-click on an entity goes through:
     *   0. UseEntityCallback — fired here manually at the same position the fabric
     *      mixin fires it (HEAD of the packet handler's interact, before the real call).
     *   1. entity.interact(player, hand) — entity handles (villager opens trade,
     *      sheep sets color, ...)
     *   2. If the entity returns PASS and is a LivingEntity: itemStack.useOnEntity —
     *      the item handles (bucket milks cow, shears shear sheep, food feeds, ...)
     *
     * Params:
     *   entityUuid    string  required   UUID of the target entity
     *   item          string  optional   item id to hold (default: empty hand)
     *   count          int     optional   stack size (default 1)
     *   nbt            object  optional   item NBT
     *   sneaking       bool    optional   player.isShiftKeyDown (default false)
     *   playerFacing   string  optional   which direction the player faces (for
     *                                    events that read player.horizontalFacing)
     *   dim            string  optional
     *
     * Returns:
     *   success         boolean
     *   action          string
     *   eventConsumed   boolean  the loader's use-entity event consumed it
     *   entityConsumed  boolean  entity.interact consumed
     *   itemConsumed    boolean  item.useOnEntity consumed
     *   entityType      string   type of the target entity
     *   itemBefore, itemAfter
     */
    fun interactEntity(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))

            // Find entity by UUID
            val uuidStr = p.requireString("entityUuid")
            val uuid: UUID = try {
                UUID.fromString(uuidStr)
            } catch (e: IllegalArgumentException) {
                throw RpcException(RpcErrors.INVALID_PARAMS, "invalid entity UUID: $uuidStr")
            }
            val entity = world.getEntity(uuid)
                ?: throw RpcException(RpcErrors.INVALID_PARAMS, "entity not found: $uuid")

            // Parse held item
            val itemBefore: ItemStack = if (p.has("item") && !p.get("item").isJsonNull) {
                val itemId = p.requireString("item")
                val count = p.getIntOr("count", 1)
                val nbtEl = p.get("nbt")
                ServerContext.itemStackFromJson(server, itemId, count, nbtEl)
            } else {
                ItemStack.EMPTY
            }

            val sneaking = p.getBoolOrFalse("sneaking")

            val playerFacing = p.getDirectionOrNull("playerFacing") ?: Direction.SOUTH
            val gamemode = FakePlayerPool.gameModeFrom(p)
            var eventResult: InteractionResult = InteractionResult.PASS
            var entityResult: InteractionResult = InteractionResult.PASS
            var itemResult: InteractionResult = InteractionResult.PASS
            var itemAfter = ItemStack.EMPTY

            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, itemBefore.copy())
                fakePlayer.isShiftKeyDown = sneaking

                // Position player near the entity, looking at it
                val eyePos = entity.getEyePosition()
                fakePlayer.moveTo(
                    eyePos.x, eyePos.y, eyePos.z,
                    yawFor(playerFacing), 0f
                )

                // Hit result pointing at entity center
                val hitResult = EntityHitResult(entity, eyePos)
                val itemBeforeSnapshot = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND).copy()

                // The loader's "player uses entity" event is fired by the loader's own
                // packet handler (Fabric: a mixin on ServerGamePacketListenerImpl;
                // NeoForge: PlayerInteractEvent.EntityInteract), not by Player.interact —
                // fire it here at the same position (before the real interact call).
                eventResult = Loader.api.fireUseEntity(
                    fakePlayer, world, InteractionHand.MAIN_HAND, entity, hitResult
                )

                if (eventResult.consumesAction() || eventResult == InteractionResult.FAIL) {
                    entityResult = InteractionResult.PASS
                    itemResult = InteractionResult.PASS
                } else {
                    // Real path: Player.interact — the method a real right-click on
                    // an entity goes through: entity.interact(hand), then for living
                    // entities ItemStack.useOnEntity, creative count restore, GameEvent.
                    val result = fakePlayer.interactOn(entity, InteractionHand.MAIN_HAND)
                    val itemChanged = !ItemStack.matches(
                        itemBeforeSnapshot, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND)
                    )
                    entityResult = if (result.consumesAction() && !itemChanged) result else InteractionResult.PASS
                    itemResult = if (result.consumesAction() && itemChanged) result else InteractionResult.PASS
                }

                itemAfter = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND).copy()

                fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                fakePlayer.isShiftKeyDown = false
                fakePlayer.isSprinting = false
            }

            val success = eventResult.consumesAction() || entityResult.consumesAction() || itemResult.consumesAction()
            val consumed = when {
                eventResult.consumesAction() || eventResult == InteractionResult.FAIL -> eventResult
                entityResult.consumesAction() -> entityResult
                itemResult.consumesAction() -> itemResult
                else -> InteractionResult.PASS
            }

            JsonObject().apply {
                addProperty("success", success)
                addProperty("action", consumed.name.lowercase())
                addProperty("entityType", BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString())
                addProperty("entityUuid", entity.getStringUUID())
                addProperty("sneaking", sneaking)
                addProperty("playerFacing", playerFacing.getName())
                addProperty("gamemode", gamemode.name.lowercase())
                addProperty("eventConsumed", eventResult.consumesAction() || eventResult == InteractionResult.FAIL)
                addProperty("entityConsumed", entityResult.consumesAction())
                addProperty("itemConsumed", itemResult.consumesAction())
                add("entityPos", JsonArray().apply {
                    add(entity.position().x); add(entity.position().y); add(entity.position().z)
                })
                add("itemBefore", ServerContext.itemStackToJson(itemBefore))
                add("itemAfter", ServerContext.itemStackToJson(itemAfter))
            }
        }

    /**
     * Simulate left-clicking (attacking) an entity.
     *
     * Mirrors the server-side entity attack pipeline:
     *   0. AttackEntityCallback.EVENT.invoker().interact(player, world, hand, entity, hitResult)
     *      — Fabric API event. If non-PASS, skip vanilla attack.
     *   1. Player.attack(entity) — vanilla attack (damage, knockback, sweep, etc.)
     *
     * Params:
     *   entityUuid    string  required   UUID of the target entity
     *   item          string  optional   item id to hold (default: empty hand)
     *   count          int     optional   stack size (default 1)
     *   nbt            object  optional   item NBT
     *   armor          object  optional   armor stacks keyed by head/chest/legs/feet;
     *                                    each value is {item,count,nbt}
     *   playerFacing   string  optional   player facing direction
     *   dim            string  optional
     *
     * Returns:
     *   success         boolean
     *   eventConsumed   boolean  Fabric AttackEntityCallback consumed
     *   entityType, entityUuid, entityPos
     *   entityHealth    number   entity health after attack
     *   itemBefore, itemAfter
     */
    fun attackEntity(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))

            val uuidStr = p.requireString("entityUuid")
            val uuid: UUID = try {
                UUID.fromString(uuidStr)
            } catch (e: IllegalArgumentException) {
                throw RpcException(RpcErrors.INVALID_PARAMS, "invalid entity UUID: $uuidStr")
            }
            val entity = world.getEntity(uuid)
                ?: throw RpcException(RpcErrors.INVALID_PARAMS, "entity not found: $uuid")

            val itemBefore: ItemStack = if (p.has("item") && !p.get("item").isJsonNull) {
                val itemId = p.requireString("item")
                val count = p.getIntOr("count", 1)
                val nbtEl = p.get("nbt")
                ServerContext.itemStackFromJson(server, itemId, count, nbtEl)
            } else {
                ItemStack.EMPTY
            }

            var eventConsumed = false
            var itemAfter = ItemStack.EMPTY
            var attackDamageBefore = 0.0
            var attackSpeedBefore = 0.0
            var attackCooldownBefore = 0f
            val gamemode = FakePlayerPool.gameModeFrom(p)

            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                val oldArmor = installArmor(server, fakePlayer, p)

                try {
                    fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, itemBefore.copy())
                    primeStackForGameplay(world, fakePlayer, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND), 0, true)
                    addMainHandModifiers(fakePlayer, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND))
                    primeAttackCooldown(fakePlayer)

                    val playerFacing = p.getDirectionOrNull("playerFacing") ?: Direction.SOUTH
                    val eyePos = entity.getEyePosition()
                    val playerOffset = Vec3.atLowerCornerOf(playerFacing.opposite.normal).scale(1.5)
                    val playerPos = entity.position().add(playerOffset)
                    fakePlayer.moveTo(
                        playerPos.x, entity.y, playerPos.z,
                        yawFor(playerFacing), 0f
                    )
                    fakePlayer.setOnGround(true)
                    fakePlayer.fallDistance = 0f
                    fakePlayer.setDeltaMovement(0.0, 0.0, 0.0)
                    fakePlayer.isSprinting = false

                    attackDamageBefore = fakePlayer.getAttributeValue(Attributes.ATTACK_DAMAGE)
                    attackSpeedBefore = fakePlayer.getAttributeValue(Attributes.ATTACK_SPEED)
                    attackCooldownBefore = fakePlayer.getAttackStrengthScale(0.5f)

                    // Real path: ServerPlayer.attack. AttackEntityCallback fires
                    // automatically (fabric mixin at HEAD of this method) — a consuming
                    // handler cancels the whole attack. Detection: a real attack resets
                    // the attack cooldown (resetLastAttackedTicks), a canceled one leaves
                    // it primed.
                    fakePlayer.attack(entity)
                    eventConsumed = fakePlayer.getAttackStrengthScale(0.5f) >= attackCooldownBefore - 0.05f

                    itemAfter = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND).copy()
                } finally {
                    removeMainHandModifiers(fakePlayer, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND))
                    fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                    restoreArmor(fakePlayer, oldArmor)
                    fakePlayer.isShiftKeyDown = false
                    fakePlayer.isSprinting = false
                }
            }

            JsonObject().apply {
                addProperty("success", true)
                addProperty("entityType", BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString())
                addProperty("entityUuid", entity.getStringUUID())
                addProperty("eventConsumed", eventConsumed)
                addProperty("gamemode", gamemode.name.lowercase())
                add("entityPos", JsonArray().apply {
                    add(entity.position().x); add(entity.position().y); add(entity.position().z)
                })
                if (entity is LivingEntity) {
                    addProperty("entityHealth", entity.health)
                    addProperty("entityMaxHealth", entity.maxHealth)
                    addProperty("entityDead", !entity.isAlive)
                } else {
                    addProperty("entityHealth", -1)
                    addProperty("entityMaxHealth", -1)
                    addProperty("entityDead", entity.isRemoved)
                }
                add("itemBefore", ServerContext.itemStackToJson(itemBefore))
                add("itemAfter", ServerContext.itemStackToJson(itemAfter))
                addProperty("attackDamageBefore", attackDamageBefore)
                addProperty("attackSpeedBefore", attackSpeedBefore)
                addProperty("attackCooldownBefore", attackCooldownBefore)
            }
        }

    /**
     * Install temporary armor on the shared fake player. The JSON order follows the
     * equipment slot names rather than PlayerInventory's internal feet-first order.
     * This is intentionally per-call so one test cannot leak equipped armor into
     * another.
     */
    private fun installArmor(
        server: MinecraftServer,
        player: ServerPlayer,
        params: JsonObject,
    ): Map<EquipmentSlot, ItemStack> {
        val element = params.get("armor") ?: return emptyMap()
        if (!element.isJsonObject) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "armor must be an object keyed by head/chest/legs/feet")
        }
        val slots = linkedMapOf(
            "head" to EquipmentSlot.HEAD,
            "chest" to EquipmentSlot.CHEST,
            "legs" to EquipmentSlot.LEGS,
            "feet" to EquipmentSlot.FEET,
        )
        val old = slots.values.associateWith { player.getItemBySlot(it).copy() }
        val armor = element.asJsonObject
        for ((name, slot) in slots) {
            val stackElement = armor.get(name) ?: continue
            if (!stackElement.isJsonObject) {
                throw RpcException(RpcErrors.INVALID_PARAMS, "armor.$name must be an item object")
            }
            val stackObject = stackElement.asJsonObject
            val item = stackObject.getStringOrNull("item") ?: "minecraft:air"
            val count = stackObject.getIntOr("count", 1)
            val stack = ServerContext.itemStackFromJson(server, item, count, stackObject.get("nbt"))
            player.setItemSlot(slot, stack)
            primeStackForGameplay(player.level() as ServerLevel, player, stack, slot.getIndex(), false)
        }
        return old
    }

    private fun primeStackForGameplay(
        world: ServerLevel,
        player: ServerPlayer,
        stack: ItemStack,
        slot: Int,
        selected: Boolean,
    ) {
        if (!stack.isEmpty) {
            // A real player ticks held and worn stacks before interacting. This generic
            // one-shot tick initializes lazy item state without hard-coding any mod
            // API into mcdebug.
            stack.inventoryTick(world, player, slot, selected)
        }
    }

    private fun restoreArmor(player: ServerPlayer, old: Map<EquipmentSlot, ItemStack>) {
        old.forEach { (slot, stack) -> player.setItemSlot(slot, stack) }
    }

    //1.21: ItemStack#getAttributeModifiers(EquipmentSlot) is gone; the attribute component lives on the item.
    // `Item#getDefaultAttributeModifiers` is @Deprecated in 1.21.1 but is still what vanilla's own
    // ItemStack#forEachModifier uses, so keep the identical source of modifiers on purpose.
    @Suppress("DEPRECATION")
    private fun mainHandModifierMultimap(stack: ItemStack): com.google.common.collect.Multimap<Holder<Attribute>, AttributeModifier> {
        val multimap = LinkedHashMultimap.create<Holder<Attribute>, AttributeModifier>()
        stack.item.getDefaultAttributeModifiers().forEach(EquipmentSlot.MAINHAND) { attribute, modifier ->
            multimap.put(attribute, modifier)
        }
        return multimap
    }

    private fun addMainHandModifiers(player: ServerPlayer, stack: ItemStack) {
        if (!stack.isEmpty) {
            player.getAttributes().addTransientAttributeModifiers(mainHandModifierMultimap(stack))
        }
    }

    private fun removeMainHandModifiers(player: ServerPlayer, stack: ItemStack) {
        if (!stack.isEmpty) {
            player.getAttributes().removeAttributeModifiers(mainHandModifierMultimap(stack))
        }
    }

    private fun primeAttackCooldown(player: ServerPlayer) {
        LAST_ATTACKED_TICKS_FIELD.setInt(player, ceil(player.getCurrentItemAttackStrengthDelay()).toInt())
    }

    private val LAST_ATTACKED_TICKS_FIELD by lazy {
        listOf("lastAttackedTicks", "field_6273", "aQ").firstNotNullOfOrNull { name ->
            findDeclaredField(Player::class.java, name)
        } ?: error("Unable to resolve Player lastAttackedTicks field")
    }

    private fun findDeclaredField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            val field = runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()
            if (field != null) return field
            current = current.superclass
        }
        return null
    }

    /**
     * Invoke LivingEntity.tickActiveItemStack() — the private method the server
     * calls from LivingEntity.tick() every tick while an entity is using an item.
     * It runs Item.usageTick, decrements the remaining use time, and consumes the
     * item when the use duration elapses (unless the item is used on release).
     */
    private fun invokeTickActiveItemStack(entity: LivingEntity) {
        if (!entity.isUsingItem()) return
        val method = TICK_ACTIVE_ITEM_STACK_METHOD ?: return
        try {
            method.invoke(entity)
        } catch (e: Exception) {
            LOGGER.warn("tickActiveItemStack invoke failed: {}", e.toString())
        }
    }

    private val TICK_ACTIVE_ITEM_STACK_METHOD: java.lang.reflect.Method? by lazy {
        // LivingEntity.updatingUsingItem() is protected: intermediary name in production,
        // Mojang name in a Loom dev run.
        findDeclaredMethod(LivingEntity::class.java, "method_6076", "updatingUsingItem")
    }

    private fun findDeclaredMethod(type: Class<*>, vararg names: String): java.lang.reflect.Method? {
        var current: Class<*>? = type
        while (current != null) {
            for (name in names) {
                val method = runCatching {
                    current.getDeclaredMethod(name).apply { isAccessible = true }
                }.getOrNull()
                if (method != null) return method
            }
            current = current.superclass
        }
        return null
    }

    /** that, when passed
     * to Entity.setYaw, makes getYaw() → getEntityFacingOrder produce the same
     * direction. Matches vanilla: south=0, west=90, north=180, east=-90.
     * UP/DOWN map to a horizontal look (south) since getPlayerLookDirection()
     * for the Y axis uses getPitch, not yaw.
     */
    private fun yawFor(facing: Direction): Float = when (facing) {
        Direction.SOUTH -> 0f
        Direction.WEST -> 90f
        Direction.NORTH -> 180f
        Direction.EAST -> -90f
        Direction.UP, Direction.DOWN -> 0f
    }

    /**
     * Convert a vertical Direction to a pitch (degrees). UP = looking up = -90,
     * DOWN = looking down = +90. Horizontal directions stay level (pitch=0).
     */
    private fun pitchFor(facing: Direction): Float = when (facing) {
        Direction.UP -> -90f
        Direction.DOWN -> 90f
        else -> 0f
    }

    /**
     * Position the fake player for a placement-style interaction (placeAsPlayer,
     * or useOnBlock with a block item in hand).
     *
     * Rules:
     *   - If the player's current position already has the click point within
     *     reach (4.5 blocks survival / 5.0 creative, measured from the eye) AND
     *     its bounding box does not intersect the block being placed, stay put
     *     (no teleport — keeps position continuity across calls).
     *   - Otherwise teleport to the far side of the clicked block
     *     (`clickedPos + face.opposite * 1.5`, feet at the block center): for
     *     every face this spot is within reach and never overlaps the placement
     *     target. This is required because the spawned fake player is a real
     *     entity — standing inside the target block makes vanilla's canPlace
     *     entity-collision check reject the placement.
     */
    private fun positionForPlacement(
        fakePlayer: ServerPlayer,
        targetPos: BlockPos,
        clickedPos: BlockPos,
        face: Direction,
        yaw: Float,
        pitch: Float,
    ) {
        val clickPoint = Vec3(
            clickedPos.x + 0.5 + face.stepX * 0.5,
            clickedPos.y + 0.5 + face.stepY * 0.5,
            clickedPos.z + 0.5 + face.stepZ * 0.5,
        )
        val targetBox = net.minecraft.world.phys.AABB(
            targetPos.x.toDouble(), targetPos.y.toDouble(), targetPos.z.toDouble(),
            targetPos.x + 1.0, targetPos.y + 1.0, targetPos.z + 1.0,
        )
        val reach = if (fakePlayer.abilities.instabuild) 5.0 else 4.5
        val inReach = fakePlayer.getEyePosition().distanceToSqr(clickPoint) <= reach * reach
        val obstructs = fakePlayer.boundingBox.intersects(targetBox)
        if (inReach && !obstructs) return

        val stand = Vec3(
            clickedPos.x + 0.5 + face.opposite.stepX * 1.5,
            clickedPos.y + 0.5 + face.opposite.stepY * 1.5,
            clickedPos.z + 0.5 + face.opposite.stepZ * 1.5,
        )
        fakePlayer.moveTo(stand.x, stand.y, stand.z, yaw, pitch)
    }
}

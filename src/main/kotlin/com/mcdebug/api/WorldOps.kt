package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.NbtJson
import com.mcdebug.util.ServerContext
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.entity.Entity
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.BlockItem
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.math.ceil
import java.util.UUID
import java.util.concurrent.CompletableFuture

object WorldOps : RpcHandlerGroup {

    private val LOGGER: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("mcdebug-worldops")
    private const val DEFAULT_FLAGS = Block.NOTIFY_LISTENERS or Block.NOTIFY_NEIGHBORS or Block.REDRAW_ON_MAIN_THREAD

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "getBlock" to ::getBlock,
        "setBlock" to ::setBlock,
        "setBlocks" to ::setBlocks,
        "placeAsPlayer" to ::placeAsPlayer,
        "useItem" to ::useItem,
        "useItemHold" to ::useItemHold,
        "useOnBlock" to ::useOnBlock,
        "attackBlock" to ::attackBlock,
        "breakBlock" to ::breakBlock,
        "interactEntity" to ::interactEntity,
        "attackEntity" to ::attackEntity,
        "getRegion" to ::getRegion,
        "selectBlocks" to ::selectBlocks,
        "fillBox" to ::fillBox,
        "clearBox" to ::clearBox,
        "forceloadChunk" to ::forceloadChunk,
        "unforceloadChunk" to ::unforceloadChunk
    )

    private fun getBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val state = world.getBlockState(pos)
            val hasBe = world.getBlockEntity(pos) != null
            val includeNbt = p.getBoolOrFalse("includeNbt")
            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("dim", world.registryKey.value.toString())
                add("state", ServerContext.blockStateToJson(state))
                addProperty("hasBlockEntity", hasBe)
                if (includeNbt && hasBe) {
                    val nbt = world.getBlockEntity(pos)!!.createNbt()
                    add("nbt", NbtJson.toJson(nbt))
                }
            }
        }

    private fun setBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
            val blockId = p.requireString("block")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val stateProps = p.getStringMapOrNull("stateProps")
            val flags = p.getIntOr("flags", DEFAULT_FLAGS)
            val state = ServerContext.blockState(server, blockId, stateProps)
            val previous = world.getBlockState(pos)
            val ok = world.setBlockState(pos, state, flags)
            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("ok", ok)
                add("previous", ServerContext.blockStateToJson(previous))
            }
        }

    private fun setBlocks(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val ops = p.getAsJsonArray("ops")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val flags = p.getIntOr("flags", DEFAULT_FLAGS)
            var count = 0
            ops.forEach { el ->
                val op = el.asJsonObject
                val pos = ServerContext.pos(op.getAsJsonArray("pos"))
                val block = ServerContext.blockState(server, op.requireString("block"), op.getStringMapOrNull("stateProps"))
                if (world.setBlockState(pos, block, flags)) count++
            }
            JsonObject().apply { addProperty("count", count) }
        }

    private fun fillBox(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            fillBoxNow(server, p, p.requireString("block"), p.getStringMapOrNull("stateProps"))
        }

    private fun clearBox(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            fillBoxNow(server, p, "minecraft:air", null)
        }

    /**
     * Place a block as if a player were clicking the side of an adjacent block.
     *
     * Goes through the full BlockItem / ItemPlacementContext pipeline so directional
     * blocks (stairs, doors, furnaces, ...) derive their state from the supplied
     * face / playerFacing instead of returning defaultState. Fires sounds, game
     * events, onPlaced, and Criteria.PLACED_BLOCK. Can fail (canPlace()=false)
     * if the target pos is non-replaceable.
     *
     * The placer is a stable fake ServerPlayerEntity (UUID 8c0a4d6e-..., name
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
    private fun placeAsPlayer(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
            val blockId = p.requireString("block")
            val face = p.requireDirection("face")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))

            val neighbor: BlockPos = p.getAsJsonArray("neighbor")?.let(ServerContext::pos)
                ?: pos.offset(face.opposite)

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
                stack.nbt = NbtJson.fromJson(nbtEl) as? NbtCompound
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
                val hitPos = Vec3d(
                    neighbor.x + 0.5 + face.offsetX * 0.5,
                    neighbor.y + 0.5 + face.offsetY * 0.5,
                    neighbor.z + 0.5 + face.offsetZ * 0.5
                )
                val hitResult = BlockHitResult(hitPos, face, neighbor, false)
                fakePlayer.setStackInHand(Hand.MAIN_HAND, stack.copy())

                // Real path: the full right-click pipeline
                // (ServerPlayerInteractionManager.interactBlock) — the exact method a real
                // player's click goes through: UseBlockCallback (fabric mixin at HEAD) →
                // BlockState.onUse on the clicked neighbor → ItemStack.useOnBlock →
                // BlockItem.place, plus cooldown check and Criteria.ITEM_USED_ON_BLOCK.
                // Note: if the neighbor block consumes the click itself (button, lever,
                // chest, ...), the placement is skipped — exactly like a real player.
                val before = world.getBlockState(pos)
                val result: ActionResult = fakePlayer.interactionManager.interactBlock(
                    fakePlayer, world, fakePlayer.getStackInHand(Hand.MAIN_HAND), Hand.MAIN_HAND, hitResult
                )
                val after = world.getBlockState(pos)
                ok = result.isAccepted
                itemAfter = fakePlayer.getStackInHand(Hand.MAIN_HAND)

                JsonObject().apply {
                    add("pos", ServerContext.posAsJson(pos))
                    add("neighbor", ServerContext.posAsJson(neighbor))
                    addProperty("ok", ok)
                    addProperty("face", face.asString())
                    addProperty("playerFacing", playerFacing.asString())
                    addProperty("placer", fakePlayer.name.string)
                    addProperty("placerUuid", fakePlayer.uuidAsString)
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
    private fun useItem(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val itemId = p.requireString("item")
            val count = p.getIntOr("count", 1)
            val itemBefore = ServerContext.itemStackFromJson(server, itemId, count, p.get("nbt"))

            val sneaking = p.getBoolOrFalse("sneaking")
            val gamemode = FakePlayerPool.gameModeFrom(p)

            var result: ActionResult = ActionResult.PASS
            var itemAfter = ItemStack.EMPTY

            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                fakePlayer.isSneaking = sneaking
                fakePlayer.setStackInHand(Hand.MAIN_HAND, itemBefore.copy())

                // Real path: ServerPlayerInteractionManager.interactItem — the exact
                // method a real right-click (use item) goes through: UseItemCallback
                // (fabric mixin at HEAD), cooldown check, ItemStack.use, creative
                // count/damage restore, inventory sync.
                result = fakePlayer.interactionManager.interactItem(
                    fakePlayer, world, fakePlayer.getStackInHand(Hand.MAIN_HAND), Hand.MAIN_HAND
                )
                itemAfter = fakePlayer.getStackInHand(Hand.MAIN_HAND).copy()

                fakePlayer.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY)
                fakePlayer.isSneaking = false
                fakePlayer.isSprinting = false
            }

            JsonObject().apply {
                addProperty("success", result.isAccepted)
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
     *   3. PlayerEntity.stopUsingItem() — the "release". This calls
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
    private fun useItemHold(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
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
            val worldBox = net.minecraft.util.math.Box(
                -30_000_000.0, -30_000_000.0, -30_000_000.0,
                30_000_000.0, 30_000_000.0, 30_000_000.0
            )
            val projFilter: (net.minecraft.entity.Entity) -> Boolean = { it is net.minecraft.entity.projectile.ProjectileEntity }
            val projectilesBefore = world.getOtherEntities(null, worldBox, projFilter).map { it.uuid }.toSet()

            val gamemode = FakePlayerPool.gameModeFrom(p)
            val cycles = JsonArray()
            var ammoStack = ItemStack.EMPTY

            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                val oldMainHand = fakePlayer.getStackInHand(Hand.MAIN_HAND)
                val oldOffHand = fakePlayer.getStackInHand(Hand.OFF_HAND)

                try {
                    // Main hand = hotbar selectedSlot; pin it to 0 so the held
                    // weapon is always written to a known slot (and ammo in slot 8
                    // can never collide with it).
                    fakePlayer.inventory.selectedSlot = 0
                    fakePlayer.setStackInHand(Hand.MAIN_HAND, itemBefore.copy())
                    primeStackForGameplay(world, fakePlayer, fakePlayer.getStackInHand(Hand.MAIN_HAND), 0, true)

                    // Ammo goes in the offhand (vanilla getProjectileType searches
                    // main hand, then offhand, then inventory) and also a copy in
                    // hotbar slot 8 for mods that only scan the inventory (e.g.
                    // TConstruct BowAmmoModifierHook). NOTE: hotbar slot 0 is the
                    // main hand (selectedSlot defaults to 0) — never write ammo
                    // there or it overwrites the held weapon.
                    p.getStringOrNull("ammo")?.let { ammoId ->
                        val ammo = ServerContext.itemStackFromJson(server, ammoId, ammoCount, null)
                        fakePlayer.setStackInHand(Hand.OFF_HAND, ammo.copy())
                        fakePlayer.inventory.setStack(8, ammo.copy())
                        ammoStack = ammo.copy()
                    }

                    // Position + aim. With a target we look at its eyes so the
                    // weapon's shot vector (derived from yaw/pitch) points at it.
                    if (target != null) {
                        val eye = target!!.getEyePos()
                        if (playerPos != null) {
                            fakePlayer.refreshPositionAndAngles(
                                playerPos.x + 0.5, playerPos.y.toDouble(), playerPos.z + 0.5,
                                fakePlayer.yaw, fakePlayer.pitch
                            )
                        } else {
                            // Default: 5 blocks in front of the target, at its eye level.
                            val toPlayer = fakePlayer.pos.subtract(eye).normalize()
                            val stand = eye.add(toPlayer.multiply(5.0))
                            fakePlayer.refreshPositionAndAngles(stand.x, stand.y, stand.z, fakePlayer.yaw, fakePlayer.pitch)
                        }
                        fakePlayer.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, eye)
                    } else {
                        val dir = direction!!
                        val yaw = when (dir) {
                            Direction.NORTH -> 180f
                            Direction.SOUTH -> 0f
                            Direction.EAST -> -90f
                            Direction.WEST -> 90f
                            Direction.UP, Direction.DOWN -> fakePlayer.yaw
                        }
                        val pitch = when (dir) {
                            Direction.UP -> -90f
                            Direction.DOWN -> 90f
                            else -> 0f
                        }
                        if (playerPos != null) {
                            fakePlayer.refreshPositionAndAngles(
                                playerPos.x + 0.5, playerPos.y.toDouble(), playerPos.z + 0.5, yaw, pitch
                            )
                        } else {
                            val spawn = world.getSpawnPos()
                            fakePlayer.refreshPositionAndAngles(spawn.x + 0.5, spawn.y + 1.0, spawn.z + 0.5, yaw, pitch)
                        }
                    }
                    fakePlayer.setOnGround(true)
                    fakePlayer.fallDistance = 0f
                    fakePlayer.setVelocity(0.0, 0.0, 0.0)
                    fakePlayer.isSprinting = false

                    // Drive the use cycles.
                    var held = itemBefore.copy()
                    for (cycle in 0 until repeat) {
                        val cycleJson = JsonObject()
                        // 1. Right-click press — through the real interaction-manager
                        //    path (UseItemCallback, cooldown check, creative count restore).
                        val useResult = fakePlayer.interactionManager.interactItem(
                            fakePlayer, world, fakePlayer.getStackInHand(Hand.MAIN_HAND), Hand.MAIN_HAND
                        )
                        held = fakePlayer.getStackInHand(Hand.MAIN_HAND)
                        cycleJson.addProperty("cycle", cycle)
                        cycleJson.addProperty("useResult", useResult.name.lowercase())
                        cycleJson.addProperty("usingItem", fakePlayer.isUsingItem())
                        cycleJson.addProperty("mainHand", fakePlayer.getStackInHand(Hand.MAIN_HAND).item.toString())
                        cycleJson.addProperty("offHand", fakePlayer.getStackInHand(Hand.OFF_HAND).item.toString())
                        cycleJson.addProperty("projectileType", fakePlayer.getProjectileType(fakePlayer.getStackInHand(Hand.MAIN_HAND)).item.toString())

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
                        cycleJson.addProperty("useTimeLeft", fakePlayer.getItemUseTimeLeft())
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
                    world.getOtherEntities(null, worldBox, projFilter)
                        .filter { it.uuid !in projectilesBefore }
                        .forEach { proj ->
                            val obj = JsonObject()
                            obj.addProperty("type", Registries.ENTITY_TYPE.getId(proj.type).toString())
                            obj.addProperty("uuid", proj.uuidAsString)
                            obj.add("pos", JsonArray().apply {
                                add(proj.x); add(proj.y); add(proj.z)
                            })
                            obj.add("velocity", JsonArray().apply {
                                add(proj.velocity.x); add(proj.velocity.y); add(proj.velocity.z)
                            })
                            projectiles.add(obj)
                        }

                    // Cleanup: restore hand stacks and using state.
                    if (fakePlayer.isUsingItem()) {
                        fakePlayer.stopUsingItem()
                    }
                    fakePlayer.setStackInHand(Hand.MAIN_HAND, oldMainHand)
                    fakePlayer.setStackInHand(Hand.OFF_HAND, oldOffHand)
                    fakePlayer.inventory.setStack(8, ItemStack.EMPTY)
                    fakePlayer.isSneaking = false

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
                        fakePlayer.setStackInHand(Hand.MAIN_HAND, oldMainHand)
                        fakePlayer.setStackInHand(Hand.OFF_HAND, oldOffHand)
                        fakePlayer.inventory.setStack(8, ItemStack.EMPTY)
                        fakePlayer.isSneaking = false
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
     *   sneaking       bool    optional   player.isSneaking (default false)
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
    private fun useOnBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
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

            lateinit var eventResult: ActionResult
            lateinit var blockResult: ActionResult
            lateinit var itemResult: ActionResult
            var itemAfter: ItemStack = ItemStack.EMPTY
            var creativeModeSnapshot: Boolean? = null

            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                fakePlayer.setStackInHand(Hand.MAIN_HAND, itemBefore.copy())
                fakePlayer.isSneaking = sneaking

                // Auto-position: keep the current spot if it can reach the click and does
                // not obstruct the block this interaction may place (pos + face);
                // otherwise teleport to the far side of the clicked block. yaw follows
                // playerFacing so player.horizontalFacing matches it.
                positionForPlacement(
                    fakePlayer, pos.offset(face), pos, face,
                    yawFor(playerFacing), pitchFor(face.opposite),
                )

                // Hit point: center of the target face
                val hitPos = Vec3d(
                    pos.x + 0.5 + face.offsetX * 0.5,
                    pos.y + 0.5 + face.offsetY * 0.5,
                    pos.z + 0.5 + face.offsetZ * 0.5
                )
                val hitResult = BlockHitResult(hitPos, face, pos, false)

                val stateBefore = world.getBlockState(pos)
                val itemBeforeSnapshot = fakePlayer.getStackInHand(Hand.MAIN_HAND).copy()

                // Real path: ServerPlayerInteractionManager.interactBlock — the exact method
                // a real right-click goes through: UseBlockCallback (fabric mixin at HEAD)
                // → BlockState.onUse (with the vanilla sneaking rule) → ItemStack.useOnBlock,
                // plus the cooldown check and Criteria.ITEM_USED_ON_BLOCK.
                val result = fakePlayer.interactionManager.interactBlock(
                    fakePlayer, world, fakePlayer.getStackInHand(Hand.MAIN_HAND), Hand.MAIN_HAND, hitResult
                )

                val stateAfter = world.getBlockState(pos)
                itemAfter = fakePlayer.getStackInHand(Hand.MAIN_HAND).copy()
                creativeModeSnapshot = fakePlayer.abilities.creativeMode

                // The phase breakdown is inferred from observable side effects (the vanilla
                // method does not expose per-phase results):
                //   - eventConsumed: the callback consumed the click with no vanilla side
                //     effect (e.g. a protection mod denying → FAIL, nothing changed).
                //   - itemConsumed: the held stack changed (bucket pickup, survival placement...).
                //   - blockConsumed: block.onUse handled it (lever, chest...) or the block
                //     state changed (creative placement).
                val itemChanged = !ItemStack.areEqual(itemBeforeSnapshot, itemAfter)
                val stateChanged = stateBefore != stateAfter
                val screenOpened = fakePlayer.currentScreenHandler !== fakePlayer.playerScreenHandler
                if (screenOpened) {
                    // block.onUse opened a container GUI — close it so the shared fake
                    // player does not keep a stale open screen between calls.
                    fakePlayer.closeHandledScreen()
                }

                eventResult = if (result != ActionResult.PASS && !itemChanged && !stateChanged && !screenOpened) {
                    result
                } else {
                    ActionResult.PASS
                }
                blockResult = if (result.isAccepted && !itemChanged && eventResult == ActionResult.PASS) result else ActionResult.PASS
                itemResult = if (result.isAccepted && itemChanged) result else ActionResult.PASS

                // Clean up: clear hand and reset sneaking (mode/abilities restored by the pool).
                fakePlayer.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY)
                fakePlayer.isSneaking = false
            }

            val success = eventResult.isAccepted || blockResult.isAccepted || itemResult.isAccepted
            val consumed = when {
                eventResult.isAccepted || eventResult == ActionResult.FAIL -> eventResult
                blockResult.isAccepted -> blockResult
                itemResult.isAccepted -> itemResult
                else -> ActionResult.PASS
            }

            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("success", success)
                addProperty("action", consumed.name.lowercase())
                addProperty("face", face.asString())
                addProperty("sneaking", sneaking)
                addProperty("playerFacing", playerFacing.asString())
                addProperty("gamemode", gamemode.name.lowercase())
                addProperty("creativeMode", creativeModeSnapshot ?: false)
                addProperty("eventConsumed", eventResult.isAccepted || eventResult == ActionResult.FAIL)
                addProperty("blockConsumed", blockResult.isAccepted)
                addProperty("itemConsumed", itemResult.isAccepted)
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
    private fun breakBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
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
                    fakePlayer.setStackInHand(Hand.MAIN_HAND, itemBefore.copy())
                    primeStackForGameplay(world, fakePlayer, fakePlayer.getStackInHand(Hand.MAIN_HAND), 0, true)

                    val lookDir = face.opposite
                    fakePlayer.refreshPositionAndAngles(
                        pos.x + 0.5 + face.offsetX * 1.0,
                        pos.y + 0.5 + face.offsetY * 1.0,
                        pos.z + 0.5 + face.offsetZ * 1.0,
                        yawFor(lookDir), pitchFor(lookDir)
                    )

                    // Real path: ServerPlayerInteractionManager.tryBreakBlock — the full
                    // server-side break pipeline (canMine check, creative instant break /
                    // survival drops + tool postMine, break events).
                    if (!world.getBlockState(pos).isAir) {
                        broken = fakePlayer.interactionManager.tryBreakBlock(pos)
                    }

                    itemAfter = fakePlayer.getStackInHand(Hand.MAIN_HAND).copy()
                } finally {
                    fakePlayer.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY)
                    restoreArmor(fakePlayer, oldArmor)
                    fakePlayer.isSneaking = false
                }
            }

            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("face", face.asString())
                addProperty("broken", broken)
                addProperty("gamemode", gamemode.name.lowercase())
                add("itemBefore", ServerContext.itemStackToJson(itemBefore))
                add("itemAfter", ServerContext.itemStackToJson(itemAfter))
                add("blockState", ServerContext.blockStateToJson(world.getBlockState(pos)))
            }
        }

    private fun attackBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
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
                    fakePlayer.setStackInHand(Hand.MAIN_HAND, itemBefore.copy())
                    primeStackForGameplay(world, fakePlayer, fakePlayer.getStackInHand(Hand.MAIN_HAND), 0, true)

                    val lookDir = face.opposite
                    fakePlayer.refreshPositionAndAngles(
                        pos.x + 0.5 + face.offsetX * 1.0,
                        pos.y + 0.5 + face.offsetY * 1.0,
                        pos.z + 0.5 + face.offsetZ * 1.0,
                        yawFor(lookDir), pitchFor(lookDir)
                    )

                    val stateBefore = world.getBlockState(pos)
                    if (!stateBefore.isAir) {
                        val im = fakePlayer.interactionManager
                        // Real mining gesture: START → hold (fast-forwarded through the same
                        // interactionManager.update() the server tick runs) → STOP.
                        // AttackBlockCallback fires automatically (fabric mixin at HEAD of
                        // processBlockBreakingAction); a consuming handler cancels everything.
                        im.processBlockBreakingAction(
                            pos, PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, face, world.topY, 0
                        )
                        if (!world.getBlockState(pos).isAir) {
                            // Survival: mine until the vanilla stop-check (>= 70% progress) passes.
                            val delta = stateBefore.calcBlockBreakingDelta(fakePlayer, world, pos)
                            val holdTicks = if (delta > 0f) {
                                ceil(0.7 / delta - 1.0).toInt().coerceAtLeast(0)
                            } else 0
                            repeat(holdTicks) { im.update() }
                            im.processBlockBreakingAction(
                                pos, PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, face, world.topY, 0
                            )
                        }
                    }

                    val stateAfter = world.getBlockState(pos)
                    broken = !stateBefore.isAir && stateAfter.isAir
                    // A consuming AttackBlockCallback cancels the whole method, leaving the
                    // block untouched (same observable as a refused break).
                    eventConsumed = !broken && stateAfter == stateBefore
                    itemAfter = fakePlayer.getStackInHand(Hand.MAIN_HAND).copy()
                } finally {
                    fakePlayer.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY)
                    restoreArmor(fakePlayer, oldArmor)
                    fakePlayer.isSneaking = false
                }
            }

            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("face", face.asString())
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
     * Routes through the real PlayerEntity.interact(entity, hand) — the method a real
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
     *   sneaking       bool    optional   player.isSneaking (default false)
     *   playerFacing   string  optional   which direction the player faces (for
     *                                    events that read player.horizontalFacing)
     *   dim            string  optional
     *
     * Returns:
     *   success         boolean
     *   action          string
     *   eventConsumed   boolean  Fabric UseEntityCallback consumed
     *   entityConsumed  boolean  entity.interact consumed
     *   itemConsumed    boolean  item.useOnEntity consumed
     *   entityType      string   type of the target entity
     *   itemBefore, itemAfter
     */
    private fun interactEntity(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
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
            var eventResult: ActionResult = ActionResult.PASS
            var entityResult: ActionResult = ActionResult.PASS
            var itemResult: ActionResult = ActionResult.PASS
            var itemAfter = ItemStack.EMPTY

            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                fakePlayer.setStackInHand(Hand.MAIN_HAND, itemBefore.copy())
                fakePlayer.isSneaking = sneaking

                // Position player near the entity, looking at it
                val eyePos = entity.eyePos
                fakePlayer.refreshPositionAndAngles(
                    eyePos.x, eyePos.y, eyePos.z,
                    yawFor(playerFacing), 0f
                )

                // Hit result pointing at entity center
                val hitResult = EntityHitResult(entity, eyePos)
                val itemBeforeSnapshot = fakePlayer.getStackInHand(Hand.MAIN_HAND).copy()

                // UseEntityCallback is fired by the fabric mixin on the PACKET handler
                // (ServerPlayNetworkHandler.onPlayerInteractEntity), not by
                // PlayerEntity.interact — fire it here at the same position (before the
                // real interact call, mirroring the mixin's order).
                eventResult = UseEntityCallback.EVENT.invoker()
                    .interact(fakePlayer, world, Hand.MAIN_HAND, entity, hitResult)

                if (eventResult.isAccepted || eventResult == ActionResult.FAIL) {
                    entityResult = ActionResult.PASS
                    itemResult = ActionResult.PASS
                } else {
                    // Real path: PlayerEntity.interact — the method a real right-click on
                    // an entity goes through: entity.interact(hand), then for living
                    // entities ItemStack.useOnEntity, creative count restore, GameEvent.
                    val result = fakePlayer.interact(entity, Hand.MAIN_HAND)
                    val itemChanged = !ItemStack.areEqual(
                        itemBeforeSnapshot, fakePlayer.getStackInHand(Hand.MAIN_HAND)
                    )
                    entityResult = if (result.isAccepted && !itemChanged) result else ActionResult.PASS
                    itemResult = if (result.isAccepted && itemChanged) result else ActionResult.PASS
                }

                itemAfter = fakePlayer.getStackInHand(Hand.MAIN_HAND).copy()

                fakePlayer.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY)
                fakePlayer.isSneaking = false
                fakePlayer.isSprinting = false
            }

            val success = eventResult.isAccepted || entityResult.isAccepted || itemResult.isAccepted
            val consumed = when {
                eventResult.isAccepted || eventResult == ActionResult.FAIL -> eventResult
                entityResult.isAccepted -> entityResult
                itemResult.isAccepted -> itemResult
                else -> ActionResult.PASS
            }

            JsonObject().apply {
                addProperty("success", success)
                addProperty("action", consumed.name.lowercase())
                addProperty("entityType", Registries.ENTITY_TYPE.getId(entity.type).toString())
                addProperty("entityUuid", entity.uuidAsString)
                addProperty("sneaking", sneaking)
                addProperty("playerFacing", playerFacing.asString())
                addProperty("gamemode", gamemode.name.lowercase())
                addProperty("eventConsumed", eventResult.isAccepted || eventResult == ActionResult.FAIL)
                addProperty("entityConsumed", entityResult.isAccepted)
                addProperty("itemConsumed", itemResult.isAccepted)
                add("entityPos", JsonArray().apply {
                    add(entity.pos.x); add(entity.pos.y); add(entity.pos.z)
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
     *   1. PlayerEntity.attack(entity) — vanilla attack (damage, knockback, sweep, etc.)
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
    private fun attackEntity(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
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
                    fakePlayer.setStackInHand(Hand.MAIN_HAND, itemBefore.copy())
                    primeStackForGameplay(world, fakePlayer, fakePlayer.getStackInHand(Hand.MAIN_HAND), 0, true)
                    addMainHandModifiers(fakePlayer, fakePlayer.getStackInHand(Hand.MAIN_HAND))
                    primeAttackCooldown(fakePlayer)

                    val playerFacing = p.getDirectionOrNull("playerFacing") ?: Direction.SOUTH
                    val eyePos = entity.eyePos
                    val playerOffset = Vec3d.of(playerFacing.opposite.vector).multiply(1.5)
                    val playerPos = entity.pos.add(playerOffset)
                    fakePlayer.refreshPositionAndAngles(
                        playerPos.x, entity.y, playerPos.z,
                        yawFor(playerFacing), 0f
                    )
                    fakePlayer.setOnGround(true)
                    fakePlayer.fallDistance = 0f
                    fakePlayer.setVelocity(0.0, 0.0, 0.0)
                    fakePlayer.isSprinting = false

                    attackDamageBefore = fakePlayer.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE)
                    attackSpeedBefore = fakePlayer.getAttributeValue(EntityAttributes.GENERIC_ATTACK_SPEED)
                    attackCooldownBefore = fakePlayer.getAttackCooldownProgress(0.5f)

                    // Real path: ServerPlayerEntity.attack. AttackEntityCallback fires
                    // automatically (fabric mixin at HEAD of this method) — a consuming
                    // handler cancels the whole attack. Detection: a real attack resets
                    // the attack cooldown (resetLastAttackedTicks), a canceled one leaves
                    // it primed.
                    fakePlayer.attack(entity)
                    eventConsumed = fakePlayer.getAttackCooldownProgress(0.5f) >= attackCooldownBefore - 0.05f

                    itemAfter = fakePlayer.getStackInHand(Hand.MAIN_HAND).copy()
                } finally {
                    removeMainHandModifiers(fakePlayer, fakePlayer.getStackInHand(Hand.MAIN_HAND))
                    fakePlayer.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY)
                    restoreArmor(fakePlayer, oldArmor)
                    fakePlayer.isSneaking = false
                    fakePlayer.isSprinting = false
                }
            }

            JsonObject().apply {
                addProperty("success", true)
                addProperty("entityType", Registries.ENTITY_TYPE.getId(entity.type).toString())
                addProperty("entityUuid", entity.uuidAsString)
                addProperty("eventConsumed", eventConsumed)
                addProperty("gamemode", gamemode.name.lowercase())
                add("entityPos", JsonArray().apply {
                    add(entity.pos.x); add(entity.pos.y); add(entity.pos.z)
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
        player: ServerPlayerEntity,
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
        val old = slots.values.associateWith { player.getEquippedStack(it).copy() }
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
            player.equipStack(slot, stack)
            primeStackForGameplay(player.serverWorld, player, stack, slot.entitySlotId, false)
        }
        return old
    }

    private fun primeStackForGameplay(
        world: ServerWorld,
        player: ServerPlayerEntity,
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

    private fun restoreArmor(player: ServerPlayerEntity, old: Map<EquipmentSlot, ItemStack>) {
        old.forEach { (slot, stack) -> player.equipStack(slot, stack) }
    }

    private fun addMainHandModifiers(player: ServerPlayerEntity, stack: ItemStack) {
        if (!stack.isEmpty) {
            player.getAttributes().addTemporaryModifiers(stack.getAttributeModifiers(EquipmentSlot.MAINHAND))
        }
    }

    private fun removeMainHandModifiers(player: ServerPlayerEntity, stack: ItemStack) {
        if (!stack.isEmpty) {
            player.getAttributes().removeModifiers(stack.getAttributeModifiers(EquipmentSlot.MAINHAND))
        }
    }

    private fun primeAttackCooldown(player: ServerPlayerEntity) {
        LAST_ATTACKED_TICKS_FIELD.setInt(player, ceil(player.getAttackCooldownProgressPerTick()).toInt())
    }

    private val LAST_ATTACKED_TICKS_FIELD by lazy {
        listOf("lastAttackedTicks", "field_6273", "aQ").firstNotNullOfOrNull { name ->
            findDeclaredField(PlayerEntity::class.java, name)
        } ?: error("Unable to resolve PlayerEntity lastAttackedTicks field")
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
        // Runtime classes are intermediary-named (mcdebug compiles against yarn,
        // Fabric Loader remaps to intermediary at runtime) — try the intermediary
        // name first, then the yarn name as fallback.
        findDeclaredMethod(LivingEntity::class.java, "method_6076", "tickActiveItemStack")
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
        fakePlayer: ServerPlayerEntity,
        targetPos: BlockPos,
        clickedPos: BlockPos,
        face: Direction,
        yaw: Float,
        pitch: Float,
    ) {
        val clickPoint = Vec3d(
            clickedPos.x + 0.5 + face.offsetX * 0.5,
            clickedPos.y + 0.5 + face.offsetY * 0.5,
            clickedPos.z + 0.5 + face.offsetZ * 0.5,
        )
        val targetBox = net.minecraft.util.math.Box(
            targetPos.x.toDouble(), targetPos.y.toDouble(), targetPos.z.toDouble(),
            targetPos.x + 1.0, targetPos.y + 1.0, targetPos.z + 1.0,
        )
        val reach = if (fakePlayer.abilities.creativeMode) 5.0 else 4.5
        val inReach = fakePlayer.getEyePos().squaredDistanceTo(clickPoint) <= reach * reach
        val obstructs = fakePlayer.boundingBox.intersects(targetBox)
        if (inReach && !obstructs) return

        val stand = Vec3d(
            clickedPos.x + 0.5 + face.opposite.offsetX * 1.5,
            clickedPos.y + 0.5 + face.opposite.offsetY * 1.5,
            clickedPos.z + 0.5 + face.opposite.offsetZ * 1.5,
        )
        fakePlayer.refreshPositionAndAngles(stand.x, stand.y, stand.z, yaw, pitch)
    }

    private fun getRegion(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val box = boxFromParams(p)
            val includeNbt = p.getBoolOrFalse("includeNbt")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            // No ensureChunkLoaded: World.getChunk synchronously loads on the main thread
            // (create=true), so getBlockState never NPEs. This matches getBlock/setBlock/etc.
            val results = JsonArray()
            BlockPos.iterate(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ).forEach { pos ->
                val state = world.getBlockState(pos)
                val be = world.getBlockEntity(pos)
                val entry = JsonObject().apply {
                    add("pos", ServerContext.posAsJson(pos))
                    addProperty("dim", world.registryKey.value.toString())
                    add("state", ServerContext.blockStateToJson(state))
                    addProperty("hasBlockEntity", be != null)
                    if (includeNbt && be != null) add("nbt", NbtJson.toJson(be.createNbt()))
                }
                results.add(entry)
            }
            JsonObject().apply { add("blocks", results) }
        }

    private fun selectBlocks(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val box = boxFromParams(p)
            val pred = p.getAsJsonObject("predicate")
                ?: throw RpcException(RpcErrors.INVALID_PARAMS, "predicate required")
            val blockId = pred.getStringOrNull("block")
            val includeNbt = p.getBoolOrFalse("includeNbt")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            // No ensureChunkLoaded: see getRegion.
            val target = if (blockId != null) ServerContext.blockById(server, blockId) else null
            val matches = JsonArray()
            BlockPos.iterate(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ).forEach { pos ->
                val state = world.getBlockState(pos)
                if (target != null && state.block != target) return@forEach
                val entry = JsonObject().apply { add("pos", ServerContext.posAsJson(pos)) }
                if (includeNbt) {
                    val be = world.getBlockEntity(pos)
                    if (be != null) entry.add("nbt", NbtJson.toJson(be.createNbt()))
                }
                matches.add(entry)
            }
            JsonObject().apply { add("matches", matches) }
        }

    /**
     * Force-load a chunk so its block entities tick.
     * Uses ServerWorld.setChunkForced (persistent, same as /forceload command).
     * This ensures the chunk stays in getForcedChunks(), which keeps tickBlockEntities() alive.
     *
     * 同步等待区块真正加载到 FULL 状态再返回。`setChunkForced` 只是把区块加入
     * forced 集合（发 ticket），实际加载在 chunk worker 线程异步进行——若 RPC
     * 立即返回，紧跟而来的 setBlocks/place 可能命中"还在加载"的区块，导致
     * 方块写入被吞或 BE 未注册（高并行测试下的 `no block entity` 竞态）。
     * getChunk(..., FULL, create=true) 在主线程会阻塞直到该区块 FULL。
     */
    private fun forceloadChunk(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val chunkPos = chunkPosFromParams(p)
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val changed = world.setChunkForced(chunkPos.x, chunkPos.z, true)
            // 同步等到区块 FULL：在主线程调用，create=true 阻塞直到加载完成。
            val chunk = world.chunkManager.getChunk(
                chunkPos.x, chunkPos.z,
                net.minecraft.world.chunk.ChunkStatus.FULL, true,
            )
            val loaded = chunk != null
            JsonObject().apply {
                add("chunk", JsonArray().apply { add(chunkPos.x); add(chunkPos.z) })
                addProperty("forced", true)
                addProperty("changed", changed)
                addProperty("loaded", loaded)
                addProperty("dim", world.registryKey.value.toString())
            }
        }

    /**
     * Stop force-loading a chunk.
     */
    private fun unforceloadChunk(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val chunkPos = chunkPosFromParams(p)
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val changed = world.setChunkForced(chunkPos.x, chunkPos.z, false)
            JsonObject().apply {
                add("chunk", JsonArray().apply { add(chunkPos.x); add(chunkPos.z) })
                addProperty("forced", false)
                addProperty("changed", changed)
                addProperty("dim", world.registryKey.value.toString())
            }
        }

    private fun chunkPosFromParams(p: JsonObject): ChunkPos {
        val arr = p.getAsJsonArray("chunk")
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "chunk [cx,cz] required")
        if (arr.size() < 2) throw RpcException(RpcErrors.INVALID_PARAMS, "chunk must be [cx, cz]")
        return ChunkPos(arr.get(0).asInt, arr.get(1).asInt)
    }

    // ---- helpers ----

    private fun boxFromParams(p: JsonObject): BlockBox {
        val box = p.getAsJsonObject("box") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "box required")
        val from = ServerContext.pos(box.getAsJsonArray("from"))
        val to = ServerContext.pos(box.getAsJsonArray("to"))
        return ServerContext.boxFrom(from, to)
    }
    private fun ensureChunkLoaded(world: ServerWorld, box: BlockBox) {
        val minCx = box.minX shr 4
        val maxCx = box.maxX shr 4
        val minCz = box.minZ shr 4
        val maxCz = box.maxZ shr 4
        for (cx in minCx..maxCx) for (cz in minCz..maxCz) {
            // ChunkNotLoaded: we just check; auto-loading is left to v2
            if (!world.chunkManager.isChunkLoaded(cx, cz)) {
                throw RpcException(RpcErrors.CHUNK_NOT_LOADED, "chunk not loaded: ($cx, $cz)")
            }
        }
    }

    private fun boxBlockCount(box: BlockBox): Int =
        (box.maxX - box.minX + 1) * (box.maxY - box.minY + 1) * (box.maxZ - box.minZ + 1)

    private fun fillBoxNow(
        server: MinecraftServer,
        p: JsonObject,
        blockId: String,
        stateProps: Map<String, String>?
    ): JsonObject {
        val box = boxFromParams(p)
        val world = ServerContext.world(server, p.getStringOrNull("dim"))
        val maxBlocks = p.getIntOr("maxBlocks", 32768)
        val total = boxBlockCount(box)
        if (total > maxBlocks) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "fillBox touches $total blocks; pass maxBlocks >= $total to confirm")
        }
        ensureChunkLoaded(world, box)
        val flags = p.getIntOr("flags", DEFAULT_FLAGS)
        val state = ServerContext.blockState(server, blockId, stateProps)
        var changed = 0
        BlockPos.iterate(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ).forEach { pos ->
            if (world.setBlockState(pos, state, flags)) changed++
        }
        return JsonObject().apply {
            addProperty("count", total)
            addProperty("changed", changed)
            addProperty("dim", world.registryKey.value.toString())
        }
    }
}

// ---- json helpers ----

internal fun JsonObject.getString(name: String): String? =
    if (has(name) && !get(name).isJsonNull) get(name).asString else null

internal fun JsonObject.getString(name: String, default: String): String =
    getString(name) ?: default

internal fun JsonObject.requireString(name: String): String =
    getString(name) ?: throw RpcException(RpcErrors.INVALID_PARAMS, "missing string param: $name")

internal fun JsonObject.getStringOrNull(name: String): String? = getString(name)

internal fun JsonObject.getIntOr(name: String, default: Int): Int =
    if (has(name) && !get(name).isJsonNull) get(name).asInt else default

internal fun JsonObject.getBoolOrFalse(name: String): Boolean =
    has(name) && !get(name).isJsonNull && get(name).asBoolean

internal fun JsonObject.getStringMapOrNull(name: String): Map<String, String>? {
    if (!has(name) || get(name).isJsonNull) return null
    val obj = getAsJsonObject(name) ?: return null
    val out = mutableMapOf<String, String>()
    obj.entrySet().forEach { (k, v) ->
        if (v.isJsonPrimitive) out[k] = v.asString
    }
    return out
}

private val DIRECTION_NAMES = mapOf(
    "up" to Direction.UP,
    "down" to Direction.DOWN,
    "north" to Direction.NORTH,
    "south" to Direction.SOUTH,
    "east" to Direction.EAST,
    "west" to Direction.WEST,
)

internal fun JsonObject.requireDirection(name: String): Direction {
    val s = getString(name)
        ?: throw RpcException(RpcErrors.INVALID_PARAMS, "missing string param: $name")
    return DIRECTION_NAMES[s.lowercase()]
        ?: throw RpcException(
            RpcErrors.INVALID_PARAMS,
            "invalid $name: $s; expected one of ${DIRECTION_NAMES.keys.sorted()}"
        )
}

internal fun JsonObject.getDirectionOrNull(name: String): Direction? {
    val s = getString(name) ?: return null
    return DIRECTION_NAMES[s.lowercase()]
}


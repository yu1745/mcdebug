package com.mcdebug.api.player

import com.mcdebug.api.FakePlayerPool
import com.mcdebug.api.requirePos
import com.mcdebug.api.requireString
import com.mcdebug.api.requireDirection
import com.mcdebug.api.getStringOrNull
import com.mcdebug.api.getDirectionOrNull
import com.mcdebug.api.getIntOr
import com.mcdebug.api.getBoolOrFalse
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
 * Fake-player item use: air right-click (useItem) and the full hold-and-release
 * lifecycle for ranged weapons (useItemHold: use -> usageTick x N -> release,
 * with projectile diffing). Shared plumbing lives in [PlayerActionSupport].
 */
internal object ItemPlayerActions {

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
            val itemBefore = ServerContext.itemStackFromJson(server, itemId, count, p.get("nbt"), p.get("components"))
    
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
     *   3. Player.releaseUsingItem() — the "release". This calls
     *      Item.releaseUsing (bow fires, crossbow finishes loading), then clears use state.
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
            val itemBefore = ServerContext.itemStackFromJson(server, itemId, count, p.get("nbt"), p.get("components"))
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
                    PlayerActionSupport.primeStackForGameplay(world, fakePlayer, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND), 0, true)
    
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
                                PlayerActionSupport.invokeTickActiveItemStack(fakePlayer)
                            }
                            // 3. Release: onStoppedUsing (bow fires, crossbow loads).
                            fakePlayer.releaseUsingItem()
                            cycleJson.addProperty("stopped", true)
                        } else {
                            cycleJson.addProperty("stopped", false)
                        }
                        cycleJson.addProperty("stillUsingAfterRelease", fakePlayer.isUsingItem())
                        cycleJson.addProperty("useTimeLeft", fakePlayer.useItemRemainingTicks)
                        cycleJson.addProperty("tickMethodFound", PlayerActionSupport.TICK_ACTIVE_ITEM_STACK_METHOD != null)
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
}

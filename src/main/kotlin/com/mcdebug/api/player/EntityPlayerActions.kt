package com.mcdebug.api.player

import com.mcdebug.api.FakePlayerPool
import com.mcdebug.api.requireString
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
 * Fake-player entity gestures: right-click interact (loader use-entity event ->
 * Player.interact -> item useOnEntity) and left-click attack (attribute modifiers,
 * cooldown priming, AttackEntityCallback detection). Plumbing in [PlayerActionSupport].
 */
internal object EntityPlayerActions {

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
                val componentsEl = p.get("components")
                ServerContext.itemStackFromJson(server, itemId, count, nbtEl, componentsEl)
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
                    PlayerActionSupport.yawFor(playerFacing), 0f
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
                val componentsEl = p.get("components")
                ServerContext.itemStackFromJson(server, itemId, count, nbtEl, componentsEl)
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
                val oldArmor = PlayerActionSupport.installArmor(server, fakePlayer, p)
                var heldModifiers: com.google.common.collect.Multimap<Holder<Attribute>, AttributeModifier> =
                    LinkedHashMultimap.create()
    
                try {
                    fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, itemBefore.copy())
                    PlayerActionSupport.primeStackForGameplay(world, fakePlayer, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND), 0, true)
                    heldModifiers = PlayerActionSupport.mainHandModifierMultimap(fakePlayer.getItemInHand(InteractionHand.MAIN_HAND))
                    fakePlayer.getAttributes().addTransientAttributeModifiers(heldModifiers)
                    PlayerActionSupport.primeAttackCooldown(fakePlayer)
    
                    val playerFacing = p.getDirectionOrNull("playerFacing") ?: Direction.SOUTH
                    val eyePos = entity.getEyePosition()
                    val playerOffset = Vec3.atLowerCornerOf(playerFacing.opposite.normal).scale(1.5)
                    val playerPos = entity.position().add(playerOffset)
                    fakePlayer.moveTo(
                        playerPos.x, entity.y, playerPos.z,
                        PlayerActionSupport.yawFor(playerFacing), 0f
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
                    // The attack may break the stack or change its dynamic modifiers. Remove what we installed.
                    fakePlayer.getAttributes().removeAttributeModifiers(heldModifiers)
                    fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                    PlayerActionSupport.restoreArmor(fakePlayer, oldArmor)
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
    
}

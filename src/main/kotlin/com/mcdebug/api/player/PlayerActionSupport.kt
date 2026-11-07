package com.mcdebug.api.player

import com.google.gson.JsonObject
import com.mcdebug.api.getStringOrNull
import com.mcdebug.api.getIntOr
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.util.ServerContext
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import net.minecraft.core.BlockPos
import com.google.common.collect.LinkedHashMultimap
import kotlin.math.ceil

/**
 * Shared plumbing for the fake-player action groups ([BlockPlayerActions],
 * [ItemPlayerActions], [EntityPlayerActions]): armor install/restore, held-stack
 * priming, aim/positioning math and the two reflection bridges into private
 * vanilla methods (attack cooldown, tickActiveItemStack).
 */
internal object PlayerActionSupport {

    private val LOGGER: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("mcdebug-worldops")

    /**
     * Install temporary armor on the shared fake player. The JSON order follows the
     * equipment slot names rather than PlayerInventory's internal feet-first order.
     * This is intentionally per-call so one test cannot leak equipped armor into
     * another.
     */
    internal fun installArmor(
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
            val stack = ServerContext.itemStackFromJson(server, item, count, stackObject.get("nbt"), stackObject.get("components"))
            player.setItemSlot(slot, stack)
            primeStackForGameplay(player.level() as ServerLevel, player, stack, slot.getIndex(), false)
        }
        return old
    }
    
    internal fun primeStackForGameplay(
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
    
    internal fun restoreArmor(player: ServerPlayer, old: Map<EquipmentSlot, ItemStack>) {
        old.forEach { (slot, stack) -> player.setItemSlot(slot, stack) }
    }
    
    // Use the same stack-aware path as real equipment: components, dynamic item hooks and enchantments.
    internal fun mainHandModifierMultimap(stack: ItemStack): com.google.common.collect.Multimap<Holder<Attribute>, AttributeModifier> {
        val multimap = LinkedHashMultimap.create<Holder<Attribute>, AttributeModifier>()
        if (!stack.isEmpty) {
            stack.forEachModifier(EquipmentSlot.MAINHAND) { attribute, modifier ->
                multimap.put(attribute, modifier)
            }
        }
        return multimap
    }
    
    internal fun primeAttackCooldown(player: ServerPlayer) {
        LAST_ATTACKED_TICKS_FIELD.setInt(player, ceil(player.getCurrentItemAttackStrengthDelay()).toInt())
    }
    
    internal val LAST_ATTACKED_TICKS_FIELD by lazy {
        listOf("lastAttackedTicks", "field_6273", "aQ").firstNotNullOfOrNull { name ->
            findDeclaredField(Player::class.java, name)
        } ?: error("Unable to resolve Player lastAttackedTicks field")
    }
    
    internal fun findDeclaredField(type: Class<*>, name: String): java.lang.reflect.Field? {
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
    internal fun invokeTickActiveItemStack(entity: LivingEntity) {
        if (!entity.isUsingItem()) return
        val method = TICK_ACTIVE_ITEM_STACK_METHOD ?: return
        try {
            method.invoke(entity)
        } catch (e: Exception) {
            LOGGER.warn("tickActiveItemStack invoke failed: {}", e.toString())
        }
    }
    
    internal val TICK_ACTIVE_ITEM_STACK_METHOD: java.lang.reflect.Method? by lazy {
        // LivingEntity.updatingUsingItem() is protected: intermediary name in production,
        // Mojang name in a Loom dev run.
        findDeclaredMethod(LivingEntity::class.java, "method_6076", "updatingUsingItem")
    }
    
    internal fun findDeclaredMethod(type: Class<*>, vararg names: String): java.lang.reflect.Method? {
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
    internal fun yawFor(facing: Direction): Float = when (facing) {
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
    internal fun pitchFor(facing: Direction): Float = when (facing) {
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
    internal fun positionForPlacement(
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

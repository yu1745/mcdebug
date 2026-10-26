package com.mcdebug.util

import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.nbt.CompoundTag
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level

/**
 * Helpers for resolving common objects (world, block, position, block-entity, inventory) from RPC params.
 * All "lookup" methods throw RpcException on failure.
 */
object ServerContext {

    fun world(server: MinecraftServer, dimId: String?): ServerLevel {
        val key = dimKey(dimId ?: "minecraft:overworld")
        return server.getLevel(key)
            ?: throw RpcException(RpcErrors.DIMENSION_NOT_FOUND, "dimension not found: $dimId")
    }

    /** MinecraftServer.getWorld takes ResourceKey<Level>, not <ServerLevel>. */
    fun dimKey(id: String): ResourceKey<Level> {
        val identifier = ResourceLocation.tryParse(id)
            ?: throw RpcException(RpcErrors.INVALID_POSITION, "invalid dimension id: $id")
        @Suppress("UNCHECKED_CAST")
        return ResourceKey.create(net.minecraft.core.registries.Registries.LEVEL_STEM, identifier) as ResourceKey<Level>
    }

    fun blockById(server: MinecraftServer, id: String): Block {
        val identifier = ResourceLocation.tryParse(id)
            ?: throw RpcException(RpcErrors.BLOCK_NOT_FOUND, "invalid block id: $id")
        val key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK, identifier)
        val block = server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BLOCK).get(key)
            ?: throw RpcException(RpcErrors.BLOCK_NOT_FOUND, "block not registered: $id")
        return block
    }

    fun itemById(server: MinecraftServer, id: String): net.minecraft.world.item.Item {
        val identifier = ResourceLocation.tryParse(id)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "invalid item id: $id")
        val key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, identifier)
        val item = server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ITEM).get(key)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "item not registered: $id")
        return item
    }

    fun pos(arr: com.google.gson.JsonArray): BlockPos {
        if (arr.size() != 3) throw RpcException(RpcErrors.INVALID_POSITION, "pos must have 3 elements")
        return BlockPos(arr[0].asInt, arr[1].asInt, arr[2].asInt)
    }

    fun blockEntity(world: ServerLevel, pos: BlockPos): BlockEntity {
        return world.getBlockEntity(pos)
            ?: throw RpcException(RpcErrors.BLOCK_ENTITY_MISSING, "no block entity at $pos in ${world.dimension().location()}")
    }

    fun inventory(world: ServerLevel, pos: BlockPos): Container {
        val be = blockEntity(world, pos)
        return be as? Container
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "block entity at $pos is not an Container")
    }

    fun posAsJson(pos: BlockPos): com.google.gson.JsonArray = com.google.gson.JsonArray().apply {
        add(pos.x); add(pos.y); add(pos.z)
    }

    fun boxFrom(from: BlockPos, to: BlockPos): BoundingBox {
        return BoundingBox(
            minOf(from.x, to.x), minOf(from.y, to.y), minOf(from.z, to.z),
            maxOf(from.x, to.x), maxOf(from.y, to.y), maxOf(from.z, to.z)
        )
    }

    /**
     * Collect every distinct Property on a block (across all its states).
     * StateManager exposes a private `properties` map; the public way is to iterate getStates().
     */
    fun allPropertiesOf(block: Block): Map<String, Property<*>> {
        val seen = LinkedHashMap<String, Property<*>>()
        for (s in block.stateDefinition.possibleStates) {
            for (p in s.properties) {
                seen.putIfAbsent(p.name, p)
            }
        }
        return seen
    }

    fun blockState(server: MinecraftServer, blockId: String, stateProps: Map<String, String>?): BlockState {
        val block = blockById(server, blockId)
        if (stateProps.isNullOrEmpty()) return block.defaultBlockState()
        val propByName = allPropertiesOf(block)
        // Parse all (prop, value) pairs first; fail fast on invalid input.
        val parsed: List<Pair<Property<*>, Any?>> = stateProps.map { (k, v) ->
            val prop = propByName[k] ?: throw RpcException(
                RpcErrors.INVALID_BLOCK_STATE, "unknown property '$k' for $blockId; available=${propByName.keys}"
            )
            val p = prop.getValue(v)
            if (p.isEmpty) throw RpcException(
                RpcErrors.INVALID_BLOCK_STATE, "invalid value '$v' for property '$k' on $blockId"
            )
            prop to p.get()
        }
        // Find a state where all parsed values match. This avoids the generic-bound gymnastics
        // that would be required to call State.with<T extends Comparable<T>, V extends T>(Property<T>, V)
        // when T is only known at runtime (Property<*>).
        return block.stateDefinition.possibleStates.firstOrNull { candidate ->
            parsed.all { (prop, value) -> candidate.getValue(prop) == value }
        } ?: throw RpcException(
            RpcErrors.INVALID_BLOCK_STATE, "no state of $blockId matches properties $stateProps"
        )
    }

    fun blockStateToJson(state: BlockState): com.google.gson.JsonObject {
        val obj = com.google.gson.JsonObject()
        obj.addProperty("name", BuiltInRegistries.BLOCK.getKey(state.block).toString())
        val props = com.google.gson.JsonObject()
        for ((prop, value) in state.values) {
            props.addProperty(prop.name, propertyValueName(prop, value))
        }
        obj.add("props", props)
        return obj
    }

    @Suppress("UNCHECKED_CAST")
    private fun propertyValueName(prop: Property<*>, value: Comparable<*>): String =
        (prop as Property<Comparable<Any>>).getName(value as Comparable<Any>)

    /**
     * 1.21: ItemStack no longer carries a raw NBT tag; arbitrary per-stack NBT lives in the
     * `minecraft:custom_data` component. These helpers keep the RPC contract's "nbt" field
     * meaning the same thing across versions.
     */
    fun stackNbt(stack: ItemStack): CompoundTag? =
        (stack.get(DataComponents.CUSTOM_DATA) as? CustomData)?.copyTag()

    fun stackSetNbt(stack: ItemStack, nbt: CompoundTag?) {
        //set(type, null) removes the component
        stack.set(DataComponents.CUSTOM_DATA, nbt?.let(CustomData::of))
    }

    fun itemStackToJson(stack: ItemStack): com.google.gson.JsonObject {
        val obj = com.google.gson.JsonObject()
        if (stack.isEmpty) {
            obj.add("item", com.google.gson.JsonNull.INSTANCE)
            obj.addProperty("count", 0)
            return obj
        }
        obj.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.item).toString())
        obj.addProperty("count", stack.count)
        val nbt = stackNbt(stack)
        if (nbt != null) obj.add("nbt", NbtJson.toJson(nbt))
        return obj
    }

    fun itemStackFromJson(server: MinecraftServer, item: String, count: Int, nbtJson: com.google.gson.JsonElement?): ItemStack {
        if (item == "minecraft:air" || item.isEmpty()) return ItemStack.EMPTY
        val it = itemById(server, item)
        val stack = ItemStack(it, count)
        if (nbtJson != null && !nbtJson.isJsonNull) {
            stackSetNbt(stack, NbtJson.fromJson(nbtJson) as? CompoundTag)
        }
        return stack
    }
}

package com.mcdebug.util

import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.entity.BlockEntity
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.state.property.Property
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

/**
 * Helpers for resolving common objects (world, block, position, block-entity, inventory) from RPC params.
 * All "lookup" methods throw RpcException on failure.
 */
object ServerContext {

    fun world(server: MinecraftServer, dimId: String?): ServerWorld {
        val key = dimKey(dimId ?: "minecraft:overworld")
        return server.getWorld(key)
            ?: throw RpcException(RpcErrors.DIMENSION_NOT_FOUND, "dimension not found: $dimId")
    }

    /** MinecraftServer.getWorld takes RegistryKey<World>, not <ServerWorld>. */
    fun dimKey(id: String): RegistryKey<World> {
        val identifier = Identifier.tryParse(id)
            ?: throw RpcException(RpcErrors.INVALID_POSITION, "invalid dimension id: $id")
        @Suppress("UNCHECKED_CAST")
        return RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, identifier) as RegistryKey<World>
    }

    fun blockById(server: MinecraftServer, id: String): Block {
        val identifier = Identifier.tryParse(id)
            ?: throw RpcException(RpcErrors.BLOCK_NOT_FOUND, "invalid block id: $id")
        val key = net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.BLOCK, identifier)
        val block = server.registryManager.get(net.minecraft.registry.RegistryKeys.BLOCK).getOrEmpty(key).orElse(null)
            ?: throw RpcException(RpcErrors.BLOCK_NOT_FOUND, "block not registered: $id")
        return block
    }

    fun itemById(server: MinecraftServer, id: String): net.minecraft.item.Item {
        val identifier = Identifier.tryParse(id)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "invalid item id: $id")
        val key = net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.ITEM, identifier)
        val item = server.registryManager.get(net.minecraft.registry.RegistryKeys.ITEM).getOrEmpty(key).orElse(null)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "item not registered: $id")
        return item
    }

    fun pos(arr: com.google.gson.JsonArray): BlockPos {
        if (arr.size() != 3) throw RpcException(RpcErrors.INVALID_POSITION, "pos must have 3 elements")
        return BlockPos(arr[0].asInt, arr[1].asInt, arr[2].asInt)
    }

    fun blockEntity(world: ServerWorld, pos: BlockPos): BlockEntity {
        return world.getBlockEntity(pos)
            ?: throw RpcException(RpcErrors.BLOCK_ENTITY_MISSING, "no block entity at $pos in ${world.registryKey.value}")
    }

    fun inventory(world: ServerWorld, pos: BlockPos): Inventory {
        val be = blockEntity(world, pos)
        return be as? Inventory
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "block entity at $pos is not an Inventory")
    }

    fun posAsJson(pos: BlockPos): com.google.gson.JsonArray = com.google.gson.JsonArray().apply {
        add(pos.x); add(pos.y); add(pos.z)
    }

    fun boxFrom(from: BlockPos, to: BlockPos): BlockBox {
        return BlockBox(
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
        for (s in block.stateManager.states) {
            for (p in s.properties) {
                seen.putIfAbsent(p.name, p)
            }
        }
        return seen
    }

    fun blockState(server: MinecraftServer, blockId: String, stateProps: Map<String, String>?): BlockState {
        val block = blockById(server, blockId)
        if (stateProps.isNullOrEmpty()) return block.defaultState
        val propByName = allPropertiesOf(block)
        // Parse all (prop, value) pairs first; fail fast on invalid input.
        val parsed: List<Pair<Property<*>, Any?>> = stateProps.map { (k, v) ->
            val prop = propByName[k] ?: throw RpcException(
                RpcErrors.INVALID_BLOCK_STATE, "unknown property '$k' for $blockId; available=${propByName.keys}"
            )
            val p = prop.parse(v)
            if (p.isEmpty) throw RpcException(
                RpcErrors.INVALID_BLOCK_STATE, "invalid value '$v' for property '$k' on $blockId"
            )
            prop to p.get()
        }
        // Find a state where all parsed values match. This avoids the generic-bound gymnastics
        // that would be required to call State.with<T extends Comparable<T>, V extends T>(Property<T>, V)
        // when T is only known at runtime (Property<*>).
        return block.stateManager.states.firstOrNull { candidate ->
            parsed.all { (prop, value) -> candidate.get(prop) == value }
        } ?: throw RpcException(
            RpcErrors.INVALID_BLOCK_STATE, "no state of $blockId matches properties $stateProps"
        )
    }

    fun blockStateToJson(state: BlockState): com.google.gson.JsonObject {
        val obj = com.google.gson.JsonObject()
        obj.addProperty("name", Registries.BLOCK.getId(state.block).toString())
        val props = com.google.gson.JsonObject()
        for ((prop, value) in state.entries) {
            props.addProperty(prop.name, propertyValueName(prop, value))
        }
        obj.add("props", props)
        return obj
    }

    @Suppress("UNCHECKED_CAST")
    private fun propertyValueName(prop: Property<*>, value: Comparable<*>): String =
        (prop as Property<Comparable<Any>>).name(value as Comparable<Any>)

    fun itemStackToJson(stack: ItemStack): com.google.gson.JsonObject {
        val obj = com.google.gson.JsonObject()
        if (stack.isEmpty) {
            obj.add("item", com.google.gson.JsonNull.INSTANCE)
            obj.addProperty("count", 0)
            return obj
        }
        obj.addProperty("item", Registries.ITEM.getId(stack.item).toString())
        obj.addProperty("count", stack.count)
        val nbt = stack.nbt
        if (nbt != null) obj.add("nbt", NbtJson.toJson(nbt))
        return obj
    }

    fun itemStackFromJson(server: MinecraftServer, item: String, count: Int, nbtJson: com.google.gson.JsonElement?): ItemStack {
        if (item == "minecraft:air" || item.isEmpty()) return ItemStack.EMPTY
        val it = itemById(server, item)
        val stack = ItemStack(it, count)
        if (nbtJson != null && !nbtJson.isJsonNull) {
            stack.nbt = NbtJson.fromJson(nbtJson) as? NbtCompound
        }
        return stack
    }
}

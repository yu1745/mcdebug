package com.mcdebug.util

import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.google.gson.JsonElement
import com.mojang.serialization.Codec
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.JsonOps
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponentPatch
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
        // Properties not given keep their default value (the first matching state could have
        // any value there, e.g. a lever placed with only `face` came out powered).
        val default = block.defaultBlockState()
        val given = parsed.map { it.first }.toSet()
        return block.stateDefinition.possibleStates.firstOrNull { candidate ->
            parsed.all { (prop, value) -> candidate.getValue(prop) == value } &&
                candidate.properties.all { it in given || candidate.getValue(it) == default.getValue(it) }
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
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()

    fun stackSetNbt(stack: ItemStack, nbt: CompoundTag?) {
        //set(type, null) removes the component
        stack.set(DataComponents.CUSTOM_DATA, nbt?.let(CustomData::of))
    }

    /**
     * Registry access used to (de)serialize data components. Set when the server starts so that
     * [itemStackToJson] (called from many places without a server handle) can encode registry-backed
     * components such as enchantments. Null before start / after stop: components are then omitted.
     */
    @Volatile
    var registries: HolderLookup.Provider? = null

    private fun componentOps(provider: HolderLookup.Provider?): DynamicOps<JsonElement> =
        provider?.createSerializationContext(JsonOps.INSTANCE) ?: JsonOps.INSTANCE

    /**
     * The stack's component patch (differences from the item's defaults) in the vanilla
     * `{"minecraft:id": value, "!minecraft:removed": {}}` form, without `minecraft:custom_data`
     * (already reported as `nbt`). Each component is encoded separately so one component without a
     * codec (transient) or failing to encode does not hide the others; failures are listed under
     * `"#errors"`. Null when there is nothing to report.
     */
    fun componentsToJson(stack: ItemStack, provider: HolderLookup.Provider? = registries): com.google.gson.JsonObject? {
        val patch = stack.componentsPatch
        if (patch.isEmpty) return null
        val ops = componentOps(provider)
        val out = com.google.gson.JsonObject()
        val errors = com.google.gson.JsonObject()
        for ((type, value) in patch.entrySet()) {
            if (type == DataComponents.CUSTOM_DATA) continue
            val id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)?.toString() ?: type.toString()
            if (!value.isPresent) {
                out.add("!$id", com.google.gson.JsonObject())
                continue
            }
            val codec = type.codec()
            if (codec == null) {
                errors.addProperty(id, "transient component (no codec)")
                continue
            }
            @Suppress("UNCHECKED_CAST")
            (codec as Codec<Any>).encodeStart(ops, value.get())
                .ifSuccess { out.add(id, it) }
                .ifError { errors.addProperty(id, it.message()) }
        }
        if (errors.size() > 0) out.add("#errors", errors)
        return if (out.size() == 0) null else out
    }

    /** Parses a vanilla component patch (`{"minecraft:enchantments": {...}, "!minecraft:food": {}}`). */
    fun componentsFromJson(server: MinecraftServer, json: JsonElement): DataComponentPatch =
        DataComponentPatch.CODEC.parse(componentOps(server.registryAccess()), json)
            .getOrThrow { RpcException(RpcErrors.INVALID_PARAMS, "invalid components: $it") }

    /** [provider]: registries that own the stack's holders (the client plane passes the client level's). */
    fun itemStackToJson(stack: ItemStack, provider: HolderLookup.Provider? = registries): com.google.gson.JsonObject {
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
        componentsToJson(stack, provider)?.let { obj.add("components", it) }
        return obj
    }

    /**
     * Builds a stack from RPC fields. `nbt` is the custom-data compound (see [stackNbt]); `components`
     * is an optional vanilla component patch applied afterwards, for registry-backed data that
     * custom data cannot express (enchantments, potion contents, damage, custom name...).
     */
    fun itemStackFromJson(
        server: MinecraftServer,
        item: String,
        count: Int,
        nbtJson: JsonElement?,
        componentsJson: JsonElement? = null,
    ): ItemStack {
        if (item == "minecraft:air" || item.isEmpty()) return ItemStack.EMPTY
        val it = itemById(server, item)
        val stack = ItemStack(it, count)
        if (nbtJson != null && !nbtJson.isJsonNull) {
            stackSetNbt(stack, NbtJson.fromJson(nbtJson) as? CompoundTag)
        }
        if (componentsJson != null && !componentsJson.isJsonNull) {
            stack.applyComponents(componentsFromJson(server, componentsJson))
        }
        return stack
    }
}

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
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.core.BlockPos
import java.util.concurrent.CompletableFuture

object ScanOps : RpcHandlerGroup {
    override fun methods(): Map<String, RpcHandler> = mapOf(
        "findBlocks" to ::findBlocks,
        "countByBlock" to ::countByBlock,
        "findEntities" to ::findEntities
    )

    private fun findBlocks(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val box = p.requireBox()
            val blockId = p.requireString("block")
            val count = p.getBoolOrFalse("count")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val target = ServerContext.blockById(server, blockId)
            val positions = JsonArray()
            var n = 0
            BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()).forEach { pos ->
                if (world.getBlockState(pos).block == target) {
                    positions.add(ServerContext.posAsJson(pos))
                    n++
                }
            }
            JsonObject().apply {
                add("positions", positions)
                if (count) addProperty("count", n)
            }
        }

    private fun countByBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val box = p.requireBox()
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val counts = HashMap<String, Int>()
            BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()).forEach { pos ->
                val state = world.getBlockState(pos)
                val key = BuiltInRegistries.BLOCK.getKey(state.block).toString()
                counts.merge(key, 1) { a, b -> a + b }
            }
            JsonObject().apply {
                val obj = JsonObject()
                counts.entries.sortedBy { it.key }.forEach { (k, v) -> obj.addProperty(k, v) }
                add("counts", obj)
            }
        }

    /**
     * List all entities in a box.
     *
     * Returns entity type, position, UUID, health (for living entities), and
     * optional NBT. Useful for verifying spawn eggs, minecarts, item frames, etc.
     */
    private fun findEntities(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            // Whole blocks from..to inclusive, like every other box param (entity.* included).
            val entityBox = p.requireEntityBox()
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val includeNbt = p.getBoolOrFalse("includeNbt")

            val typeId = p.getStringOrNull("type")
            lateinit var entityTypeFilter: (Entity) -> Boolean
            if (typeId != null) {
                val identifier = net.minecraft.resources.ResourceLocation.tryParse(typeId)
                    ?: throw RpcException(RpcErrors.INVALID_PARAMS, "invalid entity type: $typeId")
                val key = net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.ENTITY_TYPE, identifier
                )
                val resolved: net.minecraft.world.entity.EntityType<*> =
                    server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENTITY_TYPE)
                        .get(key)
                        ?: throw RpcException(RpcErrors.INVALID_PARAMS, "entity type not registered: $typeId")
                entityTypeFilter = { e: Entity -> e.type == resolved }
            } else {
                entityTypeFilter = { _: Entity -> true }
            }

            val entities = world.getEntities(null, entityBox, entityTypeFilter)
            val results = JsonArray()
            for (entity in entities) {
                val entry = JsonObject().apply {
                    addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString())
                    addProperty("uuid", entity.getStringUUID())
                    val pos = entity.position()
                    addProperty("x", pos.x)
                    addProperty("y", pos.y)
                    addProperty("z", pos.z)
                    if (entity is LivingEntity) {
                        addProperty("health", entity.health)
                        addProperty("maxHealth", entity.maxHealth)
                    }
                    if (includeNbt) {
                        val nbt = entity.saveWithoutId(net.minecraft.nbt.CompoundTag())
                        add("nbt", NbtJson.toJson(nbt))
                    }
                }
                results.add(entry)
            }
            JsonObject().apply {
                add("entities", results)
                addProperty("count", results.size())
            }
        }
}

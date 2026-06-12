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
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
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
            val boxObj = p.getAsJsonObject("box") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "box required")
            val from = ServerContext.pos(boxObj.getAsJsonArray("from"))
            val to = ServerContext.pos(boxObj.getAsJsonArray("to"))
            val box = ServerContext.boxFrom(from, to)
            val blockId = p.requireString("block")
            val count = p.getBoolOrFalse("count")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val target = ServerContext.blockById(server, blockId)
            val positions = JsonArray()
            var n = 0
            BlockPos.iterate(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ).forEach { pos ->
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
            val boxObj = p.getAsJsonObject("box") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "box required")
            val from = ServerContext.pos(boxObj.getAsJsonArray("from"))
            val to = ServerContext.pos(boxObj.getAsJsonArray("to"))
            val box = ServerContext.boxFrom(from, to)
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val counts = HashMap<String, Int>()
            BlockPos.iterate(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ).forEach { pos ->
                val state = world.getBlockState(pos)
                val key = Registries.BLOCK.getId(state.block).toString()
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
            val boxObj = p.getAsJsonObject("box") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "box required")
            val from = ServerContext.pos(boxObj.getAsJsonArray("from"))
            val to = ServerContext.pos(boxObj.getAsJsonArray("to"))
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val includeNbt = p.getBoolOrFalse("includeNbt")

            // Use double-precision box for entity iteration (entities use Vec3d, not BlockPos)
            val entityBox = Box(
                from.x.toDouble(), from.y.toDouble(), from.z.toDouble(),
                to.x.toDouble(), to.y.toDouble(), to.z.toDouble()
            ).expand(1.0) // +1 block margin to catch entities on the edge

            val typeId = p.getStringOrNull("type")
            lateinit var entityTypeFilter: (Entity) -> Boolean
            if (typeId != null) {
                val identifier = net.minecraft.util.Identifier.tryParse(typeId)
                    ?: throw RpcException(RpcErrors.INVALID_PARAMS, "invalid entity type: $typeId")
                val key = net.minecraft.registry.RegistryKey.of(
                    net.minecraft.registry.RegistryKeys.ENTITY_TYPE, identifier
                )
                val resolved: net.minecraft.entity.EntityType<*> = server.registryManager
                    .get(net.minecraft.registry.RegistryKeys.ENTITY_TYPE)
                    .getOrEmpty(key)
                    .orElseThrow { RpcException(RpcErrors.INVALID_PARAMS, "entity type not registered: $typeId") }
                entityTypeFilter = { e: Entity -> e.type == resolved }
            } else {
                entityTypeFilter = { _: Entity -> true }
            }

            val entities = world.getOtherEntities(null, entityBox, entityTypeFilter)
            val results = JsonArray()
            for (entity in entities) {
                val entry = JsonObject().apply {
                    addProperty("type", Registries.ENTITY_TYPE.getId(entity.type).toString())
                    addProperty("uuid", entity.uuidAsString)
                    val pos = entity.pos
                    addProperty("x", pos.x)
                    addProperty("y", pos.y)
                    addProperty("z", pos.z)
                    if (entity is LivingEntity) {
                        addProperty("health", entity.health)
                        addProperty("maxHealth", entity.maxHealth)
                    }
                    if (includeNbt) {
                        val nbt = entity.writeNbt(net.minecraft.nbt.NbtCompound())
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

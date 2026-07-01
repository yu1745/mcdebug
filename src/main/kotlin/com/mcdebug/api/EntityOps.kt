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
import net.minecraft.entity.EntityType
import net.minecraft.entity.ItemEntity
import net.minecraft.entity.LivingEntity
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtDouble
import net.minecraft.nbt.NbtFloat
import net.minecraft.nbt.NbtList
import net.minecraft.network.packet.s2c.play.PositionFlag
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture

object EntityOps : RpcHandlerGroup {
    override fun methods(): Map<String, RpcHandler> = mapOf(
        "spawn" to ::spawn,
        "getNbt" to ::getNbt,
        "setNbt" to ::setNbt,
        "teleport" to ::teleport,
        "remove" to ::remove,
        "listItems" to ::listItems,
        "collectItems" to ::collectItems,
    )

    private fun spawn(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val typeId = p.requireString("type")
            val entityType = resolveEntityType(server, typeId)
            val entity = entityType.create(world)
                ?: throw RpcException(RpcErrors.INVALID_PARAMS, "entity type cannot be created or is disabled: $typeId")
            val pos = p.getVec3("pos")
            val yaw = p.getFloatOr("yaw", 0f)
            val pitch = p.getFloatOr("pitch", 0f)
            entity.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, pitch)
            applySpawnNbt(server, entity, typeId, pos, yaw, pitch, p.get("nbt"))
            if (entity is ItemEntity && p.has("stack") && !p.get("stack").isJsonNull) {
                val stackObj = p.requireObject("stack")
                val itemId = stackObj.getStringOrNull("item") ?: "minecraft:air"
                entity.setStack(ServerContext.itemStackFromJson(server, itemId, stackObj.getIntOr("count", 0), stackObj.get("nbt")))
            }
            val spawned = world.spawnEntity(entity)
            JsonObject().apply {
                addProperty("spawned", spawned)
                add("entity", entityToJson(entity, includeNbt = p.getBoolOrFalse("includeNbt")))
            }
        }

    private fun getNbt(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val (_, entity) = resolveEntity(server, p)
            JsonObject().apply {
                add("entity", entityToJson(entity, includeNbt = false))
                add("nbt", NbtJson.toJson(entity.writeNbt(NbtCompound())))
            }
        }

    private fun setNbt(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val (_, entity) = resolveEntity(server, p)
            val patch = p.get("nbt") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "nbt required")
            val patchNbt = NbtJson.fromJson(patch) as? NbtCompound
                ?: throw RpcException(RpcErrors.NBT_PARSE_ERROR, "nbt must be a JSON object")
            val replace = p.getBoolOrFalse("replace")
            val nbt = if (replace) patchNbt else entity.writeNbt(NbtCompound()).also { mergeNbt(it, patchNbt) }
            if (!replace) {
                nbt.putUuid("UUID", entity.uuid)
                nbt.putString("id", Registries.ENTITY_TYPE.getId(entity.type).toString())
            }
            entity.readNbt(nbt)
            JsonObject().apply { add("entity", entityToJson(entity, includeNbt = true)) }
        }

    private fun teleport(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val (currentWorld, entity) = resolveEntity(server, p)
            val targetWorld = ServerContext.world(server, p.getStringOrNull("toDim") ?: p.getStringOrNull("targetDim") ?: currentWorld.registryKey.value.toString())
            val pos = p.getVec3("pos")
            val yaw = p.getFloatOr("yaw", entity.yaw)
            val pitch = p.getFloatOr("pitch", entity.pitch)
            val ok = entity.teleport(targetWorld, pos.x, pos.y, pos.z, Collections.emptySet<PositionFlag>(), yaw, pitch)
            JsonObject().apply {
                addProperty("teleported", ok)
                add("entity", entityToJson(entity, includeNbt = p.getBoolOrFalse("includeNbt")))
            }
        }

    private fun remove(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val (_, entity) = resolveEntity(server, p)
            val before = entityToJson(entity, includeNbt = p.getBoolOrFalse("includeNbt"))
            entity.remove(Entity.RemovalReason.DISCARDED)
            JsonObject().apply {
                addProperty("removed", true)
                add("entity", before)
            }
        }

    private fun listItems(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val box = entityBoxFromParams(p)
            val item = p.getStringOrNull("item")
            val items = itemEntities(world, box, item)
            JsonObject().apply {
                addProperty("count", items.size)
                add("items", JsonArray().apply { items.forEach { add(entityToJson(it, includeNbt = p.getBoolOrFalse("includeNbt"))) } })
            }
        }

    private fun collectItems(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val box = entityBoxFromParams(p)
            val item = p.getStringOrNull("item")
            val remove = !p.has("remove") || p.get("remove").asBoolean
            val items = itemEntities(world, box, item)
            val json = JsonArray()
            for (entity in items) {
                json.add(entityToJson(entity, includeNbt = p.getBoolOrFalse("includeNbt")))
                if (remove) entity.discard()
            }
            JsonObject().apply {
                addProperty("count", items.size)
                addProperty("removed", remove)
                add("items", json)
            }
        }

    internal fun entityToJson(entity: Entity, includeNbt: Boolean): JsonObject =
        JsonObject().apply {
            addProperty("type", Registries.ENTITY_TYPE.getId(entity.type).toString())
            addProperty("uuid", entity.uuidAsString)
            addProperty("dim", entity.world.registryKey.value.toString())
            addProperty("x", entity.x)
            addProperty("y", entity.y)
            addProperty("z", entity.z)
            addProperty("yaw", entity.yaw)
            addProperty("pitch", entity.pitch)
            addProperty("removed", entity.isRemoved)
            if (entity is LivingEntity) {
                addProperty("health", entity.health)
                addProperty("maxHealth", entity.maxHealth)
            }
            if (entity is ItemEntity) {
                add("stack", ServerContext.itemStackToJson(entity.stack))
            }
            if (includeNbt) add("nbt", NbtJson.toJson(entity.writeNbt(NbtCompound())))
        }

    private fun resolveEntity(server: MinecraftServer, p: JsonObject): Pair<ServerWorld, Entity> {
        val uuid = try {
            UUID.fromString(p.getStringOrNull("uuid") ?: p.getStringOrNull("entityUuid") ?: "")
        } catch (_: IllegalArgumentException) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "invalid entity UUID")
        }
        val dim = p.getStringOrNull("dim")
        if (dim != null) {
            val world = ServerContext.world(server, dim)
            val entity = world.getEntity(uuid) ?: throwEntityNotFound(uuid)
            return world to entity
        }
        for (world in server.worlds) {
            val entity = world.getEntity(uuid)
            if (entity != null) return world to entity
        }
        throwEntityNotFound(uuid)
    }

    private fun resolveEntityType(server: MinecraftServer, typeId: String): EntityType<*> {
        val identifier = Identifier.tryParse(typeId)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "invalid entity type: $typeId")
        val key = RegistryKey.of(RegistryKeys.ENTITY_TYPE, identifier)
        return server.registryManager.get(RegistryKeys.ENTITY_TYPE).getOrEmpty(key).orElseThrow {
            RpcException(RpcErrors.INVALID_PARAMS, "entity type not registered: $typeId")
        }
    }

    private fun applySpawnNbt(
        server: MinecraftServer,
        entity: Entity,
        typeId: String,
        pos: Vec3,
        yaw: Float,
        pitch: Float,
        patchJson: JsonElement?,
    ) {
        if (patchJson == null || patchJson.isJsonNull) return
        val patch = NbtJson.fromJson(patchJson) as? NbtCompound
            ?: throw RpcException(RpcErrors.NBT_PARSE_ERROR, "nbt must be a JSON object")
        val nbt = entity.writeNbt(NbtCompound())
        mergeNbt(nbt, patch)
        nbt.putString("id", typeId)
        nbt.put("Pos", doubleList(pos.x, pos.y, pos.z))
        nbt.put("Rotation", floatList(yaw, pitch))
        if (!patch.containsUuid("UUID")) nbt.putUuid("UUID", entity.uuid)
        entity.readNbt(nbt)
    }

    private fun itemEntities(world: ServerWorld, box: Box, item: String?): List<ItemEntity> =
        world.getOtherEntities(null, box) { entity ->
            if (entity !is ItemEntity) return@getOtherEntities false
            item == null || Registries.ITEM.getId(entity.stack.item).toString() == item
        }.filterIsInstance<ItemEntity>()

    private fun entityBoxFromParams(p: JsonObject): Box {
        val boxObj = p.getAsJsonObject("box") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "box required")
        val from = ServerContext.pos(boxObj.getAsJsonArray("from"))
        val to = ServerContext.pos(boxObj.getAsJsonArray("to"))
        return Box(
            minOf(from.x, to.x).toDouble(),
            minOf(from.y, to.y).toDouble(),
            minOf(from.z, to.z).toDouble(),
            maxOf(from.x, to.x).toDouble() + 1.0,
            maxOf(from.y, to.y).toDouble() + 1.0,
            maxOf(from.z, to.z).toDouble() + 1.0,
        )
    }

    private fun JsonObject.getVec3(name: String): Vec3 {
        val arr = getAsJsonArray(name) ?: throw RpcException(RpcErrors.INVALID_PARAMS, "$name [x,y,z] required")
        if (arr.size() != 3) throw RpcException(RpcErrors.INVALID_PARAMS, "$name must have 3 elements")
        return Vec3(arr[0].asDouble, arr[1].asDouble, arr[2].asDouble)
    }

    private fun JsonObject.getFloatOr(name: String, default: Float): Float =
        if (has(name) && !get(name).isJsonNull) get(name).asFloat else default

    private fun mergeNbt(base: NbtCompound, patch: NbtCompound) {
        patch.keys.forEach { key -> base.put(key, patch[key]!!.copy()) }
    }

    private fun doubleList(x: Double, y: Double, z: Double): NbtList =
        NbtList().apply {
            add(NbtDouble.of(x))
            add(NbtDouble.of(y))
            add(NbtDouble.of(z))
        }

    private fun floatList(yaw: Float, pitch: Float): NbtList =
        NbtList().apply {
            add(NbtFloat.of(yaw))
            add(NbtFloat.of(pitch))
        }

    private fun throwEntityNotFound(uuid: UUID): Nothing {
        val data = JsonObject().apply {
            addProperty("reason", "ENTITY_NOT_FOUND")
            addProperty("uuid", uuid.toString())
        }
        throw RpcException(RpcErrors.ENTITY_NOT_FOUND, "entity not found: $uuid", data)
    }

    private data class Vec3(val x: Double, val y: Double, val z: Double)
}

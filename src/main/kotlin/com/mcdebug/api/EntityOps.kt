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
import net.minecraft.world.phys.Vec3
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.ListTag
import net.minecraft.world.entity.RelativeMovement
import net.minecraft.resources.ResourceKey
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.AABB
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
            val pos = p.requireVec3("pos")
            val yaw = p.getFloatOr("yaw", 0f)
            val pitch = p.getFloatOr("pitch", 0f)
            entity.moveTo(pos.x, pos.y, pos.z, yaw, pitch)
            applySpawnNbt(server, entity, typeId, pos, yaw, pitch, p.get("nbt"))
            if (entity is ItemEntity && p.has("stack") && !p.get("stack").isJsonNull) {
                val stackObj = p.requireObject("stack")
                val itemId = stackObj.getStringOrNull("item") ?: "minecraft:air"
                entity.setItem(ServerContext.itemStackFromJson(server, itemId, stackObj.getIntOr("count", 0), stackObj.get("nbt")))
            }
            val spawned = world.addFreshEntity(entity)
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
                add("nbt", NbtJson.toJson(entity.saveWithoutId(CompoundTag())))
            }
        }

    private fun setNbt(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val (_, entity) = resolveEntity(server, p)
            val patch = p.get("nbt") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "nbt required")
            val patchNbt = NbtJson.fromJson(patch) as? CompoundTag
                ?: throw RpcException(RpcErrors.NBT_PARSE_ERROR, "nbt must be a JSON object")
            val replace = p.getBoolOrFalse("replace")
            val nbt = if (replace) patchNbt else entity.saveWithoutId(CompoundTag()).also { mergeNbt(it, patchNbt) }
            if (!replace) {
                nbt.putUUID("UUID", entity.uuid)
                nbt.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString())
            }
            entity.load(nbt)
            JsonObject().apply { add("entity", entityToJson(entity, includeNbt = true)) }
        }

    private fun teleport(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val (currentWorld, entity) = resolveEntity(server, p)
            val targetWorld = ServerContext.world(server, p.getStringOrNull("toDim") ?: p.getStringOrNull("targetDim") ?: currentWorld.dimension().location().toString())
            val pos = p.requireVec3("pos")
            val yaw = p.getFloatOr("yaw", entity.yRot)
            val pitch = p.getFloatOr("pitch", entity.xRot)
            val ok = entity.teleportTo(targetWorld, pos.x, pos.y, pos.z, Collections.emptySet<RelativeMovement>(), yaw, pitch)
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
            val box = p.requireEntityBox()
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
            val box = p.requireEntityBox()
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
            addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString())
            addProperty("uuid", entity.getStringUUID())
            addProperty("dim", entity.level().dimension().location().toString())
            addProperty("x", entity.x)
            addProperty("y", entity.y)
            addProperty("z", entity.z)
            addProperty("yaw", entity.yRot)
            addProperty("pitch", entity.xRot)
            addProperty("removed", entity.isRemoved)
            if (entity is LivingEntity) {
                addProperty("health", entity.health)
                addProperty("maxHealth", entity.maxHealth)
            }
            if (entity is ItemEntity) {
                add("stack", ServerContext.itemStackToJson(entity.item))
            }
            if (includeNbt) add("nbt", NbtJson.toJson(entity.saveWithoutId(CompoundTag())))
        }

    private fun resolveEntity(server: MinecraftServer, p: JsonObject): Pair<ServerLevel, Entity> {
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
        for (world in server.getAllLevels()) {
            val entity = world.getEntity(uuid)
            if (entity != null) return world to entity
        }
        throwEntityNotFound(uuid)
    }

    private fun resolveEntityType(server: MinecraftServer, typeId: String): EntityType<*> {
        val identifier = ResourceLocation.tryParse(typeId)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "invalid entity type: $typeId")
        val key = ResourceKey.create(Registries.ENTITY_TYPE, identifier)
        return server.registryAccess().registryOrThrow(Registries.ENTITY_TYPE).get(key)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "entity type not registered: $typeId")
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
        val patch = NbtJson.fromJson(patchJson) as? CompoundTag
            ?: throw RpcException(RpcErrors.NBT_PARSE_ERROR, "nbt must be a JSON object")
        val nbt = entity.saveWithoutId(CompoundTag())
        mergeNbt(nbt, patch)
        nbt.putString("id", typeId)
        nbt.put("Pos", doubleList(pos.x, pos.y, pos.z))
        nbt.put("Rotation", floatList(yaw, pitch))
        if (!patch.hasUUID("UUID")) nbt.putUUID("UUID", entity.uuid)
        entity.load(nbt)
    }

    private fun itemEntities(world: ServerLevel, box: AABB, item: String?): List<ItemEntity> =
        world.getEntities(null, box) { entity ->
            if (entity !is ItemEntity) return@getEntities false
            item == null || BuiltInRegistries.ITEM.getKey(entity.item.item).toString() == item
        }.filterIsInstance<ItemEntity>()

    private fun JsonObject.getFloatOr(name: String, default: Float): Float =
        if (has(name) && !get(name).isJsonNull) get(name).asFloat else default

    private fun mergeNbt(base: CompoundTag, patch: CompoundTag) {
        patch.allKeys.forEach { key -> base.put(key, patch[key]!!.copy()) }
    }

    private fun doubleList(x: Double, y: Double, z: Double): ListTag =
        ListTag().apply {
            add(DoubleTag.valueOf(x))
            add(DoubleTag.valueOf(y))
            add(DoubleTag.valueOf(z))
        }

    private fun floatList(yaw: Float, pitch: Float): ListTag =
        ListTag().apply {
            add(FloatTag.valueOf(yaw))
            add(FloatTag.valueOf(pitch))
        }

    private fun throwEntityNotFound(uuid: UUID): Nothing {
        val data = JsonObject().apply {
            addProperty("reason", "ENTITY_NOT_FOUND")
            addProperty("uuid", uuid.toString())
        }
        throw RpcException(RpcErrors.ENTITY_NOT_FOUND, "entity not found: $uuid", data)
    }

}

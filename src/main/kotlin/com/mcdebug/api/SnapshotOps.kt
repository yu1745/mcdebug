package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.NbtJson
import com.mcdebug.util.ServerContext
import net.minecraft.entity.LivingEntity
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.Box
import net.minecraft.util.math.BlockPos
import java.util.concurrent.CompletableFuture

object SnapshotOps : RpcHandlerGroup {
    private val DEFAULT_KINDS = linkedSetOf(
        SnapshotKind.BLOCK,
        SnapshotKind.BLOCK_ENTITY_NBT,
        SnapshotKind.INVENTORY,
        SnapshotKind.FLUID,
        SnapshotKind.ENERGY,
        SnapshotKind.ENTITY,
    )

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "capture" to ::capture,
        "diff" to ::diff,
    )

    private fun capture(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val box = boxFromParams(p)
            val include = includeKinds(p)
            captureSnapshot(server, world, box, include)
        }

    private fun diff(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val before = p.get("before") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "before required")
            val after = p.get("after") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "after required")
            // ignoreMeta=true：过滤顶层 /tick、/time 等元数据字段后再 diff（快照必然随时间变化，
            // 不忽略的话 equal 永远为 false）。
            val ignoreMeta = p.getBoolOrFalse("ignoreMeta")
            diffJson(
                if (ignoreMeta) stripMeta(before) else before,
                if (ignoreMeta) stripMeta(after) else after,
            )
        }

    /** 去掉快照顶层元数据键（tick/time），不改动入参（返回拷贝）。 */
    internal fun stripMeta(el: JsonElement): JsonElement {
        if (!el.isJsonObject) return el.deepCopy()
        val out = JsonObject()
        el.asJsonObject.entrySet().forEach { (k, v) ->
            if (k == "tick" || k == "time") return@forEach
            out.add(k, v)
        }
        return out
    }

    internal fun captureSnapshot(
        server: MinecraftServer,
        world: ServerWorld,
        box: BlockBox,
        include: Set<SnapshotKind>,
    ): JsonObject {
        ensureChunksLoaded(world, box)
        return JsonObject().apply {
            addProperty("dim", world.registryKey.value.toString())
            addProperty("tick", server.ticks)
            addProperty("time", world.time)
            add("box", boxToJson(box))
            add("include", JsonArray().apply { include.forEach { add(it.wireName) } })
            if (include.any { it != SnapshotKind.ENTITY }) {
                add("blocks", captureBlocks(world, box, include))
            }
            if (SnapshotKind.ENTITY in include) {
                add("entities", captureEntities(world, box))
            }
        }
    }

    internal fun includeKinds(p: JsonObject?): Set<SnapshotKind> {
        if (p == null || !p.has("include") || p.get("include").isJsonNull) return DEFAULT_KINDS
        val arr = p.getAsJsonArray("include")
        val out = linkedSetOf<SnapshotKind>()
        arr.forEach { el ->
            val kind = SnapshotKind.fromWire(el.asString)
            out.add(kind)
        }
        return out
    }

    private fun captureBlocks(world: ServerWorld, box: BlockBox, include: Set<SnapshotKind>): JsonArray =
        JsonArray().apply {
            BlockPos.iterate(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ).forEach { mutablePos ->
                val pos = BlockPos(mutablePos)
                val state = world.getBlockState(pos)
                val be = world.getBlockEntity(pos)
                add(JsonObject().apply {
                    add("pos", ServerContext.posAsJson(pos))
                    if (SnapshotKind.BLOCK in include) {
                        add("state", ServerContext.blockStateToJson(state))
                    }
                    if (SnapshotKind.BLOCK_ENTITY_NBT in include && be != null) {
                        add("blockEntityNbt", NbtJson.toJson(be.createNbt()))
                    }
                    if ((SnapshotKind.INVENTORY in include || SnapshotKind.FLUID in include || SnapshotKind.ENERGY in include) && be != null) {
                        val resources = StorageOps.blockResourcesToJson(world, pos)
                        val filtered = filterResources(resources, include)
                        add("storage", filtered)
                    }
                })
            }
        }

    private fun filterResources(resources: JsonObject, include: Set<SnapshotKind>): JsonObject =
        JsonObject().apply {
            val handles = JsonArray()
            val details = JsonArray()
            resources.getAsJsonArray("handles")?.forEach { el ->
                val obj = el.asJsonObject
                if (resourceKindIncluded(obj.get("kind").asString, include)) handles.add(obj)
            }
            resources.getAsJsonArray("resources")?.forEach { el ->
                val obj = el.asJsonObject
                if (resourceKindIncluded(obj.get("kind").asString, include)) details.add(obj)
            }
            add("handles", handles)
            add("resources", details)
        }

    private fun resourceKindIncluded(kind: String, include: Set<SnapshotKind>): Boolean =
        when (kind) {
            "item" -> SnapshotKind.INVENTORY in include
            "fluid" -> SnapshotKind.FLUID in include
            "energy" -> SnapshotKind.ENERGY in include
            else -> false
        }

    private fun captureEntities(world: ServerWorld, box: BlockBox): JsonArray {
        val query = Box(
            box.minX.toDouble(), box.minY.toDouble(), box.minZ.toDouble(),
            (box.maxX + 1).toDouble(), (box.maxY + 1).toDouble(), (box.maxZ + 1).toDouble()
        )
        return JsonArray().apply {
            world.getOtherEntities(null, query).forEach { entity ->
                add(JsonObject().apply {
                    addProperty("type", Registries.ENTITY_TYPE.getId(entity.type).toString())
                    addProperty("uuid", entity.uuidAsString)
                    addProperty("id", entity.id)
                    add("pos", JsonArray().apply {
                        add(entity.x)
                        add(entity.y)
                        add(entity.z)
                    })
                    if (entity is LivingEntity) {
                        addProperty("health", entity.health)
                        addProperty("maxHealth", entity.maxHealth)
                    }
                    add("nbt", NbtJson.toJson(entity.writeNbt(net.minecraft.nbt.NbtCompound())))
                })
            }
        }
    }

    private fun boxToJson(box: BlockBox): JsonObject =
        JsonObject().apply {
            add("from", JsonArray().apply { add(box.minX); add(box.minY); add(box.minZ) })
            add("to", JsonArray().apply { add(box.maxX); add(box.maxY); add(box.maxZ) })
        }

    private fun diffJson(before: JsonElement, after: JsonElement): JsonObject {
        val changes = JsonArray()
        collectDiff("", before, after, changes)
        return JsonObject().apply {
            addProperty("equal", changes.size() == 0)
            addProperty("changeCount", changes.size())
            add("changes", changes)
        }
    }

    private fun collectDiff(path: String, before: JsonElement?, after: JsonElement?, changes: JsonArray) {
        if (before == null && after == null) return
        if (before == null || after == null) {
            changes.add(change(path, before ?: JsonNull.INSTANCE, after ?: JsonNull.INSTANCE))
            return
        }
        if (before == after) return

        if (before.isJsonObject && after.isJsonObject) {
            val keys = linkedSetOf<String>()
            before.asJsonObject.entrySet().forEach { keys.add(it.key) }
            after.asJsonObject.entrySet().forEach { keys.add(it.key) }
            for (key in keys) {
                collectDiff(joinPath(path, key), before.asJsonObject.get(key), after.asJsonObject.get(key), changes)
            }
            return
        }

        if (before.isJsonArray && after.isJsonArray) {
            val max = maxOf(before.asJsonArray.size(), after.asJsonArray.size())
            for (i in 0 until max) {
                val b = if (i < before.asJsonArray.size()) before.asJsonArray[i] else null
                val a = if (i < after.asJsonArray.size()) after.asJsonArray[i] else null
                collectDiff("$path/$i", b, a, changes)
            }
            return
        }

        changes.add(change(path, before, after))
    }

    private fun change(path: String, before: JsonElement, after: JsonElement): JsonObject =
        JsonObject().apply {
            addProperty("path", if (path.isEmpty()) "/" else path)
            add("before", before)
            add("after", after)
        }

    private fun joinPath(base: String, key: String): String =
        if (base.isEmpty()) "/${escapePath(key)}" else "$base/${escapePath(key)}"

    private fun escapePath(value: String): String =
        value.replace("~", "~0").replace("/", "~1")
}

internal enum class SnapshotKind(val wireName: String) {
    BLOCK("block"),
    BLOCK_ENTITY_NBT("blockEntityNbt"),
    INVENTORY("inventory"),
    FLUID("fluid"),
    ENERGY("energy"),
    ENTITY("entity");

    companion object {
        fun fromWire(value: String): SnapshotKind =
            entries.firstOrNull { it.wireName == value }
                ?: throw RpcException(
                    RpcErrors.INVALID_PARAMS,
                    "unknown snapshot kind: $value (allowed: ${entries.joinToString { it.wireName }})"
                )
    }
}

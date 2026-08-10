package com.mcdebug.cli

import com.google.gson.JsonElement

/**
 * 类型化 API（对照 TS 版 api.ts 全量迁移）。
 * 所有方法参数即 JSON 参数名，null 参数不发送；返回原始 JsonElement，
 * 调用方自行取字段（命令输出）或反序列化为 contract DTO（SDK 用户）。
 */

private fun params(block: MutableMap<String, JsonElement>.() -> Unit): JsonElement {
    val m = LinkedHashMap<String, JsonElement>()
    block(m)
    return gson.toJsonTree(m)
}

private fun MutableMap<String, JsonElement>.p(name: String, v: Any?) {
    if (v != null) put(name, gson.toJsonTree(v))
}

typealias Pos = List<Int>
typealias Box = Map<String, Any>

class WorldApi(private val rpc: RpcClient) {
    fun getBlock(pos: Pos, dim: String? = null, includeNbt: Boolean? = null): JsonElement =
        rpc.call("world.getBlock", params { p("pos", pos); p("dim", dim); p("includeNbt", includeNbt) })

    fun setBlock(pos: Pos, block: String, stateProps: Map<String, String>? = null, flags: Int? = null, dim: String? = null): JsonElement =
        rpc.call("world.setBlock", params {
            p("pos", pos); p("block", block)
            if (stateProps != null) p("stateProps", stateProps)
            p("flags", flags); p("dim", dim)
        })

    fun setBlocks(ops: List<Map<String, Any?>>, flags: Int? = null, dim: String? = null): JsonElement =
        rpc.call("world.setBlocks", params {
            p("ops", ops); p("flags", flags); p("dim", dim)
        })

    fun fillBox(box: Box, block: String, stateProps: Map<String, String>? = null, flags: Int? = null, dim: String? = null, maxBlocks: Int? = null): JsonElement =
        rpc.call("world.fillBox", params {
            p("box", box); p("block", block); p("stateProps", stateProps)
            p("flags", flags); p("dim", dim); p("maxBlocks", maxBlocks)
        })

    fun clearBox(box: Box, flags: Int? = null, dim: String? = null, maxBlocks: Int? = null): JsonElement =
        rpc.call("world.clearBox", params { p("box", box); p("flags", flags); p("dim", dim); p("maxBlocks", maxBlocks) })

    fun placeAsPlayer(pos: Pos, block: String, face: String, neighbor: Pos? = null, playerFacing: String? = null, nbt: Any? = null, dim: String? = null): JsonElement =
        rpc.call("world.placeAsPlayer", params {
            p("pos", pos); p("block", block); p("face", face)
            p("neighbor", neighbor); p("playerFacing", playerFacing); p("nbt", nbt); p("dim", dim)
        })

    fun getRegion(box: Box, includeNbt: Boolean? = null, dim: String? = null): JsonElement =
        rpc.call("world.getRegion", params { p("box", box); p("includeNbt", includeNbt); p("dim", dim) })

    fun selectBlocks(box: Box, block: String? = null, includeNbt: Boolean? = null, dim: String? = null): JsonElement =
        rpc.call("world.selectBlocks", params {
            p("box", box)
            if (block != null) p("predicate", mapOf("block" to block))
            p("includeNbt", includeNbt); p("dim", dim)
        })

    fun forceloadChunk(cx: Int, cz: Int, dim: String? = null): JsonElement =
        rpc.call("world.forceloadChunk", params { p("chunk", listOf(cx, cz)); p("dim", dim) })

    fun unforceloadChunk(cx: Int, cz: Int, dim: String? = null): JsonElement =
        rpc.call("world.unforceloadChunk", params { p("chunk", listOf(cx, cz)); p("dim", dim) })

    fun useOnBlock(pos: Pos, face: String, item: String? = null, count: Int? = null, nbt: Any? = null, sneaking: Boolean? = null, playerFacing: String? = null, gamemode: String? = null, dim: String? = null): JsonElement =
        rpc.call("world.useOnBlock", params {
            p("pos", pos); p("face", face); p("item", item); p("count", count); p("nbt", nbt)
            p("sneaking", sneaking); p("playerFacing", playerFacing); p("gamemode", gamemode); p("dim", dim)
        })

    fun useItem(item: String, count: Int? = null, nbt: Any? = null, sneaking: Boolean? = null, dim: String? = null): JsonElement =
        rpc.call("world.useItem", params { p("item", item); p("count", count); p("nbt", nbt); p("sneaking", sneaking); p("dim", dim) })

    fun useItemHold(item: String, count: Int? = null, nbt: Any? = null, ammo: String? = null, ammoCount: Int? = null, targetUuid: String? = null, direction: String? = null, holdTicks: Int? = null, repeat: Int? = null, playerPos: Pos? = null, dim: String? = null): JsonElement =
        rpc.call("world.useItemHold", params {
            p("item", item); p("count", count); p("nbt", nbt); p("ammo", ammo); p("ammoCount", ammoCount)
            p("targetUuid", targetUuid); p("direction", direction); p("holdTicks", holdTicks)
            p("repeat", repeat); p("playerPos", playerPos); p("dim", dim)
        })

    fun attackBlock(pos: Pos, face: String, item: String? = null, count: Int? = null, nbt: Any? = null, armor: Any? = null, gamemode: String? = null, dim: String? = null): JsonElement =
        rpc.call("world.attackBlock", params {
            p("pos", pos); p("face", face); p("item", item); p("count", count); p("nbt", nbt)
            p("armor", armor); p("gamemode", gamemode); p("dim", dim)
        })

    fun interactEntity(entityUuid: String, item: String? = null, count: Int? = null, nbt: Any? = null, sneaking: Boolean? = null, playerFacing: String? = null, dim: String? = null): JsonElement =
        rpc.call("world.interactEntity", params {
            p("entityUuid", entityUuid); p("item", item); p("count", count); p("nbt", nbt)
            p("sneaking", sneaking); p("playerFacing", playerFacing); p("dim", dim)
        })

    fun attackEntity(entityUuid: String, item: String? = null, count: Int? = null, nbt: Any? = null, armor: Any? = null, playerFacing: String? = null, dim: String? = null): JsonElement =
        rpc.call("world.attackEntity", params {
            p("entityUuid", entityUuid); p("item", item); p("count", count); p("nbt", nbt)
            p("armor", armor); p("playerFacing", playerFacing); p("dim", dim)
        })
}

class BeApi(private val rpc: RpcClient) {
    fun getNbt(pos: Pos, dim: String? = null): JsonElement =
        rpc.call("be.getNbt", params { p("pos", pos); p("dim", dim) })

    fun setNbt(pos: Pos, nbt: Any, dim: String? = null): JsonElement =
        rpc.call("be.setNbt", params { p("pos", pos); p("nbt", nbt); p("dim", dim) })

    fun getField(pos: Pos, path: String, dim: String? = null): JsonElement =
        rpc.call("be.getField", params { p("pos", pos); p("path", path); p("dim", dim) })

    fun setField(pos: Pos, path: String, value: Any, dim: String? = null): JsonElement =
        rpc.call("be.setField", params { p("pos", pos); p("path", path); p("value", value); p("dim", dim) })
}

class InvApi(private val rpc: RpcClient) {
    fun getSize(pos: Pos, dim: String? = null): JsonElement =
        rpc.call("inv.getSize", params { p("pos", pos); p("dim", dim) })

    fun getSlot(pos: Pos, slot: Int, dim: String? = null): JsonElement =
        rpc.call("inv.getSlot", params { p("pos", pos); p("slot", slot); p("dim", dim) })

    fun setSlot(pos: Pos, slot: Int, item: String?, count: Int, nbt: Any? = null, dim: String? = null): JsonElement =
        rpc.call("inv.setSlot", params { p("pos", pos); p("slot", slot); p("item", item); p("count", count); p("nbt", nbt); p("dim", dim) })

    fun insert(pos: Pos, item: String, count: Int, slot: Int? = null, nbt: Any? = null, simulate: Boolean? = null, dim: String? = null): JsonElement =
        rpc.call("inv.insert", params { p("pos", pos); p("item", item); p("count", count); p("slot", slot); p("nbt", nbt); p("simulate", simulate); p("dim", dim) })

    fun extract(pos: Pos, item: String, count: Int, slot: Int? = null, simulate: Boolean? = null, dim: String? = null): JsonElement =
        rpc.call("inv.extract", params { p("pos", pos); p("item", item); p("count", count); p("slot", slot); p("simulate", simulate); p("dim", dim) })
}

class StorageApi(private val rpc: RpcClient) {
    fun list(target: Any, side: String? = null): JsonElement =
        rpc.call("storage.list", params { p("target", target); p("side", side) })

    fun get(target: Any, handle: String, side: String? = null): JsonElement =
        rpc.call("storage.get", params { p("target", target); p("handle", handle); p("side", side) })

    fun insert(target: Any, handle: String, resource: Any, amount: Long, side: String? = null, simulate: Boolean? = null): JsonElement =
        rpc.call("storage.insert", params { p("target", target); p("handle", handle); p("resource", resource); p("amount", amount); p("side", side); p("simulate", simulate) })

    fun extract(target: Any, handle: String, resource: Any, amount: Long, side: String? = null, simulate: Boolean? = null): JsonElement =
        rpc.call("storage.extract", params { p("target", target); p("handle", handle); p("resource", resource); p("amount", amount); p("side", side); p("simulate", simulate) })

    fun transfer(from: Any, to: Any, resource: Any, amount: Long, fromSide: String? = null, toSide: String? = null, simulate: Boolean? = null): JsonElement =
        rpc.call("storage.transfer", params { p("from", from); p("to", to); p("resource", resource); p("amount", amount); p("fromSide", fromSide); p("toSide", toSide); p("simulate", simulate) })
}

class SnapshotApi(private val rpc: RpcClient) {
    fun capture(options: Map<String, Any?>): JsonElement =
        rpc.call("snapshot.capture", gson.toJsonTree(options))

    fun diff(before: Any, after: Any): JsonElement =
        rpc.call("snapshot.diff", params { p("before", before); p("after", after) })
}

class TraceApi(private val rpc: RpcClient) {
    fun start(options: Map<String, Any?>): JsonElement =
        rpc.call("trace.start", gson.toJsonTree(options))

    fun stop(traceId: String): JsonElement =
        rpc.call("trace.stop", params { p("traceId", traceId) })

    fun get(traceId: String): JsonElement =
        rpc.call("trace.get", params { p("traceId", traceId) })
}

class ScreenApi(private val rpc: RpcClient) {
    fun openBlock(pos: Pos, dim: String? = null, player: String? = null, side: String? = null): JsonElement =
        rpc.call("screen.openBlock", params { p("pos", pos); p("dim", dim); p("player", player); p("side", side) })

    fun snapshot(screenId: String): JsonElement =
        rpc.call("screen.snapshot", params { p("screenId", screenId) })

    fun setPlayerSlot(screenId: String, slot: Int, stack: Any): JsonElement =
        rpc.call("screen.setPlayerSlot", params { p("screenId", screenId); p("slot", slot); p("stack", stack) })

    fun clickSlot(screenId: String, slot: Int, button: Int, actionType: String): JsonElement =
        rpc.call("screen.clickSlot", params { p("screenId", screenId); p("slot", slot); p("button", button); p("actionType", actionType) })

    fun quickMove(screenId: String, slot: Int): JsonElement =
        rpc.call("screen.quickMove", params { p("screenId", screenId); p("slot", slot) })

    fun close(screenId: String): JsonElement =
        rpc.call("screen.close", params { p("screenId", screenId) })
}

class RedstoneApi(private val rpc: RpcClient) {
    fun getPower(pos: Pos, side: String? = null, dim: String? = null): JsonElement =
        rpc.call("redstone.getPower", params { p("pos", pos); p("side", side); p("dim", dim) })

    fun isPowered(pos: Pos, dim: String? = null): JsonElement =
        rpc.call("redstone.isPowered", params { p("pos", pos); p("dim", dim) })

    fun setLever(pos: Pos, powered: Boolean, dim: String? = null): JsonElement =
        rpc.call("redstone.setLever", params { p("pos", pos); p("powered", powered); p("dim", dim) })

    fun pulse(pos: Pos, ticks: Int = 2, dim: String? = null): JsonElement =
        rpc.call("redstone.pulse", params { p("pos", pos); p("ticks", ticks); p("dim", dim) })

    fun notifyNeighbors(pos: Pos, dim: String? = null): JsonElement =
        rpc.call("redstone.notifyNeighbors", params { p("pos", pos); p("dim", dim) })
}

class EntityApi(private val rpc: RpcClient) {
    fun spawn(type: String, pos: Pos, dim: String? = null, yaw: Float? = null, pitch: Float? = null, nbt: Any? = null, stack: Any? = null, includeNbt: Boolean? = null): JsonElement =
        rpc.call("entity.spawn", params { p("type", type); p("pos", pos); p("dim", dim); p("yaw", yaw); p("pitch", pitch); p("nbt", nbt); p("stack", stack); p("includeNbt", includeNbt) })

    fun getNbt(uuid: String, dim: String? = null): JsonElement =
        rpc.call("entity.getNbt", params { p("uuid", uuid); p("dim", dim) })

    fun setNbt(uuid: String, nbt: Any, dim: String? = null, replace: Boolean? = null): JsonElement =
        rpc.call("entity.setNbt", params { p("uuid", uuid); p("nbt", nbt); p("dim", dim); p("replace", replace) })

    fun teleport(uuid: String, pos: Pos, dim: String? = null, toDim: String? = null, yaw: Float? = null, pitch: Float? = null, includeNbt: Boolean? = null): JsonElement =
        rpc.call("entity.teleport", params { p("uuid", uuid); p("pos", pos); p("dim", dim); p("toDim", toDim); p("yaw", yaw); p("pitch", pitch); p("includeNbt", includeNbt) })

    fun remove(uuid: String, dim: String? = null, includeNbt: Boolean? = null): JsonElement =
        rpc.call("entity.remove", params { p("uuid", uuid); p("dim", dim); p("includeNbt", includeNbt) })

    fun listItems(box: Box, dim: String? = null, item: String? = null, includeNbt: Boolean? = null): JsonElement =
        rpc.call("entity.listItems", params { p("box", box); p("dim", dim); p("item", item); p("includeNbt", includeNbt) })

    fun collectItems(box: Box, dim: String? = null, item: String? = null, remove: Boolean? = null, includeNbt: Boolean? = null): JsonElement =
        rpc.call("entity.collectItems", params { p("box", box); p("dim", dim); p("item", item); p("remove", remove); p("includeNbt", includeNbt) })
}

class FixtureApi(private val rpc: RpcClient) {
    fun capture(box: Box, dim: String? = null, includeNbt: Boolean? = null): JsonElement =
        rpc.call("fixture.capture", params { p("box", box); p("dim", dim); p("includeNbt", includeNbt) })

    fun load(fixture: Any, origin: Pos? = null, dim: String? = null, flags: Int? = null): JsonElement =
        rpc.call("fixture.load", params { p("fixture", fixture); p("origin", origin); p("dim", dim); p("flags", flags) })
}

class FluidApi(private val rpc: RpcClient) {
    fun info(pos: Pos, side: String? = null, dim: String? = null): JsonElement =
        rpc.call("fluid.info", params { p("pos", pos); p("side", side); p("dim", dim) })

    fun get(pos: Pos, side: String? = null, index: Int? = null, dim: String? = null): JsonElement =
        rpc.call("fluid.get", params { p("pos", pos); p("side", side); p("index", index); p("dim", dim) })

    fun insert(pos: Pos, fluid: String, amount: Long, side: String? = null, index: Int? = null, dim: String? = null): JsonElement =
        rpc.call("fluid.insert", params { p("pos", pos); p("side", side); p("index", index); p("fluid", fluid); p("amount", amount); p("dim", dim) })

    fun extract(pos: Pos, amount: Long, side: String? = null, index: Int? = null, dim: String? = null): JsonElement =
        rpc.call("fluid.extract", params { p("pos", pos); p("side", side); p("index", index); p("amount", amount); p("dim", dim) })
}

class WaitApi(private val rpc: RpcClient) {
    /** 被动等待谓词为真（服务端自然 tick 求值），不推进 tick。 */
    fun until(predicate: String, timeoutTicks: Int? = null, pollIntervalTicks: Int? = null): JsonElement =
        rpc.call("wait.until", params { p("predicate", predicate); p("timeoutTicks", timeoutTicks); p("pollIntervalTicks", pollIntervalTicks) })
}

class CraftApi(private val rpc: RpcClient) {
    fun craft(grid: List<Any?>, recipeId: String? = null, dim: String? = null): JsonElement =
        rpc.call("craft.craft", params { p("grid", grid); p("recipeId", recipeId); p("dim", dim) })

    fun find(grid: List<Any?>, dim: String? = null): JsonElement =
        rpc.call("craft.find", params { p("grid", grid); p("dim", dim) })
}

class ScanApi(private val rpc: RpcClient) {
    fun findBlocks(box: Box, block: String, count: Boolean? = null, dim: String? = null): JsonElement =
        rpc.call("scan.findBlocks", params { p("box", box); p("block", block); p("count", count); p("dim", dim) })

    fun countByBlock(box: Box, dim: String? = null): JsonElement =
        rpc.call("scan.countByBlock", params { p("box", box); p("dim", dim) })

    fun findEntities(box: Box, type: String? = null, includeNbt: Boolean? = null, dim: String? = null): JsonElement =
        rpc.call("scan.findEntities", params { p("box", box); p("type", type); p("includeNbt", includeNbt); p("dim", dim) })
}

class ServerApi(private val rpc: RpcClient) {
    fun status(): JsonElement = rpc.call("server.status")
    fun listDimensions(): JsonElement = rpc.call("server.listDimensions")
    fun runCommand(command: String, dim: String? = null): JsonElement =
        rpc.call("server.runCommand", params { p("command", command); p("dim", dim) })
}

class DebugApi(val rpc: RpcClient) {
    val world = WorldApi(rpc)
    val be = BeApi(rpc)
    val inv = InvApi(rpc)
    val storage = StorageApi(rpc)
    val snapshot = SnapshotApi(rpc)
    val trace = TraceApi(rpc)
    val screen = ScreenApi(rpc)
    val redstone = RedstoneApi(rpc)
    val entity = EntityApi(rpc)
    val fixture = FixtureApi(rpc)
    val fluid = FluidApi(rpc)
    val wait = WaitApi(rpc)
    val craft = CraftApi(rpc)
    val scan = ScanApi(rpc)
    val server = ServerApi(rpc)
}

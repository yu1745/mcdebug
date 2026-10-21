package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.ServerContext
import com.mojang.authlib.GameProfile
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.chat.Component
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import java.util.UUID
import java.util.concurrent.CompletableFuture

object ScreenOps : RpcHandlerGroup {
    private val sessions = LinkedHashMap<String, ScreenSession>()

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "openBlock" to ::openBlock,
        "snapshot" to ::snapshot,
        "setPlayerSlot" to ::setPlayerSlot,
        "clickSlot" to ::clickSlot,
        "quickMove" to ::quickMove,
        "close" to ::close,
    )

    fun clear() {
        sessions.values.toList().forEach { session ->
            runCatching { closeSession(session) }
        }
        sessions.clear()
    }

    private fun openBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val side = StorageOps.parseSide(p.getAsJsonObject("opts")?.getStringOrNull("side") ?: p.getStringOrNull("side"))
            val state = world.getBlockState(pos)
            val factory = state.getMenuProvider(world, pos)
                ?: throwScreenNotFound("no screen handler factory at $pos", null)

            val sessionUuid = UUID.randomUUID()
            val player = FakePlayerPool.create(world, GameProfile(sessionUuid, "[mcdebug_screen]"))
            positionPlayerForScreen(player, pos, side)

            closeSessionsForPlayer(player)
            val opened = player.openMenu(factory)
            if (opened.isEmpty) {
                throwScreenNotFound("screen handler factory returned empty at $pos", null)
            }

            val handler = player.containerMenu
            val id = UUID.randomUUID().toString()
            val title = factory.displayName ?: Component.empty()
            val session = ScreenSession(id, player, handler, title, world.dimension().location().toString(), pos)
            sessions[id] = session
            screenSnapshot(session)
        }

    private fun snapshot(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val session = session(params)
            screenSnapshot(session)
        }

    private fun setPlayerSlot(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val session = session(p)
            val slot = p.requireLong("slot").toInt()
            if (slot !in 0 until 36) {
                throw RpcException(RpcErrors.INVALID_PARAMS, "player inventory slot must be in 0..35, got $slot")
            }
            val stack = itemStackFromJson(server, p.requireObject("stack"))
            requireUsable(session)
            session.player.inventory.setItem(slot, stack)
            session.player.inventory.setChanged()
            session.handler.broadcastChanges()
            screenSnapshot(session)
        }

    private fun clickSlot(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val session = session(p)
            val slot = p.requireLong("slot").toInt()
            val button = p.getIntOr("button", 0)
            val actionType = parseActionType(p.requireString("actionType"))
            requireUsable(session)
            session.handler.clicked(slot, button, actionType, session.player)
            session.handler.broadcastChanges()
            screenSnapshot(session)
        }

    private fun quickMove(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val session = session(p)
            val slot = p.requireLong("slot").toInt()
            requireUsable(session)
            session.handler.clicked(slot, 0, ClickType.QUICK_MOVE, session.player)
            session.handler.broadcastChanges()
            screenSnapshot(session)
        }

    private fun close(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val session = session(params)
            sessions.remove(session.id)
            closeSession(session)
            JsonObject().apply {
                addProperty("screenId", session.id)
                addProperty("closed", true)
            }
        }

    private fun screenSnapshot(session: ScreenSession): JsonObject =
        JsonObject().apply {
            session.handler.broadcastChanges()
            addProperty("screenId", session.id)
            addProperty("title", session.title.string)
            addProperty("handlerType", handlerTypeId(session.handler))
            addProperty("syncId", session.handler.containerId)
            addProperty("dim", session.dim)
            add("pos", ServerContext.posAsJson(session.pos))
            add("slots", JsonArray().apply {
                session.handler.slots.forEach { slot ->
                    add(ServerContext.itemStackToJson(slot.item))
                }
            })
            add("slotDetails", JsonArray().apply {
                session.handler.slots.forEachIndexed { index, slot ->
                    add(JsonObject().apply {
                        addProperty("index", index)
                        addProperty("id", slot.index)
                        addProperty("x", slot.x)
                        addProperty("y", slot.y)
                        addProperty("canTake", runCatching { slot.mayPickup(session.player) }.getOrDefault(false))
                        addProperty("canInsert", runCatching { slot.mayPlace(slot.item) }.getOrDefault(false))
                        add("stack", ServerContext.itemStackToJson(slot.item))
                    })
                }
            })
            add("cursor", ServerContext.itemStackToJson(session.handler.carried))
            add("properties", JsonArray().apply {
                screenHandlerProperties(session.handler).forEach { add(it) }
            })
        }

    private fun closeSession(session: ScreenSession) {
        if (session.player.containerMenu == session.handler) {
            session.player.closeContainer()
        } else {
            session.handler.removed(session.player)
        }
        // The session player was spawned into the world (FakePlayerPool.create) —
        // remove it so it stops ticking.
        FakePlayerPool.discard(session.player)
    }

    private fun closeSessionsForPlayer(player: ServerPlayer) {
        val ids = sessions.values.filter { it.player == player }.map { it.id }
        for (id in ids) {
            val session = sessions.remove(id) ?: continue
            closeSession(session)
        }
    }

    private fun session(params: JsonObject?): ScreenSession {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
        val id = p.requireString("screenId")
        return sessions[id] ?: throwScreenNotFound("screen not found: $id", id)
    }

    private fun requireUsable(session: ScreenSession) {
        if (session.player.containerMenu != session.handler) {
            throwScreenNotFound("screen is no longer the player's current handler: ${session.id}", session.id)
        }
        if (!session.handler.stillValid(session.player)) {
            throwScreenNotFound("screen can no longer be used: ${session.id}", session.id)
        }
    }

    private fun handlerTypeId(handler: AbstractContainerMenu): String? {
        val type = runCatching { handler.type }.getOrNull() ?: return null
        return BuiltInRegistries.MENU.getKey(type)?.toString()
    }

    private fun positionPlayerForScreen(player: ServerPlayer, pos: BlockPos, side: Direction?) {
        val face = side ?: Direction.NORTH
        player.moveTo(
            pos.x + 0.5 + face.stepX * 0.75,
            pos.y + 0.5 + face.stepY * 0.75,
            pos.z + 0.5 + face.stepZ * 0.75,
            yawFor(face.opposite),
            pitchFor(face.opposite),
        )
    }

    private fun yawFor(facing: Direction): Float = when (facing) {
        Direction.SOUTH -> 0f
        Direction.WEST -> 90f
        Direction.NORTH -> 180f
        Direction.EAST -> -90f
        Direction.UP, Direction.DOWN -> 0f
    }

    private fun pitchFor(facing: Direction): Float = when (facing) {
        Direction.UP -> -90f
        Direction.DOWN -> 90f
        else -> 0f
    }

    private fun parseActionType(value: String): ClickType =
        runCatching { ClickType.valueOf(value.uppercase()) }
            .getOrElse {
                throw RpcException(
                    RpcErrors.INVALID_PARAMS,
                    "invalid actionType: $value (allowed: ${ClickType.entries.joinToString { it.name.lowercase() }})"
                )
            }

    private fun itemStackFromJson(server: MinecraftServer, obj: JsonObject) =
        ServerContext.itemStackFromJson(
            server,
            obj.getStringOrNull("item") ?: "minecraft:air",
            obj.getIntOr("count", 0),
            obj.get("nbt"),
        )

    private fun throwScreenNotFound(message: String, screenId: String?): Nothing {
        val data = JsonObject().apply {
            addProperty("reason", "SCREEN_NOT_FOUND")
            if (screenId != null) addProperty("screenId", screenId)
        }
        throw RpcException(RpcErrors.SCREEN_NOT_FOUND, message, data)
    }

    private data class ScreenSession(
        val id: String,
        val player: ServerPlayer,
        val handler: AbstractContainerMenu,
        val title: Component,
        val dim: String,
        val pos: BlockPos,
    )
}

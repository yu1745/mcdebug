package com.mcdebug.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.api.player.BlockPlayerActions
import com.mcdebug.api.player.EntityPlayerActions
import com.mcdebug.api.player.ItemPlayerActions
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture

/**
 * `world.*` handlers that act as a (fake) player. Registered by [WorldOps].
 *
 * Thin facade only — the implementations live in `api/player/`, grouped by
 * gesture: [BlockPlayerActions] (place/use-on/break/attack block),
 * [ItemPlayerActions] (air use, hold-and-release ranged weapons),
 * [EntityPlayerActions] (interact/attack entity). Shared fake-player plumbing
 * (armor, positioning, reflection bridges) is in
 * [com.mcdebug.api.player.PlayerActionSupport].
 */
internal object WorldPlayerActions {

    fun placeAsPlayer(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        BlockPlayerActions.placeAsPlayer(server, params)

    fun useOnBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        BlockPlayerActions.useOnBlock(server, params)

    fun breakBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        BlockPlayerActions.breakBlock(server, params)

    fun attackBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        BlockPlayerActions.attackBlock(server, params)

    fun useItem(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        ItemPlayerActions.useItem(server, params)

    fun useItemHold(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        ItemPlayerActions.useItemHold(server, params)

    fun interactEntity(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        EntityPlayerActions.interactEntity(server, params)

    fun attackEntity(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        EntityPlayerActions.attackEntity(server, params)
}
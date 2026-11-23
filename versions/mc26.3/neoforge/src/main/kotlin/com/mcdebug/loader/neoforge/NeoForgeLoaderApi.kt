package com.mcdebug.loader.neoforge

import com.mcdebug.loader.LoaderApi
import com.mcdebug.loader.MojangRuntimeNames
import com.mcdebug.loader.RuntimeNames
import com.mcdebug.loader.StorageApi
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.network.Connection
import net.minecraft.network.DisconnectionDetails
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.EntityHitResult
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.nio.file.Path

/**
 * NeoForge implementation of the loader seam (see [LoaderApi]). Installed by
 * [McDebugNeoForge] (server side, and on a client by the client entrypoint).
 *
 * MC 26.3 runs on **official Mojang names** in dev and production alike, so `reflect.*` needs
 * no mapping table at all ([MojangRuntimeNames]).
 */
class NeoForgeLoaderApi : LoaderApi {
    override val name: String = "neoforge"

    override val gameDir: Path get() = FMLPaths.GAMEDIR.get()

    override val modVersion: String =
        ModList.get().getModContainerById(MOD_ID).map { it.modInfo.version.toString() }.orElse("0.0.0")

    override val fluidUnit: String = "mb"

    /** NeoForge Energy (Forge Energy) — what `EnergyHandler` counts. */
    override val energyUnit: String = "FE"

    override val names: RuntimeNames = MojangRuntimeNames

    override val storage: StorageApi = NeoForgeStorageApi()

    override fun isModLoaded(modId: String): Boolean = ModList.get().isLoaded(modId)

    override fun onServerStarted(callback: (MinecraftServer) -> Unit) {
        NeoForge.EVENT_BUS.addListener { event: ServerStartedEvent -> callback(event.server) }
    }

    override fun onServerStopping(callback: (MinecraftServer) -> Unit) {
        NeoForge.EVENT_BUS.addListener { event: ServerStoppingEvent -> callback(event.server) }
    }

    override fun onServerTick(callback: (MinecraftServer) -> Unit) {
        NeoForge.EVENT_BUS.addListener { event: ServerTickEvent.Post -> callback(event.server) }
    }

    override fun fakePlayerPacketListener(
        server: MinecraftServer,
        connection: Connection,
        player: ServerPlayer,
        cookie: CommonListenerCookie,
    ): ServerGamePacketListenerImpl {
        val neoConnection = object : Connection(connection.receiving) {
            override fun channel(): Channel = DUMMY_CHANNEL
        }
        return object : ServerGamePacketListenerImpl(server, neoConnection, player, cookie) {
            override fun send(packet: Packet<*>) {}
            override fun send(packet: Packet<*>, sendListener: ChannelFutureListener?) {}
            override fun disconnect(reason: Component) {}
            override fun disconnect(details: DisconnectionDetails) {}

            // Vanilla menu sync and mods can both probe extended payload support.
            // 原版菜单同步和模组都可能探测扩展载荷支持。
            override fun hasChannel(payloadId: Identifier): Boolean = false
        }
    }

    /**
     * `IModFile.getFilePath()` — the real module file. A `protectionDomain.codeSource` is not a
     * file URL on NeoForge (mods live in a module layer, where it degenerates to `/`), which is
     * exactly why `gui.status.jarPath` has to ask the loader.
     */
    override fun modJarPath(): String? = try {
        ModList.get().getModFileById(MOD_ID)?.file?.filePath?.toString()
    } catch (_: Throwable) {
        null
    }

    /** 26.3 fires EntityInteract inside Player.interactOn; the shared path must not fire it twice. */
    override fun fireUseEntity(
        player: Player,
        level: Level,
        hand: InteractionHand,
        entity: Entity,
        hitResult: EntityHitResult?,
    ): InteractionResult = InteractionResult.PASS

    companion object {
        const val MOD_ID: String = "mcdebug"
        private val DUMMY_CHANNEL: Channel = EmbeddedChannel()
    }
}

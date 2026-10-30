package com.mcdebug.loader.fabric

import com.mcdebug.loader.LoaderApi
import com.mcdebug.loader.RuntimeNames
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.EntityHitResult
import java.nio.file.Path

/**
 * Fabric implementation of the loader seam (see [LoaderApi]). Installed by the Fabric
 * `server` entrypoint and, on a client, by the `client` entrypoint.
 */
class FabricLoaderApi : LoaderApi {
    override val name: String = "fabric"

    override val gameDir: Path get() = FabricLoader.getInstance().gameDir

    override val modVersion: String =
        FabricLoader.getInstance().getModContainer(MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("0.0.0")

    override val fluidUnit: String = "droplet"

    /** Team Reborn Energy — the unit Fabric's energy lookups report. */
    override val energyUnit: String = "E"

    override val names: RuntimeNames = FabricRuntimeNames()

    override val storage: com.mcdebug.loader.StorageApi = FabricStorageApi()

    override fun isModLoaded(modId: String): Boolean = FabricLoader.getInstance().isModLoaded(modId)

    override fun onServerStarted(callback: (MinecraftServer) -> Unit) {
        ServerLifecycleEvents.SERVER_STARTED.register { server -> callback(server) }
    }

    override fun onServerStopping(callback: (MinecraftServer) -> Unit) {
        ServerLifecycleEvents.SERVER_STOPPING.register { server -> callback(server) }
    }

    override fun onServerTick(callback: (MinecraftServer) -> Unit) {
        ServerTickEvents.END_SERVER_TICK.register { server -> callback(server) }
    }

    override fun modJarPath(): String? = try {
        FabricLoaderApi::class.java.protectionDomain?.codeSource?.location?.toURI()?.let { Path.of(it).toString() }
    } catch (_: Throwable) {
        null
    }

    override fun fireUseEntity(
        player: Player,
        level: Level,
        hand: InteractionHand,
        entity: Entity,
        hitResult: EntityHitResult?,
    ): InteractionResult =
        UseEntityCallback.EVENT.invoker().interact(player, level, hand, entity, hitResult)

    companion object {
        const val MOD_ID: String = "mcdebug"
    }
}

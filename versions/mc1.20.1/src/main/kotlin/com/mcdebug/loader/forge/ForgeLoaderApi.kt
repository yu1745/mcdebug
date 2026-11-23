package com.mcdebug.loader.forge

import com.mcdebug.loader.LoaderApi
import com.mcdebug.loader.MojangRuntimeNames
import com.mcdebug.loader.RuntimeNames
import com.mcdebug.loader.StorageApi
import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.EntityHitResult
import net.minecraftforge.common.ForgeHooks
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.loading.FMLPaths
import java.nio.file.Path

/**
 * Forge 1.20.1 implementation of the loader seam. Class names are official Mojang names at runtime;
 * member names are SRG in production, so `reflect.*` is only meaningful in dev (documented limitation
 * of this backport, where the purpose is GUI/screenshot baselines).
 */
class ForgeLoaderApi : LoaderApi {
    override val name: String = "forge"
    override val gameDir: Path get() = FMLPaths.GAMEDIR.get()
    override val modVersion: String =
        ModList.get().getModContainerById(MOD_ID).map { it.modInfo.version.toString() }.orElse("0.0.0")
    override val fluidUnit: String = "mb"
    override val energyUnit: String = "FE"
    override val names: RuntimeNames = MojangRuntimeNames
    override val storage: StorageApi = ForgeStorageApi()

    override fun isModLoaded(modId: String): Boolean = ModList.get().isLoaded(modId)

    override fun onServerStarted(callback: (MinecraftServer) -> Unit) {
        MinecraftForge.EVENT_BUS.addListener { event: ServerStartedEvent -> callback(event.server) }
    }

    override fun onServerStopping(callback: (MinecraftServer) -> Unit) {
        MinecraftForge.EVENT_BUS.addListener { event: ServerStoppingEvent -> callback(event.server) }
    }

    override fun onServerTick(callback: (MinecraftServer) -> Unit) {
        MinecraftForge.EVENT_BUS.addListener { event: TickEvent.ServerTickEvent ->
            if (event.phase == TickEvent.Phase.END) callback(event.server)
        }
    }

    override fun modJarPath(): String? = try {
        ModList.get().getModFileById(MOD_ID)?.file?.filePath?.toString()
    } catch (_: Throwable) {
        null
    }

    override fun fireUseEntity(
        player: Player,
        level: Level,
        hand: InteractionHand,
        entity: Entity,
        hitResult: EntityHitResult?,
    ): InteractionResult {
        val result = if (hitResult != null) {
            ForgeHooks.onInteractEntityAt(player, entity, hitResult, hand)
        } else {
            ForgeHooks.onInteractEntity(player, entity, hand)
        }
        return result ?: InteractionResult.PASS
    }

    companion object {
        const val MOD_ID: String = "mcdebug"
    }
}

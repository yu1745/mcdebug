package com.mcdebug.loader.neoforge

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
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.CommonHooks
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.nio.file.Path

/**
 * NeoForge implementation of the loader seam (see [LoaderApi]). Installed by
 * [McDebugNeoForge] (server side, and on a client by the client entrypoint).
 *
 * MC 1.21.1 runs on **official Mojang names** in dev and production alike, so `reflect.*` needs
 * no mapping table at all ([MojangRuntimeNames]).
 */
class NeoForgeLoaderApi : LoaderApi {
    override val name: String = "neoforge"

    override val gameDir: Path get() = FMLPaths.GAMEDIR.get()

    override val modVersion: String =
        ModList.get().getModContainerById(MOD_ID).map { it.modInfo.version.toString() }.orElse("0.0.0")

    override val fluidUnit: String = "mb"

    /** NeoForge Energy (Forge Energy) — what `IEnergyStorage` counts. */
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

    /**
     * Both calls below are the ones the server packet handler makes, so listeners see exactly what
     * a real right-click delivers (the Fabric side has a single `UseEntityCallback` that carries a
     * nullable hit result):
     *  - with a hit vector  → `PlayerInteractEvent.EntityInteractSpecific` (`onInteractEntityAt`);
     *  - without one        → `PlayerInteractEvent.EntityInteract` (`onInteractEntity`) — the flavour
     *    a plain interact produces. Fabricating a *specific* event at the entity's feet with a null
     *    hit result would leave `EntityInteract` listeners blind.
     */
    override fun fireUseEntity(
        player: Player,
        level: Level,
        hand: InteractionHand,
        entity: Entity,
        hitResult: EntityHitResult?,
    ): InteractionResult {
        val event = if (hitResult != null) {
            // The Vec3 overload takes the position *relative to the entity* (localPos); the
            // HitResult overload does the subtraction, exactly like the packet handler.
            CommonHooks.onInteractEntityAt(player, entity, hitResult, hand)
        } else {
            CommonHooks.onInteractEntity(player, entity, hand)
        }
        return event ?: InteractionResult.PASS
    }

    companion object {
        const val MOD_ID: String = "mcdebug"
    }
}

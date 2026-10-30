package com.mcdebug.loader

import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.EntityHitResult
import java.nio.file.Path

/**
 * The **loader seam**: everything in mcdebug that has to talk to a mod loader goes through this
 * interface, so the same source tree compiles and behaves the same on Fabric and NeoForge.
 *
 * Exactly one implementation is installed by the loader entrypoint, before any plane starts:
 *  - Fabric:   `com.mcdebug.McDebugMod` (server entrypoint) / `com.mcdebug.client.McDebugClientMod`
 *  - NeoForge: `com.mcdebug.loader.neoforge.McDebugNeoForge`
 *
 * Rules for shared code: never import `net.fabricmc.*` or `net.neoforged.*` outside a
 * `com.mcdebug.loader.<loader>` package, and never reference a loader entrypoint class.
 */
interface LoaderApi {
    /** Loader id reported by `server.status.modLoader`: `fabric` | `neoforge`. */
    val name: String

    /** Game (run) directory: where `mcdebug/socket`, `mcdebug/port`, `mods/` live. */
    val gameDir: Path

    /** Version of this mod as the loader sees it. */
    val modVersion: String

    fun isModLoaded(modId: String): Boolean

    /**
     * Unit of every fluid amount crossing the RPC: Fabric's Transfer API counts droplets
     * (1 bucket = 81000) while the Forge family counts mB (1 bucket = 1000). Reported by
     * `server.status.fluidUnit` so a client can interpret `storage.*` / `fluid.*` amounts.
     */
    val fluidUnit: String

    /**
     * Unit of the `teamreborn:energy` handle's amounts/capacities: `E` (Team Reborn Energy, Fabric)
     * or `FE` (NeoForge `IEnergyStorage`). Reported by `server.status`, mirroring [fluidUnit], so a
     * cross-loader script never compares two different units by accident.
     */
    val energyUnit: String

    /** Runtime name translation for `reflect.*` (intermediary on Fabric production, Mojang elsewhere). */
    val names: RuntimeNames

    /** Storage lookup/transfer implementation for `storage.*` and `fluid.*`. */
    val storage: StorageApi

    // ---------------------------------------------------------------- lifecycle
    // Registered once per process; both dedicated servers and the integrated server of a
    // single-player client must fire these (the client plane relies on the latter).

    fun onServerStarted(callback: (MinecraftServer) -> Unit)

    fun onServerStopping(callback: (MinecraftServer) -> Unit)

    /** Passive end-of-tick observer. It must never advance ticks itself (see AGENTS.md §7). */
    fun onServerTick(callback: (MinecraftServer) -> Unit)

    // ---------------------------------------------------------------- gameplay hooks

    /**
     * Fire the loader's "player right-clicks an entity" event and return its result, so
     * `world.interactEntity` runs the same listeners a real packet would trigger.
     */
    /**
     * Absolute path of the jar (or classes directory) this mod was loaded from, or null when the
     * loader cannot tell.
     *
     * `gui.status.jarPath` exists so an agent can spot a **stale copy in `mods/`**; a plain
     * `protectionDomain.codeSource` is a file URL on Fabric but not on NeoForge (mods are loaded
     * through a module layer there, where it degenerates to `/`), so each loader answers for
     * itself.
     */
    fun modJarPath(): String? = null

    fun fireUseEntity(
        player: Player,
        level: Level,
        hand: InteractionHand,
        entity: Entity,
        hitResult: EntityHitResult?,
    ): InteractionResult
}

/**
 * Installed by the loader entrypoint; shared code only reads [api]. Kept as a plain object (not a
 * service loader) because the entrypoint always runs before any RPC handler can.
 */
object Loader {
    @Volatile
    private var impl: LoaderApi? = null

    val api: LoaderApi
        get() = impl ?: error("mcdebug loader seam not installed (loader entrypoint did not run)")

    val installed: Boolean get() = impl != null

    /**
     * Name translation used by `reflect.*`. Falls back to [MojangRuntimeNames] when no seam is
     * installed, which is the correct mapping in dev and in unit tests; every shipped entrypoint
     * installs the seam long before an RPC can arrive (Fabric production needs its table).
     */
    val names: RuntimeNames get() = impl?.names ?: MojangRuntimeNames

    fun install(api: LoaderApi) {
        impl = api
    }
}

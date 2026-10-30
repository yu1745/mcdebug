package com.mcdebug

import com.mcdebug.loader.Loader
import com.mcdebug.loader.fabric.FabricLoaderApi
import com.mcdebug.rpc.RpcEnv
import net.fabricmc.api.DedicatedServerModInitializer

/**
 * Fabric `server` entrypoint — installs the loader seam and the server plane.
 *
 * One jar, two planes:
 *  - **server plane** ([ServerPlane]): `world.*` / `be.*` / `inv.*` / `wait.*` … over a
 *    unix domain socket (primary, local, default `<gameDir>/mcdebug/socket`) and TCP
 *    (auxiliary, cross-machine, default port 25580). Either listener may fail to bind
 *    without blocking startup; only if both fail does the plane error out.
 *    Discovery files: `<gameDir>/mcdebug/port` (socket path) and
 *    `<gameDir>/mcdebug/tcpPort` (TCP port).
 *  - **client plane** (`mcdebug.client.McDebugClientMod`, `gui.*`): real rendering and
 *    real Screen event dispatch — screenshots with pixel coordinates, clicks, block
 *    interaction. Registered by the Fabric `client` entrypoint.
 *
 * This entrypoint is the `DedicatedServerModInitializer`, so it does **not** fire on a
 * client. A single-player client gets the server plane from the client entrypoint
 * instead ([ServerPlane.installLifecycleHooks] hooks the integrated server), which
 * yields both planes in one process.
 *
 * The NeoForge build excludes this file and uses
 * `com.mcdebug.loader.neoforge.McDebugNeoForge` instead; every other file in this source
 * tree is shared between the loaders (see [Loader]).
 */
object McDebugMod : DedicatedServerModInitializer {
    override fun onInitializeServer() {
        Loader.install(FabricLoaderApi())
        RpcEnv.gameDir = { Loader.api.gameDir }
        McDebug.LOGGER.info("mcdebug {} initializing (fabric dedicated server)", McDebug.MOD_VERSION)
        ServerPlane.installLifecycleHooks()
    }
}

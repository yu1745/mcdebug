package com.mcdebug

import com.mcdebug.rpc.RpcDispatcher
import com.mcdebug.rpc.RpcEnv
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * mcdebug — JSON-RPC debug agent for mod development automation.
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
 */
object McDebugMod : DedicatedServerModInitializer {
    const val MOD_ID = "mcdebug"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
    val MOD_VERSION: String = FabricLoader.getInstance().getModContainer(MOD_ID)
        .map { it.metadata.version.friendlyString }
        .orElse("0.0.0")

    /** Live JSON-RPC dispatcher of the server plane (null when not running). */
    val dispatcher: RpcDispatcher? get() = ServerPlane.dispatcher

    /** The MinecraftServer instance backing the current server-plane session. */
    val currentServer: MinecraftServer? get() = ServerPlane.currentServer

    override fun onInitializeServer() {
        RpcEnv.gameDir = { FabricLoader.getInstance().gameDir }
        LOGGER.info("mcdebug {} initializing (dedicated server)", MOD_VERSION)
        ServerPlane.installLifecycleHooks()
    }
}

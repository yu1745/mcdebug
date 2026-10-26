package com.mcdebug.client

import com.mcdebug.McDebugMod
import com.mcdebug.ServerPlane
import com.mcdebug.contract.Methods
import com.mcdebug.rpc.RpcEnv
import com.mcdebug.rpc.RpcServer
import com.mcdebug.rpc.TransportConfig
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

/**
 * Fabric `client` entrypoint — the half of mcdebug that only exists in a real client.
 *
 * It does two things the Fabric loader will not do for us:
 *
 * 1. **Client plane** (`gui.*`): a second [RpcServer] with [TransportConfig.CLIENT]
 *    (default `<gameDir>/mcdebug/clientSocket`, TCP **off** by default on port 25581)
 *    dispatching to [GuiOps] on the client thread. Distinct socket/port defaults are
 *    mandatory because a single-player client hosts both planes in one process
 *    sharing one `gameDir`.
 * 2. **Server plane fallback**: `net.fabricmc.api.ServerModInitializer` does not exist
 *    in loader 0.19.5, so [McDebugMod] (a `DedicatedServerModInitializer`) never fires
 *    on a client. Calling [ServerPlane.installLifecycleHooks] here hooks the
 *    *integrated* server through the same `ServerLifecycleEvents`, which is what gives
 *    a single-player client both `mcdebug/socket` and `mcdebug/clientSocket`.
 *
 * The client plane starts even if the server plane never does: looking at screens and
 * clicking widgets is useful on a client that never opens a world.
 */
class McDebugClientMod : ClientModInitializer {

    override fun onInitializeClient() {
        val log = McDebugMod.LOGGER
        val gameDir = FabricLoader.getInstance().gameDir
        RpcEnv.gameDir = { gameDir }
        log.info("mcdebug {} initializing (client) gameDir={}", McDebugMod.MOD_VERSION, gameDir)

        ClientTasks.install()

        val dispatcher = ClientRpcDispatcher().apply { registerGroup("gui", GuiOps) }
        val mismatch = contractMismatch(dispatcher.methods())
        ClientInfo.setContractMismatch(mismatch)
        if (mismatch.isEmpty()) {
            log.info("mcdebug client plane contract ok ({} methods)", dispatcher.methods().size)
        } else {
            log.error(
                "mcdebug client plane contract mismatch ({}): {}",
                mismatch.size,
                mismatch.joinToString(", "),
            )
        }

        // RpcServer resolves the socket path itself (system property -> env ->
        // config/mcdebug.json -> default); the two lambdas only say where the
        // *discovery files* go, and they must differ from the server plane's
        // `mcdebug/port` + `mcdebug/tcpPort` because both planes share one gameDir.
        val socket = gameDir.resolve("mcdebug").resolve(TransportConfig.CLIENT.defaultSocketFileName)
        ClientInfo.setIntendedSocket(socket)
        try {
            val server = RpcServer(
                host = ClientMethodHost(dispatcher),
                socketFilePath = { socketFilePath(gameDir) },
                tcpPortFilePath = { tcpPortFilePath(gameDir) },
                protocolVersion = ClientInfo.PROTOCOL_VERSION,
                transport = TransportConfig.CLIENT,
            )
            server.start(null)
            ClientInfo.attach(server)
            log.info("mcdebug client plane listening: {}", ClientInfo.endpointDescription())
        } catch (t: Throwable) {
            // Both listeners failed: the game must still start. The log line and
            // `gui.status` on any other transport are the only diagnostics left.
            log.error("mcdebug client plane failed to start (unix={}): {}", socket, t.message, t)
        }

        log.info(
            "mcdebug client info: jar={} modVersion={} protocol={}",
            ClientInfo.jarPath(),
            ClientInfo.modVersion(),
            ClientInfo.PROTOCOL_VERSION,
        )

        ServerPlane.installLifecycleHooks()
        log.info("mcdebug client ready")
    }

    /**
     * Compares the registered `gui.*` handlers against [Methods.CLIENT_METHODS].
     *
     * This is a runtime self-check rather than a unit test on purpose: `src/client`
     * does not compile into the server test source set, so a plain JUnit contract test
     * cannot see it. A mismatch is reported (never thrown) because a stale jar must
     * still be able to answer `gui.status` and *tell* you it is stale.
     */
    private fun contractMismatch(registered: Set<String>): List<String> {
        val missing = Methods.CLIENT_METHODS.filterNot { it in registered }.map { "missing:$it" }
        val undeclared = registered.filterNot { it in Methods.CLIENT_METHODS }.map { "undeclared:$it" }
        val wrongPrefix = registered.filterNot { it.startsWith("gui.") }.map { "wrongPrefix:$it" }
        return (missing + undeclared + wrongPrefix).sorted()
    }

    /** Where the client plane's discovery file lives (`<gameDir>/mcdebug/clientPort`). */
    fun socketFilePath(gameDir: Path): Path = gameDir.resolve("mcdebug").resolve("clientPort")

    /** Where the client plane's TCP discovery file lives (`<gameDir>/mcdebug/clientTcpPort`). */
    fun tcpPortFilePath(gameDir: Path): Path = gameDir.resolve("mcdebug").resolve("clientTcpPort")
}

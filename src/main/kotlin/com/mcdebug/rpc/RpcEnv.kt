package com.mcdebug.rpc

import java.nio.file.Path

/**
 * Loader-specific environment hook for the loader-independent RPC transport.
 * Fabric sets it from FabricLoader, Forge from FMLPaths; RpcServer only reads it.
 */
object RpcEnv {
    /** Returns the game (run) directory. Must be assigned by the loader entrypoint before RpcServer.start. */
    lateinit var gameDir: () -> Path
}

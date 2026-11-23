package com.mcdebug

import com.mcdebug.loader.Loader
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Loader-independent identity of this mod. Shared code (RPC transport, handlers, client plane)
 * uses these instead of a loader entrypoint's fields, so the same sources compile on both loaders.
 */
object McDebug {
    const val MOD_ID: String = "mcdebug"
    /**
     * 本源码树对应的 Minecraft 版本线：`Methods` 的清单是三个版本的并集，契约自检与
     * `gui.status.methods` 都按 `Methods.*Methods(MC_VERSION_LINE)` 取本版本的子集。
     */
    const val MC_VERSION_LINE: String = "1.21.1"

    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    /**
     * Version as the loader reports it. Falls back to `0.0.0` before the seam is installed
     * (unit tests) so no caller has to guard against a missing loader.
     */
    val MOD_VERSION: String
        get() = if (Loader.installed) Loader.api.modVersion else "0.0.0"
}

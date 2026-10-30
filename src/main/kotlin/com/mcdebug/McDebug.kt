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

    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    /**
     * Version as the loader reports it. Falls back to `0.0.0` before the seam is installed
     * (unit tests) so no caller has to guard against a missing loader.
     */
    val MOD_VERSION: String
        get() = if (Loader.installed) Loader.api.modVersion else "0.0.0"
}

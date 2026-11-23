package com.mcdebug.util

import com.mcdebug.loader.Loader
import com.mcdebug.loader.RuntimeNames

/**
 * Facade over the loader's [RuntimeNames] implementation, kept because `reflect.*` code refers to
 * it by this name in several places:
 *
 *  - Fabric:   intermediary <-> Mojang table from `mappings/reflect.txt.gz` (production runs on
 *              intermediary names; dev runs on Mojang names)
 *  - NeoForge: identity ([com.mcdebug.loader.MojangRuntimeNames]) — MC 1.21.1 runs on official
 *              Mojang names in dev *and* production, so no table is needed at all
 */
object Mappings {
    private val impl: RuntimeNames get() = Loader.names

    fun ensureLoaded() = impl.ensureLoaded()

    fun isAvailable(): Boolean = impl.isAvailable()

    /** Resolve a class name (dotted or slashed; Mojang, intermediary, or a simple Mojang name). */
    fun runtimeClassName(userName: String): String = impl.runtimeClassName(userName)

    /** Runtime names a member may have, looking through the class and its supertypes. */
    fun runtimeMemberNames(runtimeClass: Class<*>, memberName: String, isMethod: Boolean): List<String> =
        impl.runtimeMemberNames(runtimeClass, memberName, isMethod)

    /** Mojang name of a runtime member, or null when it is not a mapped Minecraft member. */
    fun mojangMemberName(declaringClass: Class<*>, runtimeName: String, isMethod: Boolean): String? =
        impl.mojangMemberName(declaringClass, runtimeName, isMethod)

    /** Mojang class name (dotted) of a runtime class, or null for non-Minecraft classes. */
    fun mojangClassName(runtimeClass: Class<*>): String? = impl.mojangClassName(runtimeClass)
}

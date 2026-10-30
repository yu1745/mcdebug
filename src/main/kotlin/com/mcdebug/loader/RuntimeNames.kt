package com.mcdebug.loader

/**
 * Name translation for `reflect.*`. The caller always speaks Mojang (official) names, which is
 * what this repository compiles against; the implementation maps them to whatever the running
 * game actually uses.
 *
 *  - Fabric dev / NeoForge (all environments): runtime namespace **is** Mojang → [MojangRuntimeNames]
 *  - Fabric production: the loader remaps Minecraft to **intermediary** (`class_1309`) → table lookup
 */
interface RuntimeNames {
    fun ensureLoaded()

    fun isAvailable(): Boolean

    /** Resolve a class name (dotted or slashed; Mojang, intermediary or a simple Mojang name). */
    fun runtimeClassName(userName: String): String

    /**
     * Runtime names a member may have, looking through [runtimeClass] and its supertypes.
     * Always contains [memberName] itself as the last resort.
     */
    fun runtimeMemberNames(runtimeClass: Class<*>, memberName: String, isMethod: Boolean): List<String>

    /** Mojang name of a runtime member, or null when it is not a mapped Minecraft member. */
    fun mojangMemberName(declaringClass: Class<*>, runtimeName: String, isMethod: Boolean): String?

    /** Mojang class name (dotted) of a runtime class, or null for non-Minecraft classes. */
    fun mojangClassName(runtimeClass: Class<*>): String?
}

/**
 * Identity mapping: the runtime namespace already is Mojang, so names pass through unchanged.
 * Used by NeoForge (production and dev alike, MC 1.21.1) and by Fabric's dev environment.
 */
object MojangRuntimeNames : RuntimeNames {
    override fun ensureLoaded() {}

    override fun isAvailable(): Boolean = true

    override fun runtimeClassName(userName: String): String = userName.trim().replace('/', '.')

    override fun runtimeMemberNames(runtimeClass: Class<*>, memberName: String, isMethod: Boolean): List<String> =
        listOf(memberName)

    override fun mojangMemberName(declaringClass: Class<*>, runtimeName: String, isMethod: Boolean): String? = null

    override fun mojangClassName(runtimeClass: Class<*>): String? =
        runtimeClass.name.takeIf { it.startsWith("net.minecraft.") }
}

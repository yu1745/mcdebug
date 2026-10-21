package com.mcdebug.util

/**
 * Name mapper for reflect.* (Forge port).
 *
 * The Fabric mod bundles yarn/mojang -> intermediary tables because Fabric loader remaps Minecraft to
 * intermediary names at runtime. Forge needs none of that for classes: Minecraft classes keep their
 * official (Mojang) names at runtime, in dev and in production alike. Only *member* names differ
 * (SRG `m_123_` / `f_123_` in production, official names in the dev environment), and no SRG table is
 * bundled, so members are used exactly as given:
 *   - dev (runServer): official names, e.g. `getBlockState`
 *   - production: SRG names, e.g. `m_8055_`
 * Mod classes keep their own names everywhere. The API shape mirrors the Fabric Mappings object so
 * ReflectOps stays source-compatible.
 */
object Mappings {
    fun ensureLoaded() {}

    fun isAvailable(): Boolean = true

    fun runtimeClassName(userName: String): String = userName

    fun resolveMemberName(runtimeClassSlash: String, memberName: String, isMethod: Boolean): String = memberName

    fun yarnMemberName(runtimeClassSlash: String, intermediaryName: String, isMethod: Boolean): String? = null

    fun yarnClassName(runtimeClassSlash: String): String? = null

    /** Official names ARE the Mojang names here. */
    fun mojangClassName(runtimeClassSlash: String): String? =
        if (runtimeClassSlash.startsWith("net/minecraft/")) runtimeClassSlash.replace('/', '.') else null
}

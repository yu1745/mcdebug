package com.mcdebug.util

import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Minecraft name mapper for reflect.*: accepts Mojang (official) or intermediary names and
 * resolves them to whatever the running game uses.
 *
 *   - dev (Loom runClient/runServer): runtime namespace `named` = Mojang names
 *   - production (Fabric loader):     runtime namespace `intermediary` (class_1309, method_6076)
 *
 * Source: `mappings/reflect.txt.gz`, generated at build time by `generateReflectMappings` from
 * Loom's mappings for this exact Minecraft version (intermediary <-> Mojang), so the table can
 * never drift from the game the jar was built for. Lines (tab separated, members follow
 * their class):
 *   c <intermediary class> <mojang class>   (slashed)
 *   m <intermediary name>  <mojang name>
 *   f <intermediary name>  <mojang name>
 *
 * Members are matched by name only; a Mojang name shared by several intermediary members
 * (overloads renamed differently) yields several candidates and the caller picks the one
 * whose signature fits. Mod classes are not mapped: they keep their names at runtime.
 */
object Mappings {
    private val LOGGER = LoggerFactory.getLogger("mcdebug-mappings")
    private const val RESOURCE = "/mappings/reflect.txt.gz"

    private class Members {
        val namedToInter = HashMap<String, MutableList<String>>()
        val interToNamed = HashMap<String, String>()
        fun add(inter: String, named: String) {
            namedToInter.getOrPut(named) { ArrayList(1) }.add(inter)
            interToNamed[inter] = named
        }
    }

    /** Both keyed by intermediary class (slashed). */
    private val methods = HashMap<String, Members>()
    private val fields = HashMap<String, Members>()
    private val interToNamedClass = HashMap<String, String>()
    private val namedToInterClass = HashMap<String, String>()
    /** Mojang simple class name ("LivingEntity") -> intermediary classes. */
    private val simpleToInter = HashMap<String, MutableList<String>>()

    @Volatile
    private var loaded = false

    /** True when the game runs on intermediary names (production), false in a Mojang-named dev run. */
    private val intermediaryRuntime: Boolean by lazy {
        runCatching { FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace == "intermediary" }.getOrDefault(true)
    }

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                load()
                loaded = true
                LOGGER.info(
                    "mappings loaded: {} classes, {} methods, {} fields (runtime: {})",
                    interToNamedClass.size, methods.values.sumOf { it.interToNamed.size },
                    fields.values.sumOf { it.interToNamed.size }, if (intermediaryRuntime) "intermediary" else "mojang",
                )
            } catch (e: Exception) {
                LOGGER.error("failed to load mappings resources", e)
            }
        }
    }

    private fun load() {
        val stream = Mappings::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("${RESOURCE.removePrefix("/")} not on classpath")
        GZIPInputStream(stream).use { gz ->
            BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).useLines { lines ->
                var current: String? = null
                lines.forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size != 3) return@forEach
                    when (parts[0]) {
                        "c" -> {
                            current = parts[1]
                            interToNamedClass[parts[1]] = parts[2]
                            namedToInterClass[parts[2]] = parts[1]
                            // Top-level classes win over nested ones of the same simple name.
                            val simple = parts[2].substringAfterLast('/')
                            val list = simpleToInter.getOrPut(simple.substringAfterLast('$')) { ArrayList(1) }
                            if ('$' in simple) list.add(parts[1]) else list.add(0, parts[1])
                        }
                        "m" -> current?.let { methods.getOrPut(it) { Members() }.add(parts[1], parts[2]) }
                        "f" -> current?.let { fields.getOrPut(it) { Members() }.add(parts[1], parts[2]) }
                    }
                }
            }
        }
    }

    fun isAvailable(): Boolean = loaded

    // ------------------------------------------------------------ resolution

    /**
     * Resolve a class name (dotted or slashed; Mojang, intermediary, or a simple Mojang
     * name like "LivingEntity") to the runtime dotted name. Unknown names come back as
     * given, so the caller's Class.forName fails with a clear error.
     */
    fun runtimeClassName(userName: String): String {
        ensureLoaded()
        val dotted = userName.trim().replace('/', '.')
        if (runCatching { Class.forName(dotted, false, Mappings::class.java.classLoader) }.isSuccess) return dotted
        val slash = dotted.replace('.', '/')
        val inter = when {
            slash in interToNamedClass -> slash
            slash in namedToInterClass -> namedToInterClass.getValue(slash)
            !slash.contains('/') -> simpleToInter[slash]?.firstOrNull()
            else -> null
        } ?: return dotted
        return runtimeOf(inter).replace('/', '.')
    }

    /**
     * Runtime names a member may have, looking through [runtimeClass] and its supertypes
     * (a member is mapped on the class that declares it). Always contains [memberName]
     * itself as the last resort.
     */
    fun runtimeMemberNames(runtimeClass: Class<*>, memberName: String, isMethod: Boolean): List<String> {
        ensureLoaded()
        val out = LinkedHashSet<String>()
        for (type in hierarchy(runtimeClass)) {
            val members = (if (isMethod) methods else fields)[intermediaryOf(type) ?: continue] ?: continue
            if (intermediaryRuntime) {
                members.namedToInter[memberName]?.let(out::addAll)
            } else {
                members.interToNamed[memberName]?.let(out::add)
            }
        }
        out.add(memberName)
        return out.toList()
    }

    /** Mojang name of a runtime member, or null when it is not a mapped Minecraft member. */
    fun mojangMemberName(declaringClass: Class<*>, runtimeName: String, isMethod: Boolean): String? {
        if (!intermediaryRuntime) return null
        ensureLoaded()
        val members = (if (isMethod) methods else fields)[intermediaryOf(declaringClass) ?: return null] ?: return null
        return members.interToNamed[runtimeName]
    }

    /** Mojang class name (dotted) of a runtime class, or null for non-Minecraft classes. */
    fun mojangClassName(runtimeClass: Class<*>): String? {
        ensureLoaded()
        val inter = intermediaryOf(runtimeClass) ?: return null
        return interToNamedClass[inter]?.replace('/', '.')
    }

    private fun intermediaryOf(type: Class<*>): String? {
        val slash = type.name.replace('.', '/')
        return if (intermediaryRuntime) slash.takeIf { it in interToNamedClass } else namedToInterClass[slash]
    }

    private fun runtimeOf(inter: String): String = if (intermediaryRuntime) inter else interToNamedClass[inter] ?: inter

    private fun hierarchy(type: Class<*>): List<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>().apply { add(type) }
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!out.add(c)) continue
            c.superclass?.let(queue::add)
            queue.addAll(c.interfaces)
        }
        return out.toList()
    }
}

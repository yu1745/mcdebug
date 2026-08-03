package com.mcdebug.util

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Lazy-loaded Minecraft name mapper supporting all three naming schemes:
 *   - intermediary (runtime): class_1309, method_6076, field_6222
 *   - yarn (named):           net.minecraft.entity.LivingEntity, tickActiveItemStack
 *   - mojang (official):      net.minecraft.world.entity.LivingEntity, tick
 *
 * At runtime (Fabric Loader + Kilt) Minecraft classes are remapped to
 * intermediary names, so reflection needs those. This mapper accepts any of
 * the three forms and resolves to the runtime (intermediary) name.
 *
 * Sources (bundled as gzip resources, both derived from official files):
 *   mappings/tiny-core.txt.gz     yarn 1.20.1+build.10 4-col: official intermed named
 *   mappings/mojang-core.txt.gz   mojang official 1.20.1: mojang name -> official name
 *
 * Chain: mojang -> official -> intermediary   (and yarn -> intermediary directly)
 *
 * Mod classes (ic2_120.*, slimeknights.*, tconstruct.*, ...) are NOT in the
 * mappings — they keep their own names at runtime, so the mapper only applies
 * to net.minecraft / com.mojang classes.
 */
object Mappings {
    private val LOGGER = LoggerFactory.getLogger("mcdebug-mappings")

    // ---- yarn <-> intermediary (from tiny 4-col) ----

    /** yarn class name (slashed) -> intermediary class name (slashed) */
    private val yarnToIntermediaryClass = HashMap<String, String>()

    /** intermediary class name (slashed) -> yarn class name (slashed) */
    private val intermediaryToYarnClass = HashMap<String, String>()

    /** official (srg/obf) class name (slashed) -> intermediary class name (slashed) */
    private val officialToIntermediaryClass = HashMap<String, String>()

    /** yarn simple class name (e.g. "LivingEntity") -> list of intermediary names */
    private val simpleClassToIntermediary = HashMap<String, MutableList<String>>()

    /** intermediary class (slashed) -> map yarn member -> intermediary member */
    private val methodMaps = HashMap<String, HashMap<String, String>>()
    private val fieldMaps = HashMap<String, HashMap<String, String>>()

    /** intermediary class (slashed) -> map official member -> intermediary member */
    private val methodMapsOfficial = HashMap<String, HashMap<String, String>>()
    private val fieldMapsOfficial = HashMap<String, HashMap<String, String>>()

    // ---- mojang -> official (from mojang txt) ----

    /** mojang class name (dotted) -> official class name (slashed) */
    private val mojangToOfficialClass = HashMap<String, String>()

    /** official class (slashed) -> map mojang member name -> official member name */
    private val mojangMethodToOfficial = HashMap<String, HashMap<String, String>>()
    private val mojangFieldToOfficial = HashMap<String, HashMap<String, String>>()

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                loadTiny()
                loadMojang()
                loaded = true
                LOGGER.info(
                    "mappings loaded: {} classes, {} methods, {} fields (yarn); {} mojang classes",
                    yarnToIntermediaryClass.size,
                    methodMaps.values.sumOf { it.size },
                    fieldMaps.values.sumOf { it.size },
                    mojangToOfficialClass.size
                )
            } catch (e: Exception) {
                LOGGER.error("failed to load mappings resources", e)
            }
        }
    }

    private fun loadTiny() {
        val stream = Mappings::class.java.getResourceAsStream("/mappings/tiny-core.txt.gz")
            ?: throw IllegalStateException("mappings/tiny-core.txt.gz not on classpath")
        GZIPInputStream(stream).use { gz ->
            BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).useLines { lines ->
                var currentClass: String? = null
                lines.forEach { line ->
                    if (line.isEmpty()) return@forEach
                    val parts = line.split('\t')
                    if (parts[0] == "c" && parts.size >= 4) {
                        // c <official> <intermediary> <named>
                        val official = parts[1]
                        val intermediary = parts[2]
                        val named = parts[3]
                        if (intermediary.startsWith("net/minecraft/")) {
                            yarnToIntermediaryClass[named] = intermediary
                            intermediaryToYarnClass[intermediary] = named
                            officialToIntermediaryClass[official] = intermediary
                            simpleClassToIntermediary.getOrPut(named.substringAfterLast('/')) { mutableListOf() }
                                .add(intermediary)
                        }
                        currentClass = if (intermediary.startsWith("net/minecraft/")) intermediary else null
                    } else if (parts.size >= 5 && parts[1] == "m") {
                        // \t m <desc> <official> <intermediary> <named>
                        val cls = currentClass ?: return@forEach
                        val official = parts[3]
                        val intermediary = parts[4]
                        val named = parts[5]
                        if (named.startsWith("<")) return@forEach
                        methodMaps.getOrPut(cls) { HashMap() }[named] = intermediary
                        methodMapsOfficial.getOrPut(cls) { HashMap() }[official] = intermediary
                    } else if (parts.size >= 5 && parts[1] == "f") {
                        // \t f <desc> <official> <intermediary> <named>
                        val cls = currentClass ?: return@forEach
                        val official = parts[3]
                        val intermediary = parts[4]
                        val named = parts[5]
                        fieldMaps.getOrPut(cls) { HashMap() }[named] = intermediary
                        fieldMapsOfficial.getOrPut(cls) { HashMap() }[official] = intermediary
                    }
                }
            }
        }
    }

    private fun loadMojang() {
        val stream = Mappings::class.java.getResourceAsStream("/mappings/mojang-core.txt.gz")
            ?: throw IllegalStateException("mappings/mojang-core.txt.gz not on classpath")
        GZIPInputStream(stream).use { gz ->
            BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).useLines { lines ->
                var currentOfficial: String? = null
                lines.forEach { line ->
                    if (line.isEmpty()) return@forEach
                    val idx = line.indexOf(" -> ")
                    if (idx < 0) return@forEach
                    val left = line.substring(0, idx)
                    val right = line.substring(idx + 4).trimEnd(':')
                    if (line.endsWith(":") && !left.contains(' ')) {
                        // class line: net.minecraft...Name -> srg:
                        currentOfficial = right.replace('.', '/')
                        mojangToOfficialClass[left] = right.replace('.', '/')
                    } else if (currentOfficial != null) {
                        // member line: <type> name(desc) -> srg  |  <type> name -> srg
                        val member = parseMojangMember(left) ?: return@forEach
                        if (member.second) {
                            mojangMethodToOfficial.getOrPut(currentOfficial) { HashMap() }[member.first] = right
                        } else {
                            mojangFieldToOfficial.getOrPut(currentOfficial) { HashMap() }[member.first] = right
                        }
                    }
                }
            }
        }
    }

    /** Parse "net.minecraft...Type methodName(params...)" -> (name, isMethod). */
    private fun parseMojangMember(s: String): Pair<String, Boolean>? {
        val open = s.indexOf('(')
        if (open >= 0) {
            // method: <returnType> name(params)
            val head = s.substring(0, open)
            val name = head.substringAfterLast(' ').trim()
            if (name.isEmpty()) return null
            return name to true
        }
        // field: <type> name
        val name = s.substringAfterLast(' ').trim()
        if (name.isEmpty()) return null
        return name to false
    }

    fun isAvailable(): Boolean = loaded

    // ------------------------------------------------------------ resolution

    /**
     * Resolve any supported class name to the runtime (intermediary) dotted name.
     * Accepts dotted/slashed yarn, mojang, intermediary, or simple yarn names.
     */
    fun runtimeClassName(userName: String): String {
        ensureLoaded()
        val trimmed = userName.trim()
        val dotted = trimmed.replace('/', '.')
        // 1. direct load (mod class or already intermediary)
        try {
            Class.forName(dotted)
            return dotted
        } catch (e: ClassNotFoundException) {
            // fall through
        }
        // 2. yarn (slashed) -> intermediary
        val slash = dotted.replace('.', '/')
        yarnToIntermediaryClass[slash]?.let { return it.replace('/', '.') }
        // 3. mojang (dotted) -> official -> intermediary
        mojangToOfficialClass[dotted]?.let { official ->
            officialToIntermediaryClass[official]?.let { return it.replace('/', '.') }
        }
        // 4. simple yarn name -> intermediary (first hit)
        if (!dotted.contains('.')) {
            simpleClassToIntermediary[trimmed]?.firstOrNull()?.let { return it.replace('/', '.') }
        }
        // 5. as-is (caller's Class.forName will fail with a clear error)
        return dotted
    }

    /**
     * Resolve a member name to the runtime intermediary name for a runtime class.
     * Accepts yarn, mojang, or intermediary member names.
     */
    fun resolveMemberName(runtimeClassSlash: String, memberName: String, isMethod: Boolean): String {
        ensureLoaded()
        // try yarn / intermediary maps
        val map = if (isMethod) methodMaps[runtimeClassSlash] else fieldMaps[runtimeClassSlash]
        if (map != null) {
            if (map.values.contains(memberName)) return memberName
            map[memberName]?.let { return it }
        }
        // try mojang -> official -> intermediary
        val officialClass = officialToIntermediaryClass.entries
            .firstOrNull { it.value == runtimeClassSlash }?.key
        if (officialClass != null) {
            val officialMap = if (isMethod) mojangMethodToOfficial[officialClass] else mojangFieldToOfficial[officialClass]
            if (officialMap != null) {
                officialMap[memberName]?.let { official ->
                    val interMap = if (isMethod) methodMapsOfficial[runtimeClassSlash] else fieldMapsOfficial[runtimeClassSlash]
                    interMap?.get(official)?.let { return it }
                }
            }
        }
        // try official member maps directly (user typed official name)
        val interMap = if (isMethod) methodMapsOfficial[runtimeClassSlash] else fieldMapsOfficial[runtimeClassSlash]
        if (interMap != null) {
            if (interMap.values.contains(memberName)) return memberName
            interMap[memberName]?.let { return it }
        }
        return memberName
    }

    /** Reverse: runtime intermediary member -> yarn name, or null. */
    fun yarnMemberName(runtimeClassSlash: String, intermediaryName: String, isMethod: Boolean): String? {
        ensureLoaded()
        val map = (if (isMethod) methodMaps[runtimeClassSlash] else fieldMaps[runtimeClassSlash]) ?: return null
        return map.entries.firstOrNull { it.value == intermediaryName }?.key
    }

    /** Reverse: runtime intermediary class -> yarn class name (slashed), or null. */
    fun yarnClassName(runtimeClassSlash: String): String? =
        intermediaryToYarnClass[runtimeClassSlash]

    /** Reverse: runtime intermediary class -> mojang class name (dotted), or null. */
    fun mojangClassName(runtimeClassSlash: String): String? {
        ensureLoaded()
        val official = officialToIntermediaryClass.entries
            .firstOrNull { it.value == runtimeClassSlash }?.key ?: return null
        return mojangToOfficialClass.entries.firstOrNull { it.value == official }?.key
    }
}

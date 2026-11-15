package com.mcdebug.loader.fabric

import com.mcdebug.loader.MojangRuntimeNames
import com.mcdebug.loader.RuntimeNames
import java.util.zip.GZIPInputStream

/**
 * Minecraft 26.3 uses official names in development and production. The generated
 * class index preserves reflect's short-name lookup; members pass through unchanged.
 */
class FabricRuntimeNames : RuntimeNames by MojangRuntimeNames {
    private val classes: Map<String, String> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/mappings/reflect.txt.gz")) {
            "Minecraft class index is missing"
        }
        val names = LinkedHashMap<String, String>()
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val parts = line.split('\t')
                if (parts.size == 3 && parts[0] == "c") {
                    val name = parts[2].replace('/', '.')
                    val simple = name.substringAfterLast('.').substringAfterLast('$')
                    // A top-level class wins over nested classes with the same short name.
                    if ('$' !in name) names[simple] = name else names.putIfAbsent(simple, name)
                }
            }
        }
        names
    }

    override fun ensureLoaded() { classes.size }

    override fun runtimeClassName(userName: String): String {
        val dotted = userName.trim().replace('/', '.')
        return if ('.' in dotted) dotted else classes[dotted] ?: dotted
    }
}

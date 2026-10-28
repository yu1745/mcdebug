package com.mcdebug.client

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mcdebug.McDebugMod
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import java.lang.instrument.ClassDefinition
import java.lang.instrument.Instrumentation
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** Pure: which compiled classes under the given output directories changed after [sinceMillis]. */
object ClassScan {
    data class Changed(val name: String, val file: Path, val modifiedMillis: Long)

    fun changed(dirs: List<Path>, sinceMillis: Long, limit: Int = 5000): List<Changed> {
        val out = ArrayList<Changed>()
        for (dir in dirs) {
            require(Files.isDirectory(dir)) { "not a class output directory: $dir" }
            Files.walk(dir).use { paths ->
                paths.filter { it.isRegularFile() && it.extension == "class" }.forEach { file ->
                    val modified = Files.getLastModifiedTime(file).toMillis()
                    if (modified > sinceMillis) {
                        val relative = dir.relativize(file).toString().removeSuffix(".class")
                        out.add(Changed(relative.replace('/', '.').replace('\\', '.'), file, modified))
                    }
                }
            }
            require(out.size <= limit) { "more than $limit changed classes; is this really a class output directory?" }
        }
        return out.sortedBy { it.name }
    }
}

/**
 * Redefine already-loaded classes from freshly compiled output directories, so a
 * GUI code change shows up without restarting the client (about 25-30 s).
 *
 * The `java.lang.instrument.Instrumentation` comes from a tiny agent the CLI
 * attaches to this JVM (`mcdebug gui hotswap`); the agent only stores it in
 * `System.getProperties()` under [KEY], the mod does the rest. Bytes go through
 * the same pipeline as normal class loading: Fabric's pre-mixin transformers when
 * Knot's classpath serves the same file (the Loom dev case), then Mixin.
 *
 * Standard HotSpot only accepts method-body changes; adding/removing fields or
 * methods needs a JetBrains Runtime started with `-XX:+AllowEnhancedClassRedefinition`.
 * Not yet loaded classes need nothing: Knot loads them from the new files. Existing
 * objects keep their state, so Screen layout code (init) needs `gui.reinit`.
 */
object Hotswap {
    const val KEY = "mcdebug.instrumentation"
    private val jvmStartMillis = ManagementFactory.getRuntimeMXBean().startTime
    @Volatile private var lastSwapMillis = jvmStartMillis
    @Volatile private var swaps = 0

    private fun instrumentation(): Instrumentation? = System.getProperties()[KEY] as? Instrumentation

    private fun enhanced(): Boolean =
        ManagementFactory.getRuntimeMXBean().inputArguments.any { it == "-XX:+AllowEnhancedClassRedefinition" }

    fun statusJson(): JsonObject = JsonObject().apply {
        val inst = instrumentation()
        addProperty("available", inst != null)
        addProperty("redefineSupported", inst?.isRedefineClassesSupported ?: false)
        addProperty("enhancedRedefinition", enhanced())
        addProperty("swaps", swaps)
        addProperty("since", lastSwapMillis)
    }

    /** Client thread (no Screen code runs concurrently with the redefinition). */
    fun swap(dirs: List<Path>, sinceMillis: Long?): JsonObject {
        val inst = instrumentation() ?: throw RpcException(RpcErrors.CLIENT_NOT_READY,
            "no Instrumentation in this JVM; run `mcdebug gui hotswap` (it attaches the agent first)", reason("HOTSWAP_UNAVAILABLE"))
        val scanStarted = System.currentTimeMillis()
        val changed = try {
            ClassScan.changed(dirs, sinceMillis ?: lastSwapMillis)
        } catch (e: IllegalArgumentException) {
            throw RpcException(RpcErrors.INVALID_PARAMS, e.message ?: "invalid class directories")
        }
        val byName = changed.associateBy { it.name }
        val loaded = inst.allLoadedClasses.filter { it.classLoader != null && it.name in byName }
        val loadedNames = loaded.map { it.name }.toSet()
        val failed = JsonArray()
        val definitions = ArrayList<ClassDefinition>()
        for (cls in loaded) {
            try {
                definitions.add(ClassDefinition(cls, transformedBytes(cls.name, Files.readAllBytes(byName.getValue(cls.name).file))))
            } catch (t: Throwable) {
                failed.add(failure(cls.name, t))
            }
        }
        val redefined = JsonArray()
        if (definitions.isNotEmpty()) {
            try {
                inst.redefineClasses(*definitions.toTypedArray())
                definitions.forEach { redefined.add(it.definitionClass.name) }
            } catch (batch: Throwable) {
                // One bad class must not hide which others are fine: retry one by one.
                for (definition in definitions) {
                    try {
                        inst.redefineClasses(definition)
                        redefined.add(definition.definitionClass.name)
                    } catch (t: Throwable) {
                        failed.add(failure(definition.definitionClass.name, t))
                    }
                }
            }
        }
        if (failed.isEmpty) lastSwapMillis = scanStarted
        if (redefined.size() > 0) swaps++
        McDebugMod.LOGGER.info("mcdebug hotswap: {} changed, {} redefined, {} failed", changed.size, redefined.size(), failed.size())
        return JsonObject().apply {
            addProperty("ok", failed.isEmpty)
            addProperty("changed", changed.size)
            add("redefined", redefined)
            add("notLoaded", stringArray(changed.map { it.name }.filter { it !in loadedNames }))
            add("failed", failed)
            addProperty("enhancedRedefinition", enhanced())
            addProperty("since", lastSwapMillis)
        }
    }

    private fun transformedBytes(name: String, fileBytes: ByteArray): ByteArray {
        val launcher = FabricLauncherBase.getLauncher()
        // Only trust Knot's pre-mixin pipeline when it reads this very file (dev classpath).
        val raw = try { launcher.getClassByteArray(name, false) } catch (_: Throwable) { null }
        val preMixin = if (raw != null && raw.contentEquals(fileBytes)) launcher.getClassByteArray(name, true) ?: fileBytes else fileBytes
        val transformer = MixinEnvironment.getCurrentEnvironment().activeTransformer as? IMixinTransformer ?: return preMixin
        return transformer.transformClassBytes(name, name, preMixin)
    }

    private fun failure(name: String, t: Throwable): JsonObject = JsonObject().apply {
        addProperty("class", name)
        addProperty("error", t.toString())
        if (t is UnsupportedOperationException && !enhanced()) {
            addProperty("hint", "schema change (added/removed field or method): needs a JetBrains Runtime with " +
                "-XX:+AllowEnhancedClassRedefinition, or a client restart")
        }
    }
}

package com.mcdebug.client

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mcdebug.McDebugMod
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
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
 * Pure: the members a redefinition would add or remove. Standard HotSpot rejects any
 * of these, so they are reported per class *before* trying, instead of as one opaque
 * batch error. `<clinit>` is ignored on both sides (reflection cannot see it).
 */
object SchemaDiff {
    data class Members(val methods: Set<String>, val fields: Set<String>)

    fun of(classBytes: ByteArray): Members {
        val methods = HashSet<String>()
        val fields = HashSet<String>()
        ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                if (name != "<clinit>") methods.add(name + descriptor)
                return null
            }
            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                fields.add(name + ":" + descriptor)
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return Members(methods, fields)
    }

    fun of(loaded: Class<*>): Members = Members(
        loaded.declaredMethods.map { it.name + Type.getMethodDescriptor(it) }.toSet() +
            loaded.declaredConstructors.map { "<init>" + Type.getConstructorDescriptor(it) },
        loaded.declaredFields.map { it.name + ":" + Type.getDescriptor(it.type) }.toSet(),
    )

    /** Human-readable changes, empty when only method bodies differ. */
    fun changes(old: Members, new: Members): List<String> =
        (new.methods - old.methods).sorted().map { "added method $it" } +
            (old.methods - new.methods).sorted().map { "removed method $it" } +
            (new.fields - old.fields).sorted().map { "added field $it" } +
            (old.fields - new.fields).sorted().map { "removed field $it" }
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
 * methods (lambdas count: a new lambda is a new synthetic method) needs a JetBrains
 * Runtime started with `-XX:+AllowEnhancedClassRedefinition`. All or nothing: a
 * partially applied swap would leave new code calling members that do not exist yet.
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
        val enhanced = enhanced()
        for (cls in loaded) {
            try {
                val bytes = transformedBytes(cls.name, Files.readAllBytes(byName.getValue(cls.name).file))
                val changes = if (enhanced) emptyList() else SchemaDiff.changes(SchemaDiff.of(cls), SchemaDiff.of(bytes))
                if (changes.isEmpty()) definitions.add(ClassDefinition(cls, bytes))
                else failed.add(schemaFailure(cls.name, changes))
            } catch (t: Throwable) {
                failed.add(failure(cls.name, t))
            }
        }
        val redefined = JsonArray()
        // RedefineClasses is atomic per call: either every class of the batch or none.
        if (failed.isEmpty && definitions.isNotEmpty()) {
            try {
                inst.redefineClasses(*definitions.toTypedArray())
                definitions.forEach { redefined.add(it.definitionClass.name) }
            } catch (batch: Throwable) {
                failed.add(failure(definitions.joinToString(",") { it.definitionClass.name }, batch))
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
            if (!failed.isEmpty) addProperty("applied", "none: nothing was redefined (all or nothing)")
            addProperty("enhancedRedefinition", enhanced)
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

    private fun schemaFailure(name: String, changes: List<String>): JsonObject = JsonObject().apply {
        addProperty("class", name)
        addProperty("error", "schema change")
        add("changes", stringArray(changes.take(20)))
        addProperty("hint", "standard HotSpot only redefines method bodies (a new lambda is a new method): for the open Screen's own " +
            "class use gui.reloadScreen; otherwise restart, or run on a JetBrains Runtime with -XX:+AllowEnhancedClassRedefinition")
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

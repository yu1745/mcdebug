package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Classpath directory of the mod jars built from `versions/` (see cli/build.gradle). Each jar sits at
 * `META-INF/mcdebug/<mc>/<loader>.jar`, e.g. `META-INF/mcdebug/26.3/fabric.jar`.
 */
internal const val EMBEDDED_MOD_DIR = "META-INF/mcdebug"

/** Minecraft lines this repository builds; the id doubles as the CLI `--mc` value and the contract subset key. */
internal enum class ModJarMc(val id: String, val loaders: List<ModJarLoader>) {
    MC_1_20_1("1.20.1", listOf(ModJarLoader.FORGE)),
    MC_1_21_1("1.21.1", listOf(ModJarLoader.FABRIC, ModJarLoader.NEOFORGE)),
    MC_26_3("26.3", listOf(ModJarLoader.FABRIC, ModJarLoader.NEOFORGE));

    /** `versions/mc<id>`: the independent build that produces this line's mod jars. */
    val buildDir: String get() = "versions/mc$id"

    companion object {
        fun parse(raw: String): ModJarMc = entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }
            ?: throw UsageError("unknown Minecraft version '$raw' (expected: ${entries.joinToString("|") { it.id }})")
    }
}

internal enum class ModJarLoader(val cliName: String) {
    FABRIC("fabric"),
    NEOFORGE("neoforge"),
    FORGE("forge");

    companion object {
        fun parse(raw: String): ModJarLoader = entries.firstOrNull { it.cliName.equals(raw, ignoreCase = true) }
            ?: throw UsageError("unknown loader '$raw' (expected: ${entries.joinToString("|") { it.cliName }})")
    }
}

/** One `<mc, loader>` pair: where its jar lives inside the CLI and under which name it is written out. */
internal data class EmbeddedMod(val mc: ModJarMc, val loader: ModJarLoader) {
    val resource: String get() = "$EMBEDDED_MOD_DIR/${mc.id}/${loader.cliName}.jar"

    /** `mcdebug-<loader>-<mc>-<version>.jar`: one scheme for all five jars, no clash between Minecraft lines. */
    fun fileName(version: String): String = "mcdebug-${loader.cliName}-${mc.id}-$version.jar"

    fun resourceBytes(): ByteArray? =
        JarCmd::class.java.classLoader.getResourceAsStream(resource)?.use { it.readAllBytes() }

    companion object {
        /** Every combination the CLI knows (five mod jars when all three versions are built). */
        val ALL: List<EmbeddedMod> = ModJarMc.entries.flatMap { mc -> mc.loaders.map { EmbeddedMod(mc, it) } }

        fun embedded(): List<EmbeddedMod> = ALL.filter { it.resourceBytes() != null }
    }
}

/**
 * Write one of the mcdebug mod jars bundled with this CLI. They come from the same repository and
 * `mod_version` as the CLI, so the versions always match and nothing is downloaded.
 */
class JarCmd : CliktCommand(
    name = "jar",
    help = "write one of the mcdebug mod JARs bundled with this CLI (same version, no download); " +
        "--mc picks the Minecraft line (default 26.3) and --loader the mod loader (default fabric; forge for 1.20.1); " +
        "--output DIR (e.g. run/mods) also removes every other mcdebug-*.jar there, " +
        "since two copies with the same mod id fail to load; --list shows what this CLI build embeds",
) {
    private val output by option("--output", help = "file or existing directory (default: ./mcdebug-<loader>-<mc>-<version>.jar)")
    private val mcName by option("--mc", help = "Minecraft line of the mod jar: 26.3 (default), 1.21.1 or 1.20.1")
        .choice("26.3", "1.21.1", "1.20.1").default("26.3")
    private val loaderName by option("--loader", help = "mod loader: fabric (default), neoforge, or forge (the only loader built for 1.20.1)")
        .choice("fabric", "neoforge", "forge")
    private val list by option("--list", help = "print the mod jars this CLI build embeds (and the missing ones), then exit").flag()

    override fun run() {
        if (list) {
            val version = cliVersion()
            EmbeddedMod.ALL.forEach { mod ->
                val state = if (mod.resourceBytes() != null) "embedded" else "missing"
                println("${mod.mc.id}\t${mod.loader.cliName}\t${mod.fileName(version)}\t$state")
            }
            return
        }
        val mc = ModJarMc.parse(mcName)
        val loader = loaderName?.let(ModJarLoader::parse)
            ?: (if (mc == ModJarMc.MC_1_20_1) ModJarLoader.FORGE else ModJarLoader.FABRIC)
        val written = writeEmbeddedModJar(output?.let(Path::of), EmbeddedMod(mc, loader))
        written.removed.forEach { System.err.println("removed older $it") }
        System.err.println("SHA256: ${written.sha256}")
        println(written.path)
    }
}

internal data class WrittenJar(val path: Path, val sha256: String, val removed: List<Path>)

internal fun writeEmbeddedModJar(
    output: Path?,
    mod: EmbeddedMod,
    version: String = cliVersion(),
): WrittenJar {
    if (mod.loader !in mod.mc.loaders) {
        throw UsageError("Minecraft ${mod.mc.id} is only built for: ${mod.mc.loaders.joinToString("|") { it.cliName }}")
    }
    val bytes = mod.resourceBytes() ?: throw UsageError(embeddedMissingMessage(mod))
    val target = when {
        output == null -> Path.of(mod.fileName(version))
        Files.isDirectory(output) -> output.resolve(mod.fileName(version))
        else -> output
    }
    target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    val tmp = Files.createTempFile(target.toAbsolutePath().parent, ".mcdebug-", ".jar.part")
    Files.write(tmp, bytes)
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    val removed = if (output != null && Files.isDirectory(output)) removeOlderModJars(output, keep = target) else emptyList()
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return WrittenJar(target, sha, removed)
}

private fun embeddedMissingMessage(mod: EmbeddedMod): String {
    val embedded = EmbeddedMod.embedded()
    val available = if (embedded.isEmpty()) "none" else embedded.joinToString(", ") { "${it.mc.id}/${it.loader.cliName}" }
    return "this CLI build has no embedded Minecraft ${mod.mc.id} ${mod.loader.cliName} jar (${mod.resource}); " +
        "embedded: $available. Build it first with '(cd ${mod.mc.buildDir} && ./gradlew build)', rebuild the CLI " +
        "with './gradlew :cli:copyCliJar', or pick an embedded combination with --mc/--loader (see 'mcdebug jar --list')"
}

/**
 * Every name the mcdebug mod jar ever had, so a mods directory ends up with exactly one copy
 * (`modId=mcdebug` twice fails to load):
 *
 *  - `mcdebug-<version>.jar`               Fabric, single-line repository (<= 0.8.0 releases)
 *  - `mcdebug-neoforge-<version>.jar`      NeoForge, single-line repository
 *  - `mcdebug-forge-<version>.jar`         Forge 1.20.1
 *  - `mcdebug-<loader>-<mc>-<version>.jar` every loader since the three Minecraft lines share one version
 *
 * Sources jars and unrelated mods (`mcdebug-config-1.0.jar`) are left alone.
 */
internal fun isMcDebugModJar(fileName: String): Boolean =
    !fileName.endsWith("-sources.jar") && STALE_MOD_JAR.matches(fileName)

private val STALE_MOD_JAR = Regex("""mcdebug(-neoforge|-forge|-fabric|-\d[\w.+-]*)*\.jar""")

/** Delete every other mcdebug mod jar in a mods directory (all naming schemes, see [isMcDebugModJar]). */
private fun removeOlderModJars(dir: Path, keep: Path): List<Path> {
    val stale = Files.list(dir).use { files ->
        files.filter { f ->
            isMcDebugModJar(f.fileName.toString()) && Files.isRegularFile(f) && !Files.isSameFile(f, keep)
        }.toList()
    }
    stale.forEach(Files::delete)
    return stale
}

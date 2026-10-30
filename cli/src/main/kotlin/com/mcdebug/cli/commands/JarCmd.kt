package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Classpath locations of the mod jars built in the same Gradle run as this CLI (see cli/build.gradle). */
internal const val EMBEDDED_MOD_JAR = "META-INF/mcdebug/mod.jar"
internal const val EMBEDDED_MOD_JAR_NEOFORGE = "META-INF/mcdebug/mod-neoforge.jar"

/**
 * Which loader's jar `mcdebug jar` writes. Both are embedded in this CLI at the same version.
 * The file name matters: NeoForge's is `mcdebug-neoforge-<version>.jar`, and a mods directory must
 * never hold both (same mod id -> the loader refuses to start).
 */
internal enum class ModJarLoader(val cliName: String, val resource: String) {
    FABRIC("fabric", EMBEDDED_MOD_JAR),
    NEOFORGE("neoforge", EMBEDDED_MOD_JAR_NEOFORGE);

    fun fileName(version: String): String = when (this) {
        FABRIC -> "mcdebug-$version.jar"
        NEOFORGE -> "mcdebug-neoforge-$version.jar"
    }

    companion object {
        fun parse(raw: String): ModJarLoader = entries.firstOrNull { it.cliName.equals(raw, ignoreCase = true) }
            ?: throw UsageError("unknown loader '$raw' (expected: ${entries.joinToString("|") { it.cliName }})")
    }
}

/**
 * Write the embedded mcdebug mod jar. It is the jar of this exact build, so it always matches
 * the CLI version and needs no network.
 */
class JarCmd : CliktCommand(
    name = "jar",
    help = "write one of the mcdebug mod JARs bundled with this CLI (same version, no download); " +
        "--output DIR (e.g. run/mods) also removes the other loader's / older mcdebug-*.jar there, " +
        "since two copies with the same mod id fail to load",
) {
    private val output by option("--output", help = "file or existing directory (default: ./<loader jar name>)")

    private val loaderName by option(
        "--loader",
        help = "which mod jar to write: fabric (default) or neoforge",
    ).choice("fabric", "neoforge").default("fabric")

    override fun run() {
        val written = writeEmbeddedModJar(output?.let(Path::of), loader = ModJarLoader.parse(loaderName))
        written.removed.forEach { System.err.println("removed older $it") }
        System.err.println("SHA256: ${written.sha256}")
        println(written.path)
    }
}

internal data class WrittenJar(val path: Path, val sha256: String, val removed: List<Path>)

internal fun writeEmbeddedModJar(
    output: Path?,
    version: String = cliVersion(),
    loader: ModJarLoader = ModJarLoader.FABRIC,
): WrittenJar {
    val name = loader.fileName(version)
    val target = when {
        output == null -> Path.of(name)
        Files.isDirectory(output) -> output.resolve(name)
        else -> output
    }
    val bytes = JarCmd::class.java.classLoader.getResourceAsStream(loader.resource)?.use { it.readAllBytes() }
        ?: throw UsageError(
            "this CLI build has no embedded ${loader.cliName} mod jar (${loader.resource}); " +
                "build it with ./gradlew :cli:shadowJar"
        )
    target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    val tmp = Files.createTempFile(target.toAbsolutePath().parent, ".mcdebug-", ".jar.part")
    Files.write(tmp, bytes)
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    val removed = if (output != null && Files.isDirectory(output)) removeOlderModJars(output, keep = target) else emptyList()
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return WrittenJar(target, sha, removed)
}

/**
 * Every other mcdebug mod jar in a mods directory: `mcdebug-<version>.jar` (Fabric) and
 * `mcdebug-neoforge-<version>.jar` (NeoForge). Both schemes are removed because a single mods
 * directory must only ever contain one of them (`modId=mcdebug` twice fails to load).
 */
private fun removeOlderModJars(dir: Path, keep: Path): List<Path> {
    val pattern = Regex("""mcdebug-(neoforge-)?\d[\w.+-]*\.jar""")
    val stale = Files.list(dir).use { files ->
        files.filter { f ->
            val n = f.fileName.toString()
            pattern.matches(n) && !n.endsWith("-sources.jar") && Files.isRegularFile(f) && !Files.isSameFile(f, keep)
        }.toList()
    }
    stale.forEach(Files::delete)
    return stale
}

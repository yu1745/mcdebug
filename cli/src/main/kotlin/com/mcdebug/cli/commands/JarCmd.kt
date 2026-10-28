package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Classpath location of the mod jar built in the same Gradle run as this CLI (see cli/build.gradle). */
internal const val EMBEDDED_MOD_JAR = "META-INF/mcdebug/mod.jar"

/**
 * Write the embedded mcdebug mod jar. It is the jar of this exact build, so it always matches
 * the CLI version and needs no network.
 */
class JarCmd : CliktCommand(
    name = "jar",
    help = "write the mcdebug mod JAR bundled with this CLI (same version, no download); " +
        "--output DIR (e.g. run/mods) also removes older mcdebug-*.jar there, since two copies fail to load",
) {
    private val output by option("--output", help = "file or existing directory (default: ./mcdebug-<version>.jar)")

    override fun run() {
        val written = writeEmbeddedModJar(output?.let(Path::of))
        written.removed.forEach { System.err.println("removed older $it") }
        System.err.println("SHA256: ${written.sha256}")
        println(written.path)
    }
}

internal data class WrittenJar(val path: Path, val sha256: String, val removed: List<Path>)

internal fun writeEmbeddedModJar(output: Path?, version: String = cliVersion()): WrittenJar {
    val name = "mcdebug-$version.jar"
    val target = when {
        output == null -> Path.of(name)
        Files.isDirectory(output) -> output.resolve(name)
        else -> output
    }
    val bytes = JarCmd::class.java.classLoader.getResourceAsStream(EMBEDDED_MOD_JAR)?.use { it.readAllBytes() }
        ?: throw UsageError("this CLI build has no embedded mod jar ($EMBEDDED_MOD_JAR); build it with ./gradlew :cli:shadowJar")
    target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    val tmp = Files.createTempFile(target.toAbsolutePath().parent, ".mcdebug-", ".jar.part")
    Files.write(tmp, bytes)
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    val removed = if (output != null && Files.isDirectory(output)) removeOlderModJars(output, keep = target) else emptyList()
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return WrittenJar(target, sha, removed)
}

/** Other mcdebug mod jars (`mcdebug-<version>.jar`, not -sources/-cli) in a mods directory. */
private fun removeOlderModJars(dir: Path, keep: Path): List<Path> {
    val pattern = Regex("""mcdebug-\d[\w.+-]*\.jar""")
    val stale = Files.list(dir).use { files ->
        files.filter { f ->
            val n = f.fileName.toString()
            pattern.matches(n) && !n.endsWith("-sources.jar") && Files.isRegularFile(f) && !Files.isSameFile(f, keep)
        }.toList()
    }
    stale.forEach(Files::delete)
    return stale
}

package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.choice
import java.nio.file.Files
import java.nio.file.Path

/** Classpath location of the agent skill of this CLI version (see cli/build.gradle). */
internal const val EMBEDDED_SKILLS = "META-INF/mcdebug/skills"

/** Install the coding-agent skill that matches this CLI version. */
class SkillCmd : CliktCommand(
    name = "skill",
    help = "install a bundled coding-agent skill, including its reference files; " +
        "default: mcdebug under ~/.agents/skills/mcdebug; use --name neoforge-fabric-port for the port workflow",
) {
    private val name by option("--name", help = "bundled skill to install")
        .choice("mcdebug", "neoforge-fabric-port").default("mcdebug")
    private val output by option("--output", help = "skill directory to install into")
    private val print by option("--print", help = "print the skill to stdout instead of writing it").flag()

    override fun run() {
        if (print) {
            print(skillText(name))
            return
        }
        val dir = (output?.let(Path::of) ?: defaultSkillDir(name = name)).toAbsolutePath().normalize()
        val paths = embeddedSkillResource("${name}.files").toString(Charsets.UTF_8).lineSequence()
            .filter(String::isNotBlank).toList()
        for (relative in paths) {
            val file = dir.resolve(relative).normalize()
            require(file.startsWith(dir) && file != dir) { "invalid bundled skill path: $relative" }
            Files.createDirectories(file.parent)
            Files.write(file, embeddedSkillResource("$name/$relative"))
            println(file)
        }
    }
}

internal fun skillText(name: String = "mcdebug"): String =
    embeddedSkillResource("$name/SKILL.md").toString(Charsets.UTF_8)

private fun embeddedSkillResource(relative: String): ByteArray =
    SkillCmd::class.java.classLoader.getResourceAsStream("$EMBEDDED_SKILLS/$relative")?.use { it.readAllBytes() }
        ?: throw UsageError("this CLI build has no embedded skill resource ($relative); build it with ./gradlew :cli:shadowJar")

/** Agent-neutral location shared by coding agents, not one agent's own config dir. */
internal fun defaultSkillDir(home: Path = Path.of(System.getProperty("user.home")), name: String = "mcdebug"): Path =
    home.resolve(".agents").resolve("skills").resolve(name)

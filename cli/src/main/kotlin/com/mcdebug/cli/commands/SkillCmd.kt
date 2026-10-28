package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Files
import java.nio.file.Path

/** Classpath location of the agent skill of this CLI version (see cli/build.gradle). */
internal const val EMBEDDED_SKILL = "META-INF/mcdebug/skill/SKILL.md"

/** Install the coding-agent skill that matches this CLI version. */
class SkillCmd : CliktCommand(
    name = "skill",
    help = "install the coding-agent skill of this CLI version (workflow + pitfalls for driving a game with " +
        "mcdebug) as <dir>/SKILL.md; default dir: ~/.agents/skills/mcdebug (the agent-neutral skills location)",
) {
    private val output by option("--output", help = "skill directory to write SKILL.md into")
    private val print by option("--print", help = "print the skill to stdout instead of writing it").flag()

    override fun run() {
        val text = skillText()
        if (print) {
            print(text)
            return
        }
        val dir = output?.let(Path::of) ?: defaultSkillDir()
        Files.createDirectories(dir)
        val file = dir.resolve("SKILL.md")
        Files.writeString(file, text)
        println(file)
    }
}

internal fun skillText(): String =
    SkillCmd::class.java.classLoader.getResourceAsStream(EMBEDDED_SKILL)?.use { String(it.readAllBytes(), Charsets.UTF_8) }
        ?: throw UsageError("this CLI build has no embedded skill ($EMBEDDED_SKILL); build it with ./gradlew :cli:shadowJar")

/** Agent-neutral location shared by coding agents, not one agent's own config dir. */
internal fun defaultSkillDir(home: Path = Path.of(System.getProperty("user.home"))): Path =
    home.resolve(".agents").resolve("skills").resolve("mcdebug")

package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Help text is rendered by clikt through mordant's markdown renderer, where a pair of
 * `$` in one string is parsed as math and throws `Unexpected token when parsing inlines`.
 * A single unpaired `$` in any command's help broke the **root** help too (the parent
 * prints each child's short help), i.e. `mcdebug --help` and `mcdebug -h` exited 1:
 * the CLI became undiscoverable. Guard every command in the tree.
 */
class HelpRenderingTest {

    private fun allCommands(): List<CliktCommand> {
        val root = buildCli()
        val out = ArrayList<CliktCommand>()
        val queue = ArrayDeque<CliktCommand>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            out += c
            queue.addAll(c.registeredSubcommands())
        }
        return out
    }

    @Test
    fun `every command renders its help without a markdown parse error`() {
        val failures = ArrayList<String>()
        for (command in allCommands()) {
            try {
                val help = command.getFormattedHelp()
                if (help.isNullOrBlank()) failures += "${command.commandName}: empty help"
            } catch (t: Throwable) {
                failures += "${command.commandName}: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `root help lists the subcommands and exit codes`() {
        val root = buildCli()
        val help = checkNotNull(root.getFormattedHelp())
        for (text in listOf("reflect", "gui", "session", "Exit Codes", "退出码")) {
            assertTrue(help.contains(text), "$text missing from root help")
        }
    }

    @Test
    fun `a help request parses without running the command`() {
        val request: PrintHelpMessage = assertThrows {
            buildCli().parse(listOf("reflect", "set", "--help"))
        }
        val command = checkNotNull(request.context).command
        assertEquals("set", command.commandName)
        assertTrue(checkNotNull(command.getFormattedHelp()).contains("ref"))
    }
}

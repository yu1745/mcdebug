package com.mcdebug.cli

import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.google.gson.JsonObject
import com.mcdebug.cli.session.StartRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SessionCommandsTest {
    @TempDir lateinit var temp: Path

    private fun startCli(action: (StartRequest) -> JsonObject): McDebugCli = McDebugCli().subcommands(
        SessionCommands().subcommands(SessionStartCmd { _, request -> action(request) }),
    )

    private fun ready(state: String = "client_ready") = JsonObject().apply { addProperty("state", state) }

    @Test
    fun `start defaults are passed to manager without launching a game`() {
        var request: StartRequest? = null
        startCli { request = it; ready() }.parse(listOf("session", "start", "dev"))
        val r = requireNotNull(request)
        assertEquals("dev", r.name)
        assertEquals(Path.of("."), r.project)
        assertEquals("runClient", r.task)
        assertNull(r.gameDir, "manager resolves default project/run")
        assertNull(r.world)
        assertNull(r.display)
        assertEquals("demand", r.render)
        assertEquals(854, r.width)
        assertEquals(480, r.height)
        assertEquals(180L, r.waitSeconds)
    }

    @Test
    fun `start accepts paths and save names with spaces and all launch flags`() {
        var request: StartRequest? = null
        startCli { request = it; ready("world_ready") }.parse(listOf(
            "session", "start", "dev", "--project", "project with space", "--task", ":core:runClient",
            "--game-dir", "game with space", "--world", "New World", "--render", "gui-only", "--display", ":99",
            "--width", "1280", "--height", "720", "--wait-seconds", "240",
        ))
        assertEquals(StartRequest("dev", Path.of("project with space"), ":core:runClient", Path.of("game with space"),
            "New World", "gui-only", ":99", 1280, 720, 240L), request)
    }

    @Test
    fun `invalid start flags fail before manager invocation`() {
        for (flags in listOf(
            listOf("--render", "unknown"), listOf("--width", "0"), listOf("--height", "-1"),
            listOf("--wait-seconds", "-1"),
        )) {
            assertThrows(UsageError::class.java) {
                startCli { throw AssertionError("manager must not run for invalid flags") }
                    .parse(listOf("session", "start", "dev") + flags)
            }
        }
    }

    @Test
    fun `startup failure and timeout are not reported as successful exits`() {
        for (state in listOf("start_timeout", "failed", "stopped")) {
            val error = assertThrows(CliExit::class.java) {
                startCli { ready(state).apply { addProperty("lastError", "test startup failure") } }
                    .parse(listOf("session", "start", "dev"))
            }
            assertEquals(1, error.code)
            assertEquals("test startup failure", error.message)
        }
    }

    @Test
    fun `stop parses force and finite wait and treats normal stop as success`() {
        var called = false
        McDebugCli().subcommands(SessionCommands().subcommands(SessionStopCmd { _, name, force, wait ->
            assertEquals("dev", name)
            assertTrue(force)
            assertEquals(15L, wait)
            called = true
            ready("stopped")
        })).parse(listOf("session", "stop", "dev", "--force", "--wait-seconds", "15"))
        assertTrue(called)
    }

    @Test
    fun `unconfirmed stop exits nonzero`() {
        val cli = McDebugCli().subcommands(SessionCommands().subcommands(SessionStopCmd { _, _, force, wait ->
            assertFalse(force)
            assertEquals(30L, wait)
            ready("stopping")
        }))
        assertEquals(1, assertThrows(CliExit::class.java) {
            cli.parse(listOf("session", "stop", "dev"))
        }.code)
    }

    @Test
    fun `management list does not resolve root session or open a client connection`() {
        buildCli().parse(listOf("--sessions-dir", temp.toString(), "--session", "does-not-exist", "session", "list"))
        assertFalse(Files.exists(temp.resolve("does-not-exist.json")))
        assertNull(batchClient)
        assertNull(batchClientPlane)
    }

    @Test
    fun `all management commands support help without resolving a session`() {
        for (command in listOf(emptyList(), listOf("start"), listOf("attach"), listOf("list"), listOf("status"), listOf("logs"), listOf("stop"))) {
            // CliktCommand.main handles help by exitProcess(0), which would kill the JUnit worker.
            val helpRequest = assertThrows(PrintHelpMessage::class.java) {
                buildCli().parse(listOf("--sessions-dir", temp.toString(), "--session", "does-not-exist", "session") + command + "--help")
            }
            val target = checkNotNull(helpRequest.context).command
            assertEquals(command.lastOrNull() ?: "session", target.commandName)
            val help = checkNotNull(target.getFormattedHelp())
            assertTrue(help.contains("Example:"), help)
            assertTrue(help.contains("mcdebug session"), help)
        }
    }

    @Test
    fun `attach requires game dir and logs reject invalid line counts without reading registry`() {
        assertThrows(UsageError::class.java) { buildCli().parse(listOf("session", "attach", "dev")) }
        assertThrows(UsageError::class.java) { buildCli().parse(listOf("session", "logs", "dev", "--lines", "-1")) }
    }
}

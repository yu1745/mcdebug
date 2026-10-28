package com.mcdebug.cli

import com.github.ajalt.clikt.core.UsageError
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

// System.out capture is process-global; no other parallel test may print or replace it meanwhile.
@Isolated
@Timeout(10)
class GuiDevCommandsTest {
    @TempDir lateinit var temp: Path

    private fun expectCall(args: List<String>, method: String, check: (JsonObject) -> Unit) {
        LoopbackRpcServer("client") { socket ->
            val request = readRequest(socket)
            assertEquals(method, request.get("method").asString)
            check(request.getAsJsonObject("params") ?: JsonObject())
            reply(socket, request, JsonObject().apply { addProperty("ok", true) })
        }.use { server ->
            capture { buildCli().parse(listOf("--client-tcp", server.options().tcp!!, "gui") + args) }
            server.await()
        }
    }

    @Test
    fun `follow-up flags turn input commands into one gui act round trip`() {
        expectCall(listOf("click", "--slot", "3", "--shot", "gui", "--annotate"), "gui.act") { p ->
            assertEquals("click", p["action"].asString)
            assertEquals(3, p.getAsJsonObject("params")["slot"].asInt)
            val shot = p.getAsJsonObject("screenshot")
            assertEquals("gui", shot["mode"].asString)
            assertTrue(shot["annotate"].asBoolean)
        }
        expectCall(listOf("type", "--text", "64", "--clear", "--screen"), "gui.act") { p ->
            assertEquals("type", p["action"].asString)
            assertTrue(p["screen"].asBoolean)
            assertFalse(p.has("screenshot"))
        }
        expectCall(listOf("key", "--key", "ENTER", "--shot", "window", "--draw-log"), "gui.act") { p ->
            assertEquals("ENTER", p.getAsJsonObject("params")["key"].asString)
            assertTrue(p.getAsJsonObject("screenshot")["drawLog"].asBoolean)
        }
    }

    @Test
    fun `hover scroll and open-screen map their targets`() {
        expectCall(listOf("hover", "--slot", "2"), "gui.hover") { p -> assertEquals(2, p["slot"].asInt) }
        expectCall(listOf("hover", "--gui", "160.5,30"), "gui.hover") { p ->
            assertEquals(160.5, p.getAsJsonArray("gui")[0].asDouble)
        }
        expectCall(listOf("hover", "--text", "Start", "--shot", "gui", "--hide-cursor"), "gui.act") { p ->
            assertEquals("hover", p["action"].asString)
            assertEquals("Start", p.getAsJsonObject("params")["text"].asString)
            assertEquals("hide", p.getAsJsonObject("screenshot")["cursor"].asString)
        }
        expectCall(listOf("scroll", "--widget", "4", "--dy", "-3", "--no-settle"), "gui.scroll") { p ->
            assertEquals(4, p["widget"].asInt)
            assertEquals(-3.0, p["dy"].asDouble)
            assertFalse(p["settle"].asBoolean)
            assertFalse(p.has("dx"))
        }
        expectCall(listOf("open-screen", "com.example.Fixtures#screen"), "gui.openScreen") { p ->
            assertEquals("com.example.Fixtures#screen", p["screen"].asString)
        }
        expectCall(listOf("open-screen", "inventory", "--shot", "gui"), "gui.act") { p ->
            assertEquals("openScreen", p["action"].asString)
            assertEquals("inventory", p.getAsJsonObject("params")["screen"].asString)
        }
        expectCall(listOf("screenshot", "--hide-cursor"), "gui.screenshot") { p -> assertEquals("hide", p["cursor"].asString) }
        expectCall(listOf("reload-screen", "--classes", "out", "--also", "com.example.Widget"), "gui.reloadScreen") { p ->
            assertTrue(p.getAsJsonArray("dirs")[0].asString.endsWith("out"))
            assertEquals("com.example.Widget", p.getAsJsonArray("also")[0].asString)
        }
        expectCall(listOf("reload-screen", "--classes", "out", "--shot", "gui"), "gui.act") { p ->
            assertEquals("reloadScreen", p["action"].asString)
            assertFalse(p.getAsJsonObject("params").has("also"))
        }
    }

    @Test
    fun `pointer usage errors happen before connecting`() {
        for (args in listOf(
            listOf("hover"),
            listOf("hover", "--slot", "1", "--widget", "2"),
            listOf("hover", "--gui", "1"),
            listOf("scroll", "--slot", "1"),
            listOf("hover", "--slot", "1", "--hide-cursor"),
        )) {
            assertThrows(UsageError::class.java, { buildCli().parse(listOf("--client-tcp", "127.0.0.1:1", "gui") + args) }, args.toString())
        }
    }

    @Test
    fun `plain click keeps gui click and exposes widget and no-settle`() {
        expectCall(listOf("click", "--widget", "4", "--no-settle", "--wait-ticks", "5"), "gui.click") { p ->
            assertEquals(4, p["widget"].asInt)
            assertFalse(p["settle"].asBoolean)
            assertEquals(5, p["waitTicks"].asInt)
        }
        expectCall(listOf("click", "--slot", "1"), "gui.click") { p ->
            assertEquals(1, p["slot"].asInt)
            assertFalse(p.has("settle"))
        }
    }

    @Test
    fun `screenshot dev flags and the new commands send explicit parameters`() {
        expectCall(listOf("screenshot", "--mode", "gui", "--annotate", "--draw-log"), "gui.screenshot") { p ->
            assertEquals("png", p["format"].asString)
            assertTrue(p["annotate"].asBoolean && p["drawLog"].asBoolean)
        }
        expectCall(listOf("screenshot", "--hash-only"), "gui.screenshot") { p -> assertEquals("none", p["format"].asString) }
        expectCall(listOf("settle", "--max-ticks", "12", "--quiet-ticks", "1"), "gui.settle") { p ->
            assertEquals(12, p["maxTicks"].asInt); assertEquals(1, p["quietTicks"].asInt)
        }
        expectCall(listOf("reinit"), "gui.reinit") { }
        expectCall(listOf("problems", "--since", "7", "--level", "error"), "gui.problems") { p ->
            assertEquals(7L, p["since"].asLong); assertEquals("error", p["level"].asString)
        }
        expectCall(listOf("act", "--action", "drag", "--params", """{"from":[1,2],"to":[3,4]}""", "--no-settle"), "gui.act") { p ->
            assertEquals("drag", p["action"].asString)
            assertFalse(p["settle"].asBoolean)
            assertEquals(2, p.getAsJsonObject("params")["from"].asJsonArray.size())
        }
    }

    @Test
    fun `invalid follow-up and hash-only combinations fail before any connection`() {
        for (args in listOf(
            listOf("click", "--slot", "1", "--annotate"),
            listOf("click", "--slot", "1", "--shot", "pixels"),
            listOf("act", "--params", "[1]"),
        )) assertThrows(UsageError::class.java) { buildCli().parse(listOf("--client-tcp", "127.0.0.1:1", "gui") + args) }
    }

    @Test
    fun `agent jar contains only the agent class and an attach manifest`() {
        val jar = AgentAttach.agentJar(temp)
        JarFile(jar.toFile()).use { file ->
            assertEquals(listOf("META-INF/MANIFEST.MF", "com/mcdebug/agent/InstrumentationAgent.class"),
                file.entries().toList().map { it.name }.sorted().reversed().let { listOf(it.first { n -> n.startsWith("META") }) + it.filter { n -> !n.startsWith("META") } })
            val attributes = file.manifest.mainAttributes
            assertEquals("com.mcdebug.agent.InstrumentationAgent", attributes.getValue("Agent-Class"))
            assertEquals("true", attributes.getValue("Can-Redefine-Classes"))
        }
    }

    @Test
    fun `instrumentation agent publishes its key for the mod`() {
        assertEquals("mcdebug.instrumentation", com.mcdebug.agent.InstrumentationAgent.KEY)
    }

    private fun capture(block: () -> Unit): String {
        val previous = System.out
        val buffer = ByteArrayOutputStream()
        try {
            PrintStream(buffer, true, Charsets.UTF_8).use { stream ->
                System.setOut(stream)
                block()
            }
        } finally {
            System.setOut(previous)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    @Suppress("unused")
    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject
}

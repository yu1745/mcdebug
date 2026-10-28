package com.mcdebug.client

import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcException
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** Pure pieces of the agent loop (settle, problems, annotation, draw-log geometry, hotswap scan, options). */
class GuiDevLogicTest {
    @TempDir lateinit var temp: Path

    @Test fun `quiet tracker ignores everything before the barrier and needs consecutive stable ticks`() {
        val t = QuietTracker(2)
        assertFalse(t.observe(1, barrierDone = false))
        assertFalse(t.observe(1, barrierDone = false))
        assertFalse(t.observe(1, barrierDone = true)) // first post-barrier tick: stable=1
        assertTrue(t.observe(1, barrierDone = true))
        val u = QuietTracker(2)
        assertFalse(u.observe(1, true))
        assertFalse(u.observe(2, true)) // change resets
        assertFalse(u.observe(2, true))
        assertTrue(u.observe(2, true))
    }

    @Test fun `zero quiet ticks settles on the first post-barrier observation`() {
        val t = QuietTracker(0)
        assertFalse(t.observe(5, false))
        assertTrue(t.observe(9, true))
        assertThrows(IllegalArgumentException::class.java) { QuietTracker(-1) }
    }

    @Test fun `problem buffer pages by cursor, filters errors and reports drops`() {
        val b = ProblemBuffer(3)
        val start = b.cursor()
        b.add(1, 0, "WARN", "a", "w1", null)
        b.add(2, 0, "ERROR", "b", "e1", "trace")
        assertEquals(listOf("w1", "e1"), b.since(start, false, 10).entries.map { it.message })
        assertEquals(listOf("e1"), b.since(start, true, 10).entries.map { it.message })
        val cursor = b.cursor()
        b.add(3, 0, "WARN", "c", "w2", null)
        assertEquals(listOf("w2"), b.since(cursor, false, 10).entries.map { it.message })
        b.add(4, 0, "FATAL", "d", "f1", null) // evicts w1
        val all = b.since(1, false, 10)
        assertEquals(listOf("e1", "w2", "f1"), all.entries.map { it.message })
        assertTrue(all.truncated)
        assertEquals(1, all.dropped)
        assertEquals(2, b.errorCount())
        val limited = b.since(1, false, 1)
        assertEquals(listOf("f1"), limited.entries.map { it.message })
        assertTrue(limited.truncated)
        assertEquals(b.cursor(), limited.next)
    }

    @Test fun `problem messages are bounded`() {
        val b = ProblemBuffer(2)
        b.add(0, 0, "WARN", "x", "m".repeat(10_000), "t".repeat(20_000))
        val e = b.since(1, false, 1).entries.single()
        assertTrue(e.message.length < 4100)
        assertTrue(e.thrown!!.length < 8100)
    }

    private class IntCanvas(override val width: Int, override val height: Int) : PixelCanvas {
        val pixels = IntArray(width * height) { 0xFF808080.toInt() }
        override fun get(x: Int, y: Int) = pixels[y * width + x]
        override fun set(x: Int, y: Int, argb: Int) { pixels[y * width + x] = argb }
    }

    @Test fun `annotator outlines boxes in png space, labels them and returns a legend`() {
        val canvas = IntCanvas(100, 60)
        // GUI (10,5) 16x16 at 2 px/unit with a crop origin of (4,2) -> PNG (16,8) 32x32.
        val legend = Annotator.draw(canvas, listOf(
            Annotator.Box("slot", 7, 10.0, 5.0, 16.0, 16.0, null),
            Annotator.Box("widget", 3, 500.0, 500.0, 10.0, 10.0, "off-screen"),
        ), 2.0, 2.0, 4.0, 2.0)
        assertEquals(1, legend.size())
        val entry = legend[0].asJsonObject
        assertEquals("slot", entry["kind"].asString)
        assertEquals(listOf(16, 8, 32, 32), entry["png"].asJsonArray.map { it.asInt })
        // Bottom-right border pixel is the slot colour; label background darkened the top-left corner.
        assertEquals(0xFF00E5FF.toInt(), canvas.get(47, 39))
        assertNotEquals(0xFF808080.toInt(), canvas.get(16, 8))
        assertEquals(0xFF808080.toInt(), canvas.get(60, 50)) // untouched
    }

    @Test fun `draw log rect applies the pose transform and normalises corners`() {
        val identity = DrawRecorder.rect(Matrix4f(), 10f, 20f, 4f, 2f, 0f).map { it.asFloat }
        assertEquals(listOf(4f, 2f, 6f, 18f), identity)
        val moved = DrawRecorder.rect(Matrix4f().translate(100f, 50f, 0f).scale(2f), 1f, 1f, 3f, 2f, 0f).map { it.asFloat }
        assertEquals(listOf(102f, 52f, 4f, 2f), moved)
    }

    @Test fun `class scan finds only class files modified after the cursor and names them`() {
        val dir = Files.createDirectories(temp.resolve("classes"))
        val old = Files.createDirectories(dir.resolve("a/b")).resolve("Old.class")
        val new = dir.resolve("a/b/New\$Inner.class")
        Files.write(old, byteArrayOf(1)); Files.write(new, byteArrayOf(2)); Files.write(dir.resolve("a/readme.txt"), byteArrayOf(3))
        Files.setLastModifiedTime(old, FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(new, FileTime.fromMillis(5_000))
        assertEquals(listOf("a.b.New\$Inner"), ClassScan.changed(listOf(dir), 2_000).map { it.name })
        assertEquals(listOf("a.b.New\$Inner", "a.b.Old"), ClassScan.changed(listOf(dir), 0).map { it.name })
        assertThrows(IllegalArgumentException::class.java) { ClassScan.changed(listOf(dir.resolve("missing")), 0) }
    }

    @Test fun `capture options validate combinations and map to RPC errors`() {
        val ok = CaptureOptions.parse(JsonObject().apply {
            addProperty("mode", "GUI"); addProperty("annotate", true); addProperty("drawLog", true)
        })
        assertEquals("gui", ok.mode)
        assertTrue(ok.annotate && ok.drawLog)
        assertEquals(1, ok.delayTicks)
        assertEquals(0, CaptureOptions.parse(null, 0).delayTicks)
        assertEquals("keep", ok.cursor)
        assertEquals("hide", CaptureOptions.parse(JsonObject().apply { addProperty("cursor", "HIDE") }).cursor)
        for (bad in listOf(
            JsonObject().apply { addProperty("format", "none"); addProperty("annotate", true) },
            JsonObject().apply { addProperty("format", "none"); addProperty("inline", true) },
            JsonObject().apply { addProperty("format", "jpeg") },
            JsonObject().apply { addProperty("mode", "world") },
            JsonObject().apply { addProperty("delayTicks", 5000) },
            JsonObject().apply { addProperty("cursor", "center") },
        )) assertThrows(RpcException::class.java) { CaptureOptions.parse(bad) }
    }

    private class SchemaSample(val a: Int) {
        private val lambda = { x: Int -> x + a }
        fun twice(): Int = lambda(a) * 2
    }

    @Test
    fun `schema diff agrees between bytes and the loaded class and names changes`() {
        val bytes = SchemaSample::class.java.getResourceAsStream("GuiDevLogicTest\$SchemaSample.class")!!.use { it.readBytes() }
        val fromBytes = SchemaDiff.of(bytes)
        val loaded = SchemaDiff.of(SchemaSample::class.java)
        assertEquals(emptyList<String>(), SchemaDiff.changes(loaded, fromBytes))
        val changed = SchemaDiff.Members(fromBytes.methods + "extra()V", fromBytes.fields - fromBytes.fields.first())
        val changes = SchemaDiff.changes(loaded, changed)
        assertEquals(listOf("added method extra()V", "removed field ${fromBytes.fields.first()}"), changes)
    }
}

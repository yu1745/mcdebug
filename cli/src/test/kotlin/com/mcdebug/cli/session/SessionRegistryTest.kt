package com.mcdebug.cli.session

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Timeout(10)
class SessionRegistryTest {
    @TempDir lateinit var temp: Path

    private fun record(name: String = "test") = SessionRecord(name = name, registryDir = temp.toString(),
        owned = true, sessionToken = "token", gameDir = temp.resolve("run").toString())

    @Test fun `plain string paths and timestamps round trip without JDK reflection`() {
        val registry = SessionRegistry(temp)
        val original = record().copy(project = temp.resolve("项目 with spaces").toString())
        registry.locked { write(original) }
        assertEquals(original, registry.locked { read("test") })
        assertEquals(original.gameDir, original.toJson().get("gameDir").asString)
        assertTrue(original.toJson().get("createdAt").isJsonPrimitive)
    }

    @Test fun `explicit JSON round trips all nested optional and nullable fields`() {
        val original = record().copy(project = temp.resolve("项目").toString(), task = ":core:runClient",
            world = "World 世界", render = "demand", display = ":1", width = 854, height = 480,
            state = "world_ready", logPath = temp.resolve("stdout.log").toString(),
            launcher = SessionProcess(11, "2026-01-01T00:00:00Z"),
            wrapper = SessionProcess(22, "2026-01-01T00:00:01Z"),
            process = com.mcdebug.cli.RpcProcessIdentity(33, "2026-01-01T00:00:02Z", "instance", "token", temp.resolve("run").toString()),
            clientSocket = temp.resolve("client.sock").toString(), serverSocket = temp.resolve("server.sock").toString(),
            lastError = "中文😀")
        val registry = SessionRegistry(temp)
        registry.locked { write(original) }
        assertEquals(original, registry.locked { read("test") })
        assertEquals(original, SessionRecord.fromJson(original.toJson()))
        val nullable = record().copy(owned = false, sessionToken = "")
        assertTrue(nullable.toJson().get("process").isJsonNull)
        assertEquals(nullable, SessionRecord.fromJson(nullable.toJson()))
    }

    @Test fun `invalid names cannot escape registry or collide with lock files`() {
        val registry = SessionRegistry(temp)
        for (name in listOf("", ".", "..", "../escape", "/absolute", "x/y", "x\\y", "-option", "a".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) { registry.read(name) }
        }
        for (name in listOf("a", "a-b.c_d", "9")) registry.locked { write(record(name)) }
        assertEquals(3, registry.locked { records().size })
    }

    @Test fun `CAS rejects old generations even if attached session token is empty`() {
        val registry = SessionRegistry(temp)
        val first = record().copy(owned = false, sessionToken = "")
        val replacement = first.copy(recordId = "new-generation", state = "client_ready")
        registry.locked { write(first); write(replacement) }
        assertNull(registry.cas("test", first.recordId) { it.copy(state = "stopped") })
        assertEquals(replacement, registry.locked { read("test") })
    }

    @Test fun `readers only see complete JSON during repeated atomic replacement`() {
        val registry = SessionRegistry(temp)
        val original = record()
        registry.locked { write(original) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writing = executor.submit {
                repeat(60) { registry.locked { write(original.copy(lastError = "中文😀".repeat(it + 1))) } }
            }
            repeat(150) {
                val json = JsonParser.parseString(Files.readString(temp.resolve("test.json"))).asJsonObject
                assertEquals("test", json.get("name").asString)
            }
            writing.get(5, TimeUnit.SECONDS)
            Files.newDirectoryStream(temp, "*.tmp").use { assertFalse(it.iterator().hasNext()) }
        } finally { executor.shutdownNow() }
    }

    @Test fun `gameDir lock rejects another caller while registry remains usable`() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val held = executor.submit {
                fileLock(temp.resolve("run/mcdebug/mdb.lock"), false) {
                    entered.countDown()
                    assertTrue(release.await(3, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) {
                fileLock(temp.resolve("run/mcdebug/mdb.lock"), true) { fail<Unit>("must not acquire busy lock") }
            }
            SessionRegistry(temp.resolve("registry")).locked { write(record()) }
            release.countDown()
            held.get(3, TimeUnit.SECONDS)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `corrupt metadata is diagnosed rather than silently replaced`() {
        Files.writeString(temp.resolve("test.json"), "{truncated")
        assertThrows(IllegalStateException::class.java) { SessionRegistry(temp).locked { read("test") } }
    }
}

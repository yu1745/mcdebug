package com.mcdebug.cli.session

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** The registry lock protects only reads/CAS/atomic writes, never RPC or startup waiting. */
internal class SessionRegistry(directory: Path) {
    val directory: Path = canonicalPath(directory)
    init { Files.createDirectories(this.directory) }

    fun <T> locked(action: SessionRegistry.() -> T): T = fileLock(directory.resolve("registry.lock"), false) { action() }
    fun read(name: String): SessionRecord? {
        validateName(name)
        return readRecord(directory.resolve("$name.json"))?.also {
            check(it.name == name && it.schemaVersion == 1) { "invalid session record: $name" }
        }
    }
    fun records(): List<SessionRecord> = Files.newDirectoryStream(directory, "*.json").use { paths ->
        paths.map { path -> read(path.fileName.toString().removeSuffix(".json"))!! }.sortedBy { it.name }
    }
    fun write(record: SessionRecord) {
        validateName(record.name)
        atomicWrite(directory.resolve("${record.name}.json"), sessionGson.toJson(record.toJson()))
    }
    fun delete(name: String) {
        validateName(name)
        Files.deleteIfExists(directory.resolve("$name.json"))
    }
    fun cas(name: String, token: String, transform: (SessionRecord) -> SessionRecord): SessionRecord? = locked {
        val old = read(name) ?: return@locked null
        if (old.recordId != token) return@locked null
        val next = transform(old)
        write(next)
        next
    }
}

/** Resolve symlinked existing ancestors even if the final gameDir/registry does not exist yet. */
internal fun canonicalPath(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    if (Files.exists(absolute)) return absolute.toRealPath()
    val parent = absolute.parent ?: return absolute
    return canonicalPath(parent).resolve(absolute.fileName)
}

internal fun readRecord(path: Path): SessionRecord? = if (Files.exists(path)) {
    try {
        val json = com.google.gson.JsonParser.parseString(Files.readString(path)).asJsonObject
        SessionRecord.fromJson(json)
    }
    catch (e: Exception) { throw IllegalStateException("cannot read session record $path", e) }
} else null

internal fun atomicWrite(path: Path, text: String) {
    Files.createDirectories(path.parent)
    val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
    try {
        FileChannel.open(temporary, WRITE).use { channel ->
            val bytes = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        // No non-atomic fallback: an unsupported filesystem must fail, not expose torn JSON.
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
}

private val localLocks = ConcurrentHashMap<Path, ReentrantLock>()
internal fun <T> fileLock(path: Path, failIfBusy: Boolean, action: () -> T): T {
    val key = canonicalPath(path)
    Files.createDirectories(key.parent)
    val local = localLocks.computeIfAbsent(key) { ReentrantLock() }
    if (failIfBusy) check(local.tryLock()) { "session operation already in progress for $path" }
    else local.lock()
    try {
        return FileChannel.open(key, CREATE, WRITE).use { channel ->
            val lock = try { if (failIfBusy) channel.tryLock() else channel.lock() }
            catch (_: OverlappingFileLockException) { null }
            check(lock != null) { "session operation already in progress for $path" }
            lock.use { action() }
        }
    } finally { local.unlock() }
}

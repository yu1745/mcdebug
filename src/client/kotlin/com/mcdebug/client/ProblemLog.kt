package com.mcdebug.client

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mcdebug.McDebugMod
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property

/** Bounded, thread-safe ring of problems; pure so it is unit-testable without log4j wiring. */
class ProblemBuffer(private val capacity: Int) {
    data class Entry(
        val seq: Long,
        val timeMillis: Long,
        val clientTick: Long,
        val level: String,
        val source: String,
        val message: String,
        val thrown: String?,
    ) {
        val isError: Boolean get() = level == "ERROR" || level == "FATAL"

        fun toJson(): JsonObject = JsonObject().apply {
            addProperty("seq", seq)
            addProperty("timeMillis", timeMillis)
            addProperty("clientTick", clientTick)
            addProperty("level", level)
            addProperty("source", source)
            addProperty("message", message)
            thrown?.let { addProperty("thrown", it) }
        }
    }

    private val entries = ArrayDeque<Entry>()
    private var nextSeq = 1L
    private var dropped = 0L

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun add(timeMillis: Long, clientTick: Long, level: String, source: String, message: String, thrown: String?): Long {
        if (entries.size == capacity) {
            entries.removeFirst()
            dropped++
        }
        val entry = Entry(nextSeq++, timeMillis, clientTick, level, source, truncate(message, 4000), thrown?.let { truncate(it, 8000) })
        entries.addLast(entry)
        return entry.seq
    }

    /** The sequence the next entry will get; pass it back as `since` to read only newer problems. */
    @Synchronized
    fun cursor(): Long = nextSeq

    @Synchronized
    fun errorCount(): Int = entries.count { it.isError }

    /** Entries with seq >= [since], oldest first; [truncated] tells the caller entries were skipped. */
    @Synchronized
    fun since(since: Long, errorsOnly: Boolean, limit: Int): Page {
        val matching = entries.filter { it.seq >= since && (!errorsOnly || it.isError) }
        val page = matching.takeLast(limit)
        val firstKept = entries.firstOrNull()?.seq ?: nextSeq
        return Page(page, nextSeq, matching.size > page.size || (since in 1 until firstKept && dropped > 0), dropped)
    }

    data class Page(val entries: List<Entry>, val next: Long, val truncated: Boolean, val dropped: Long) {
        fun toJson(): JsonObject = JsonObject().apply {
            add("entries", JsonArray().apply { entries.forEach { add(it.toJson()) } })
            addProperty("next", next)
            addProperty("truncated", truncated)
            addProperty("dropped", dropped)
        }
    }

    private fun truncate(value: String, max: Int) = if (value.length <= max) value else value.take(max) + "…(truncated)"
}

/**
 * WARN+ log events of the whole client JVM (vanilla, mods, the integrated server)
 * plus render faults caught by [ScreenGuard], so an agent sees "missing texture" or
 * a render exception in the RPC answer instead of grepping latest.log.
 */
object ProblemLog {
    val buffer = ProblemBuffer(500)
    @Volatile private var installed = false

    fun install() {
        if (installed) return
        try {
            val context = LogManager.getContext(false) as LoggerContext
            val appender = object : AbstractAppender("mcdebug-problems", null, null, true, Property.EMPTY_ARRAY) {
                override fun append(event: LogEvent) {
                    if (!event.level.isMoreSpecificThan(Level.WARN)) return
                    buffer.add(event.timeMillis, ClientTasks.currentTick(), event.level.name(), event.loggerName ?: "",
                        event.message?.formattedMessage ?: "", event.thrown?.let(::describe))
                }
            }
            appender.start()
            context.configuration.rootLogger.addAppender(appender, Level.WARN, null)
            context.updateLoggers()
            installed = true
        } catch (t: Throwable) {
            // Diagnostics only: a foreign logging backend must not stop the client.
            McDebugMod.LOGGER.warn("mcdebug problem log unavailable: {}", t.toString())
        }
    }

    val available: Boolean get() = installed

    fun record(level: String, source: String, message: String, thrown: Throwable?) {
        buffer.add(System.currentTimeMillis(), ClientTasks.currentTick(), level, source, message, thrown?.let(::describe))
    }

    fun statusJson(): JsonObject = JsonObject().apply {
        addProperty("available", installed)
        addProperty("next", buffer.cursor())
        addProperty("errors", buffer.errorCount())
    }

    /** Class, message and the first frames (plus causes): enough to locate the bug, bounded in size. */
    fun describe(t: Throwable): String = buildString {
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 4) {
            if (depth > 0) append("\nCaused by: ")
            append(current.javaClass.name).append(": ").append(current.message ?: "")
            current.stackTrace.take(if (depth == 0) 12 else 4).forEach { append("\n\tat ").append(it) }
            current = current.cause?.takeIf { it !== current }
            depth++
        }
    }
}

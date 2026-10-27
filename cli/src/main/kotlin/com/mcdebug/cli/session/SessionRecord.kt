package com.mcdebug.cli.session

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.mcdebug.cli.RpcProcessIdentity
import java.nio.file.Path
import java.time.Instant

fun defaultRegistryDir(): Path = System.getenv("MCDEBUG_SESSIONS_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
    ?: (System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".local", "state")).resolve("mcdebug/sessions")

data class StartRequest(
    val name: String,
    val project: Path = Path.of("."),
    val task: String = "runClient",
    val gameDir: Path? = null,
    val world: String? = null,
    val render: String = "demand",
    val display: String? = null,
    val width: Int = 854,
    val height: Int = 480,
    val waitSeconds: Long = 180,
)

data class SessionProcess(val pid: Long, val startTime: String) {
    init { require(pid > 0) { "invalid PID" }; Instant.parse(startTime) }
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("pid", pid)
        addProperty("startTime", startTime)
    }
}

/** Persist only ordinary JVM values: in particular, never reflect on Path or Instant with Gson. */
data class SessionRecord(
    val schemaVersion: Int = 1,
    val recordId: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val registryDir: String,
    val owned: Boolean,
    val sessionToken: String,
    val gameDir: String,
    val project: String? = null,
    val task: String? = null,
    val world: String? = null,
    val render: String? = null,
    val display: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = createdAt,
    val state: String = "starting",
    val logPath: String? = null,
    val launcher: SessionProcess? = null,
    val wrapper: SessionProcess? = null,
    val process: RpcProcessIdentity? = null,
    val clientSocket: String? = null,
    val serverSocket: String? = null,
    val lastError: String? = null,
) {
    /** Explicit JSON keeps the same schema usable by the Graal native CLI without reflection metadata. */
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", schemaVersion)
        addProperty("recordId", recordId)
        addProperty("name", name)
        addProperty("registryDir", registryDir)
        addProperty("owned", owned)
        addProperty("sessionToken", sessionToken)
        addProperty("gameDir", gameDir)
        addProperty("project", project)
        addProperty("task", task)
        addProperty("world", world)
        addProperty("render", render)
        addProperty("display", display)
        addProperty("width", width)
        addProperty("height", height)
        addProperty("createdAt", createdAt)
        addProperty("updatedAt", updatedAt)
        addProperty("state", state)
        addProperty("logPath", logPath)
        add("launcher", launcher?.toJson())
        add("wrapper", wrapper?.toJson())
        add("process", process?.toJson())
        addProperty("clientSocket", clientSocket)
        addProperty("serverSocket", serverSocket)
        addProperty("lastError", lastError)
    }

    companion object {
        fun fromJson(json: JsonObject): SessionRecord {
            fun optional(key: String) = json.get(key)?.takeUnless { it.isJsonNull }
            fun process(key: String): SessionProcess? = optional(key)?.asJsonObject?.let {
                SessionProcess(it.get("pid").asLong, it.get("startTime").asString)
            }
            val record = SessionRecord(
                schemaVersion = json.get("schemaVersion").asInt,
                recordId = json.get("recordId").asString,
                name = json.get("name").asString,
                registryDir = json.get("registryDir").asString,
                owned = json.get("owned").asBoolean,
                sessionToken = json.get("sessionToken").asString,
                gameDir = json.get("gameDir").asString,
                project = optional("project")?.asString,
                task = optional("task")?.asString,
                world = optional("world")?.asString,
                render = optional("render")?.asString,
                display = optional("display")?.asString,
                width = optional("width")?.asInt,
                height = optional("height")?.asInt,
                createdAt = json.get("createdAt").asString,
                updatedAt = json.get("updatedAt").asString,
                state = json.get("state").asString,
                logPath = optional("logPath")?.asString,
                launcher = process("launcher"),
                wrapper = process("wrapper"),
                process = optional("process")?.asJsonObject?.let { RpcProcessIdentity.fromJson(it) },
                clientSocket = optional("clientSocket")?.asString,
                serverSocket = optional("serverSocket")?.asString,
                lastError = optional("lastError")?.asString,
            )
            check(record.schemaVersion == 1 && record.recordId.isNotBlank()) { "invalid record schema" }
            validateName(record.name)
            check(Path.of(record.gameDir).isAbsolute && Path.of(record.registryDir).isAbsolute) { "record paths must be absolute" }
            Instant.parse(record.createdAt)
            Instant.parse(record.updatedAt)
            check(!record.owned || record.sessionToken.isNotBlank()) { "owned record requires a launch token" }
            return record
        }
    }
}

internal val sessionGson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
internal fun validateName(name: String) {
    require(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}").matches(name)) {
        "session name must be 1..64 letters, digits, dots, underscores or hyphens, starting with a letter/digit"
    }
}

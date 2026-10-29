package com.mcdebug.cli.session

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mcdebug.cli.RpcClientOptions
import com.mcdebug.cli.RpcProcessIdentity
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class SessionManager(
    registryDir: Path = defaultRegistryDir(),
    private val runtime: SessionRuntime = LocalSessionRuntime(),
) {
    private val registry = SessionRegistry(registryDir)

    fun start(request: StartRequest): JsonObject {
        val config = normalize(request)
        val game = Path.of(config.gameDir)
        var launch = false
        var record = gameLocked(game, true) {
            registry.locked {
                read(config.name)?.let { old ->
                    if (occupied(old)) {
                        check(old.owned && sameConfig(old, config)) { "session ${old.name} is live with a different configuration" }
                        check(old.state !in setOf("stopping", "stopped")) { "session ${old.name} is stopping" }
                        return@locked old
                    }
                }
                check(records().none { it.gameDir == config.gameDir && occupied(it) }) { "gameDir already has a live session" }
                val owner = readRecord(ownerPath(game))
                check(owner == null || !occupied(owner)) { "gameDir is owned by session ${owner?.name} (${owner?.registryDir})" }
                val discovery = discovery(game)
                check(discovery == null || !alive(discovery.process)) { "gameDir already has a live external client; use session attach" }
                write(config)
                writeOwner(config)
                launch = true
                config
            }
        }
        if (launch) {
            try {
                // The gameDir lock closes the reservation/launch/identity-publication window;
                // no global registry lock is held while starting the wrapper.
                record = gameLocked(game, false) {
                    val current = registry.locked { read(record.name)!! }
                    if (current.recordId != record.recordId || current.state != "starting") return@gameLocked current
                    val wrapper = runtime.launch(launchSpec(current))
                    mutateLocked(current) { it.copy(wrapper = wrapper) } ?: current
                }
            } catch (e: Exception) {
                return update(record, setOf("starting")) { it.copy(state = "failed", lastError = "launch failed: ${e.message}") }.toJson()
            }
        }
        val deadline = deadline(request.waitSeconds)
        while (true) {
            record = refresh(record)
            if (record.state in setOf("stopping", "stopped", "failed", "dead")) return record.toJson()
            if (record.state == "world_ready" || (record.world == null && record.state == "client_ready")) return record.toJson()
            if (runtime.nanoTime() >= deadline) {
                return update(record, setOf("starting", "client_ready", "start_timeout")) {
                    it.copy(state = "start_timeout", lastError = "startup wait timed out; the launch may still be running; inspect status/logs or stop explicitly")
                }.toJson()
            }
            runtime.pause(100)
        }
    }

    fun attach(name: String, gameDir: Path): JsonObject {
        validateName(name)
        val game = canonicalPath(gameDir)
        val found = checkNotNull(discovery(game)) { "no client.json in $game" }
        check(alive(found.process)) { "client PID/startTime is no longer live" }
        val observed = runtime.inspect(options(found.socket, "client", found.process))
        check(observed.process == found.process) { "client identity changed during attach" }
        check(alive(found.process)) { "client exited during attach" }
        val record = SessionRecord(name = name, registryDir = registry.directory.toString(), owned = false,
            sessionToken = found.process.sessionToken, gameDir = game.toString(), process = found.process,
            clientSocket = found.socket, serverSocket = serverSocket(game), state = readyState(observed.status),
            logPath = game.resolve("logs/latest.log").toString())
        return gameLocked(game, false) {
            registry.locked {
                val old = read(name)
                check(old == null || !occupied(old) || (!old.owned && old.process == record.process)) { "session $name is already live" }
                write(record)
                record.toJson()
            }
        }
    }

    fun list(): JsonArray = JsonArray().apply {
        registry.locked { records() }.forEach { add(refresh(it).toJson()) }
    }

    fun status(name: String): JsonObject = refresh(requireRecord(name)).toJson()

    fun resolve(name: String, plane: String, timeoutMs: Int = 5000, requestTimeoutMs: Long? = null): RpcClientOptions {
        require(plane == "client" || plane == "server") { "unknown plane: $plane" }
        var record = requireRecord(name)
        check(record.state !in setOf("stopped", "stopping", "failed", "dead")) { "session $name is ${record.state}" }
        val current = checkNotNull(discovery(Path.of(record.gameDir))) { "session client discovery is missing" }
        check(matches(record, current.process)) { "session client has been replaced; refusing to follow a different instance" }
        val identity = current.process
        check(alive(identity)) { "session $name client PID/startTime is no longer live" }
        if (record.process == null) record = update(record, setOf("starting", "start_timeout")) {
            it.copy(process = identity, clientSocket = current.socket)
        }
        check(record.process == identity && record.state !in setOf("stopping", "stopped", "failed", "dead")) { "session changed during resolution" }
        val socket = if (plane == "client") current.socket else
            checkNotNull(serverSocket(Path.of(record.gameDir))) { "session has no integrated server endpoint (not in a singleplayer world)" }
        // The actual command's one connection validates the full identity in
        // server.ready. No gui.status/main-thread probe or second connection here.
        return options(socket, plane, identity, timeoutMs, requestTimeoutMs)
    }

    fun stop(name: String, force: Boolean = false, waitSeconds: Long = 30): JsonObject {
        require(waitSeconds >= 0) { "waitSeconds must be >= 0" }
        var record = requireRecord(name)
        check(record.owned) { "attached sessions cannot be stopped, including with --force" }
        if (record.state == "stopped") return record.toJson()
        val game = Path.of(record.gameDir)
        record = gameLocked(game, false) {
            val current = registry.locked { read(name)!! }
            check(current.recordId == record.recordId && current.owned) { "session changed before stop" }
            val found = discovery(game)
            if (found != null && alive(found.process)) {
                check(matches(current, found.process)) { "client identity does not match owned session; refusing stop" }
            }
            val identity = current.process ?: found?.process?.takeIf { alive(it) && matches(current, it) }
            if (identity != null && alive(identity)) {
                val socket = found?.socket ?: checkNotNull(current.clientSocket) { "missing client socket" }
                val verified = try {
                    runtime.verify(options(socket, "client", identity))
                } catch (e: Exception) {
                    if (e is IllegalArgumentException) throw e // a completed but mismatched handshake is never forceable
                    // A hung/stopping client may have already closed RPC. Force is
                    // still safe only with the exact persisted JVM identity in both
                    // discovery and ProcessHandle; never infer it from wrapper PID.
                    check(force && found?.process == identity && alive(identity)) {
                        "cannot verify client RPC identity before stop: ${e.message}"
                    }
                    identity
                }
                check(verified == identity) { "client identity changed before stop" }
            }
            // Publish intent BEFORE releasing the gameDir lock. Old start polls can no
            // longer move this token back to ready, and a late bootstrap sees the marker.
            atomicWrite(game.resolve("mcdebug/session-stop.json"), JsonObject().apply {
                addProperty("sessionToken", current.sessionToken)
            }.toString())
            mutateLocked(current) { it.copy(state = "stopping", process = identity,
                clientSocket = found?.socket ?: it.clientSocket, lastError = null) }!!
        }
        val identity = record.process
        if (identity == null) {
            record.wrapper?.takeIf(runtime::isAlive)?.let { runtime.terminate(it, false) }
            return update(record, setOf("stopping")) { it.copy(lastError =
                "stop token published and own wrapper cancelled; awaiting any late client bootstrap to observe the token") }.toJson()
        }
        if (alive(identity)) {
            try { runtime.requestStop(options(checkNotNull(record.clientSocket), "client", identity)) }
            catch (e: Exception) {
                record = update(record, setOf("stopping")) { it.copy(lastError = "normal stop request: ${e.message}") }
            }
        }
        val until = deadline(waitSeconds)
        while (alive(identity) && runtime.nanoTime() < until) runtime.pause(100)
        if (alive(identity) && force) {
            // gui.stop can close RPC before saving has finished. Recheck the full
            // discovery identity and PID/startTime, not RPC availability, before force.
            check(discovery(game)?.process == identity) { "client discovery identity changed before force" }
            check(alive(identity)) { "client PID/startTime changed before force" }
            runtime.terminate(SessionProcess(identity.pid, identity.startTime), true)
            val forceConfirmationDeadline = runtime.nanoTime() + 1_000_000_000L
            while (alive(identity) && runtime.nanoTime() < forceConfirmationDeadline) runtime.pause(25)
        }
        return update(record, setOf("stopping")) {
            if (!alive(identity)) it.copy(state = "stopped", lastError = null)
            else it.copy(lastError = "client is still stopping; inspect status/logs")
        }.toJson()
    }

    /** Reverse byte scan is bounded in memory and preserves complete UTF-8 lines. */
    fun logs(name: String, lines: Int = 80): String {
        require(lines in 0..10_000) { "lines must be between 0 and 10000" }
        val path = requireRecord(name).logPath ?: return ""
        if (lines == 0 || !Files.isRegularFile(Path.of(path))) return ""
        RandomAccessFile(path, "r").use { file ->
            val end = file.length()
            var position = end
            var count = 0
            while (position > 0) {
                position--
                file.seek(position)
                if (file.readByte().toInt() == 10 && position != end - 1 && ++count == lines) { position++; break }
                check(end - position <= 16L * 1024 * 1024) { "requested log tail exceeds 16 MiB; use fewer lines" }
            }
            val bytes = ByteArray((end - position).toInt())
            file.seek(position)
            file.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }

    private fun normalize(request: StartRequest): SessionRecord {
        validateName(request.name)
        require(request.waitSeconds in 0..86_400) { "waitSeconds must be between 0 and 86400" }
        require(request.width in 1..16_384 && request.height in 1..16_384) { "invalid window dimensions" }
        require(request.render in setOf("continuous", "demand", "gui-only")) { "unknown render mode" }
        require(Regex(":?(?:[A-Za-z0-9_.-]+:)*runClient").matches(request.task)) { "task must be runClient or a qualified :module:runClient task" }
        require(request.gameDir != null || request.task in setOf("runClient", ":runClient")) { "submodule runClient requires explicit gameDir" }
        val project = canonicalPath(request.project)
        require(Files.isDirectory(project)) { "project is not a directory" }
        require(Files.isRegularFile(project.resolve("gradlew"))) { "target project has no Gradle wrapper" }
        val game = canonicalPath(request.gameDir ?: project.resolve("run"))
        request.world?.let { world ->
            require(world.isNotBlank() && world !in setOf(".", "..") && world.none { it == '/' || it == '\\' || it.code < 32 }) {
                "world must be a saves directory name, not a path"
            }
            val saves = game.resolve("saves")
            val directory = saves.resolve(world)
            require(Files.isDirectory(directory)) { "world does not exist: $world (worlds are never created automatically)" }
            require(directory.toRealPath().startsWith(canonicalPath(saves))) { "world resolves outside saves" }
        }
        val token = UUID.randomUUID().toString()
        return SessionRecord(name = request.name, registryDir = registry.directory.toString(), owned = true,
            sessionToken = token, gameDir = game.toString(), project = project.toString(), task = request.task,
            world = request.world, render = request.render, display = request.display ?: System.getenv("DISPLAY"),
            width = request.width, height = request.height, launcher = runtime.currentProcess(),
            logPath = registry.directory.resolve("${request.name}-$token.log").toString())
    }

    private fun launchSpec(record: SessionRecord): SessionLaunch {
        check(System.getProperty("os.name").lowercase().contains("linux")) { "session launch currently requires Linux setsid" }
        val args = mutableListOf("--gameDir", record.gameDir, "--width", record.width.toString(), "--height", record.height.toString())
        record.world?.let { args.addAll(listOf("--quickPlaySingleplayer", it)) }
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val command = listOf("setsid", "--wait", "/bin/sh", Path.of(record.project!!).resolve("gradlew").toString(),
            record.task!!, "--console=plain", "--args=" + args.joinToString(" ", transform = ::quote))
        val env = mutableMapOf("MCDEBUG_SESSION_TOKEN" to record.sessionToken,
            "MCDEBUG_CLIENT_RENDER_MODE" to record.render!!, "MCDEBUG_TCP_PORT" to "0", "MCDEBUG_TCP_ENABLED" to "false",
            "MCDEBUG_CLIENT_TCP_ENABLED" to "false", "MCDEBUG_SOCKET" to Path.of(record.gameDir).resolve("mcdebug/socket").toString(),
            "MCDEBUG_CLIENT_SOCKET" to Path.of(record.gameDir).resolve("mcdebug/clientSocket").toString())
        record.display?.let { env["DISPLAY"] = it }
        return SessionLaunch(command, Path.of(record.project), env, Path.of(record.logPath!!))
    }

    private fun sameConfig(a: SessionRecord, b: SessionRecord): Boolean =
        a.project == b.project && a.task == b.task && a.gameDir == b.gameDir && a.world == b.world &&
            a.render == b.render && a.display == b.display && a.width == b.width && a.height == b.height

    private fun occupied(record: SessionRecord): Boolean =
        record.process?.let(::alive) == true || record.wrapper?.let(runtime::isAlive) == true ||
            (record.process == null && record.state == "stopping") ||
            (record.state in setOf("starting", "start_timeout", "client_ready") && record.launcher?.let(runtime::isAlive) == true)

    private fun refresh(snapshot: SessionRecord): SessionRecord {
        var record = registry.locked { read(snapshot.name) } ?: return snapshot
        if (record.recordId != snapshot.recordId) return record
        if (record.state in setOf("stopped", "failed", "dead")) return record
        if (record.state == "stopping") {
            if (record.process != null && !alive(record.process)) return update(record, setOf("stopping")) { it.copy(state = "stopped", lastError = null) }
            if (record.process == null) {
                // A matching late discovery is evidence that bootstrap actually ran.
                // A dead wrapper alone is NOT such evidence (Gradle may use a daemon).
                val late = runCatching { discovery(Path.of(record.gameDir)) }.getOrNull()
                if (late != null && matches(record, late.process)) return update(record, setOf("stopping")) {
                    it.copy(process = late.process, clientSocket = late.socket,
                        state = if (alive(late.process)) "stopping" else "stopped")
                }
            }
            return record
        }
        if (record.process != null && !alive(record.process)) return update(record, setOf("starting", "start_timeout", "client_ready", "world_ready")) { it.copy(state = "dead", lastError = "client PID/startTime is no longer live") }
        // The wrapper waits for runClient, so while starting its exit means the build
        // failed or MC exited/crashed before publishing an identity: fail now, not at the deadline.
        val wrapperDead = record.process == null && record.state in setOf("starting", "start_timeout") &&
            record.wrapper?.let(runtime::isAlive) == false
        fun startFailed() = update(record, setOf("starting", "start_timeout")) {
            it.copy(state = "failed", lastError = "client exited before becoming ready" +
                (crashHint(it.logPath)?.let { hint -> ": $hint" } ?: "") + "; see session logs")
        }
        try {
            val found = discovery(Path.of(record.gameDir))
            if (found == null || !alive(found.process)) return if (wrapperDead) startFailed() else record
            check(matches(record, found.process)) { "client.json identity does not match this session" }
            val observed = runtime.inspect(options(found.socket, "client", found.process))
            check(observed.process == found.process && alive(found.process)) { "client identity changed during probe" }
            record = update(record, setOf("starting", "start_timeout", "client_ready", "world_ready")) {
                it.copy(process = found.process, clientSocket = found.socket, serverSocket = serverSocket(Path.of(it.gameDir)),
                    state = readyState(observed.status), lastError = null)
            }
        } catch (e: Exception) {
            if (wrapperDead) return startFailed()
            record = update(record, setOf("starting", "start_timeout", "client_ready", "world_ready")) { it.copy(lastError = "probe: ${e.message}") }
        }
        return record
    }

    /** Last crash/failure line of the launch log (bounded tail), for lastError. */
    private fun crashHint(logPath: String?): String? {
        val path = logPath?.let(Path::of)?.takeIf(Files::isRegularFile) ?: return null
        val tail = RandomAccessFile(path.toFile(), "r").use { file ->
            val start = maxOf(0L, file.length() - 256 * 1024)
            file.seek(start)
            ByteArray((file.length() - start).toInt()).also(file::readFully).toString(Charsets.UTF_8)
        }
        val markers = listOf("Minecraft has crashed", "Caused by:", "BUILD FAILED", "FAILURE:", "Exception")
        return tail.lineSequence().map(String::trim).filter { line -> markers.any(line::contains) }
            .lastOrNull { "Caused by:" in it }
            ?: tail.lineSequence().map(String::trim).lastOrNull { line -> markers.any(line::contains) }
    }

    private fun readyState(status: JsonObject?): String = if (status?.get("inWorld")?.asBoolean == true &&
        status.get("singleplayer")?.asBoolean == true) "world_ready" else "client_ready"
    private fun matches(record: SessionRecord, process: RpcProcessIdentity): Boolean =
        process.gameDir == record.gameDir && (record.process?.let { it == process }
            ?: (record.owned && process.sessionToken == record.sessionToken))
    private fun alive(process: RpcProcessIdentity): Boolean = runtime.isAlive(SessionProcess(process.pid, process.startTime))
    private fun requireRecord(name: String): SessionRecord = registry.locked { read(name) }
        ?: throw IllegalStateException("unknown session: $name")
    private fun deadline(seconds: Long): Long {
        require(seconds in 0..86_400) { "waitSeconds must be between 0 and 86400" }
        return runtime.nanoTime() + seconds * 1_000_000_000L
    }
    private fun options(socket: String, plane: String, identity: RpcProcessIdentity, timeoutMs: Int = 1000,
                        requestTimeoutMs: Long? = 1000): RpcClientOptions = RpcClientOptions(socket = socket,
        plane = plane, timeoutMs = timeoutMs, requestTimeoutMs = requestTimeoutMs, expectedProcess = identity)
    private data class Discovery(val process: RpcProcessIdentity, val socket: String)
    private fun discovery(game: Path): Discovery? {
        val path = game.resolve("mcdebug/client.json")
        if (!Files.exists(path)) return null
        val json = JsonParser.parseString(Files.readString(path)).asJsonObject
        val process = RpcProcessIdentity.fromJson(json.getAsJsonObject("process"))
        check(process.gameDir == game.toString()) { "client.json gameDir mismatch" }
        val socket = json.get("clientSocket").asString
        check(socket.isNotBlank() && Path.of(socket).isAbsolute) { "clientSocket must be an absolute path" }
        return Discovery(process, socket)
    }
    private fun serverSocket(game: Path): String? {
        val path = game.resolve("mcdebug/port")
        if (!Files.isRegularFile(path)) return null
        return Files.readString(path).trim().takeIf { it.isNotEmpty() }?.also {
            check(Path.of(it).isAbsolute) { "integrated server socket must be absolute" }
        }
    }
    private fun ownerPath(game: Path) = game.resolve("mcdebug/mdb-owner.json")
    private fun writeOwner(record: SessionRecord) = atomicWrite(ownerPath(Path.of(record.gameDir)), sessionGson.toJson(record.toJson()))
    private fun <T> gameLocked(game: Path, failIfBusy: Boolean, action: () -> T): T =
        fileLock(game.resolve("mcdebug/mdb.lock"), failIfBusy, action)
    private fun update(record: SessionRecord, states: Set<String>? = null, transform: (SessionRecord) -> SessionRecord): SessionRecord =
        gameLocked(Path.of(record.gameDir), false) { mutateLocked(record, states, transform) ?: registry.locked { read(record.name) } ?: record }
    private fun mutateLocked(record: SessionRecord, states: Set<String>? = null, transform: (SessionRecord) -> SessionRecord): SessionRecord? =
        registry.locked {
            val current = read(record.name) ?: return@locked null
            if (current.recordId != record.recordId || (states != null && current.state !in states)) return@locked current
            val next = transform(current).copy(updatedAt = Instant.now().toString())
            write(next)
            if (next.owned && readRecord(ownerPath(Path.of(next.gameDir)))?.sessionToken == next.sessionToken) writeOwner(next)
            next
        }
}

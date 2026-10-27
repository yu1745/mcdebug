package com.mcdebug.cli.session

import com.google.gson.JsonObject
import com.mcdebug.cli.RpcClient
import com.mcdebug.cli.RpcClientOptions
import com.mcdebug.cli.RpcProcessIdentity
import java.nio.file.Path

/** Injectable OS/RPC boundary: tests never need a Gradle daemon, Minecraft, GL, or a display. */
interface SessionRuntime {
    fun currentProcess(): SessionProcess
    fun isAlive(process: SessionProcess): Boolean
    fun launch(spec: SessionLaunch): SessionProcess
    fun inspect(options: RpcClientOptions): SessionObservation
    fun verify(options: RpcClientOptions): RpcProcessIdentity = inspect(options).process
    /** Must handshake and issue gui.stop over the SAME identity-verified connection. */
    fun requestStop(options: RpcClientOptions)
    /** Never enumerate descendants or terminate a process group (Gradle daemons are shared). */
    fun terminate(process: SessionProcess, force: Boolean)
    fun nanoTime(): Long = System.nanoTime()
    fun pause(millis: Long) { Thread.sleep(millis) }
}

data class SessionLaunch(val command: List<String>, val project: Path, val environment: Map<String, String>, val logPath: Path)
data class SessionObservation(val process: RpcProcessIdentity, val status: JsonObject? = null)

class LocalSessionRuntime : SessionRuntime {
    override fun currentProcess(): SessionProcess = identity(ProcessHandle.current())
    override fun isAlive(process: SessionProcess): Boolean = ProcessHandle.of(process.pid).orElse(null)?.let {
        it.isAlive && it.info().startInstant().orElse(null)?.toString() == process.startTime
    } ?: false

    override fun launch(spec: SessionLaunch): SessionProcess {
        val builder = ProcessBuilder(spec.command).directory(spec.project.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(spec.logPath.toFile()))
        builder.environment().putAll(spec.environment)
        return identity(builder.start().toHandle())
    }
    override fun inspect(options: RpcClientOptions): SessionObservation = RpcClient(options).use { client ->
        val process = client.handshake().process
        val status = if (options.isClient) client.call("gui.status").asJsonObject else null
        SessionObservation(process, status)
    }
    override fun verify(options: RpcClientOptions): RpcProcessIdentity = RpcClient(options).use { it.handshake().process }
    override fun requestStop(options: RpcClientOptions) {
        val expected = requireNotNull(options.expectedProcess)
        RpcClient(options).use { client ->
            check(client.handshake().process == expected) { "client identity changed before stop" }
            client.call("gui.stop", JsonObject().apply {
                addProperty("instanceId", expected.instanceId)
                addProperty("sessionToken", expected.sessionToken)
            })
        }
    }
    override fun terminate(process: SessionProcess, force: Boolean) {
        val handle = ProcessHandle.of(process.pid).orElse(null) ?: return
        check(isAlive(process)) { "refusing to terminate reused/dead PID ${process.pid}" }
        if (force) handle.destroyForcibly() else handle.destroy()
    }
    private fun identity(handle: ProcessHandle): SessionProcess = SessionProcess(handle.pid(),
        checkNotNull(handle.info().startInstant().orElse(null)) { "cannot establish startTime for PID ${handle.pid()}" }.toString())
}

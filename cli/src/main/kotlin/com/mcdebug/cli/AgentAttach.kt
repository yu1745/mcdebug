package com.mcdebug.cli

import com.mcdebug.agent.InstrumentationAgent
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Attaches [InstrumentationAgent] to a local JVM (same user) through the JDK attach
 * API. Reflection keeps the CLI usable on runtimes without `jdk.attach`; those get a
 * clear error instead of a NoClassDefFoundError.
 */
object AgentAttach {
    private const val CLASS_FILE = "com/mcdebug/agent/InstrumentationAgent.class"

    /** A one-class agent jar: the target JVM must not see the CLI's own dependencies. */
    fun agentJar(directory: Path = Files.createTempDirectory("mcdebug-agent-")): Path {
        val bytes = InstrumentationAgent::class.java.classLoader.getResourceAsStream(CLASS_FILE)?.use { it.readBytes() }
            ?: error("agent class $CLASS_FILE is missing from the CLI")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name("Agent-Class")] = InstrumentationAgent::class.java.name
            mainAttributes[Attributes.Name("Can-Redefine-Classes")] = "true"
            mainAttributes[Attributes.Name("Can-Retransform-Classes")] = "true"
        }
        val jar = directory.resolve("mcdebug-instrumentation-agent.jar")
        JarOutputStream(Files.newOutputStream(jar), manifest).use { out ->
            out.putNextEntry(JarEntry(CLASS_FILE))
            out.write(bytes)
            out.closeEntry()
        }
        return jar
    }

    fun attach(pid: Long) {
        val vmClass = try {
            Class.forName("com.sun.tools.attach.VirtualMachine")
        } catch (_: ClassNotFoundException) {
            throw IllegalStateException("this Java runtime has no jdk.attach module; run the CLI with a full JDK (java -jar mcdebug-cli.jar)")
        }
        val jar = agentJar()
        try {
            val vm = vmClass.getMethod("attach", String::class.java).invoke(null, pid.toString())
            try {
                vmClass.getMethod("loadAgent", String::class.java).invoke(vm, jar.toString())
            } finally {
                vmClass.getMethod("detach").invoke(vm)
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException("cannot attach to pid $pid: ${e.targetException}", e.targetException)
        } finally {
            Files.deleteIfExists(jar)
            Files.deleteIfExists(jar.parent)
        }
    }
}

package com.mcdebug.testkit

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock

/** Ordinary JUnit tests sharing a client must not race its Screen or player inventory. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(McDebugClientExtension::class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("mcdebug-client")
annotation class McDebugClientTest

class McDebugClientExtension : BeforeAllCallback, BeforeEachCallback, AfterTestExecutionCallback,
    AfterEachCallback, ParameterResolver {
    private val namespace = ExtensionContext.Namespace.create(McDebugClientExtension::class.java)

    private fun runtime(context: ExtensionContext): ClientTestRuntime =
        context.root.getStore(namespace).getOrComputeIfAbsent("runtime", { ClientTestRuntime() }, ClientTestRuntime::class.java)

    override fun beforeAll(context: ExtensionContext) {
        runtime(context)
    }

    override fun beforeEach(context: ExtensionContext) {
        val runtime = runtime(context)
        val name = "${context.requiredTestClass.simpleName}-${context.requiredTestMethod.name}"
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val id = context.uniqueId.hashCode().toUInt().toString(16)
        val test = ClientTestContext(runtime, runtime.artifactsDir.resolve("$name-$id"))
        context.getStore(namespace).put("context", test)
        println("mcdebug artifacts: ${test.artifactsDir}")
    }

    override fun afterTestExecution(context: ExtensionContext) {
        val test = context.getStore(namespace).get("context", ClientTestContext::class.java) ?: return
        val failure = context.executionException.orElse(null)
        if (failure != null) {
            test.collectFailure(failure)
        } else {
            try { test.assertNoNewErrors() } catch (error: Throwable) {
                test.collectFailure(error)
                throw error
            }
        }
    }

    override fun afterEach(context: ExtensionContext) {
        val test = context.getStore(namespace).get("context", ClientTestContext::class.java) ?: return
        context.executionException.orElse(null)?.let(test::collectFailure)
    }

    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean =
        parameterContext.parameter.type == ClientTestContext::class.java

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any =
        checkNotNull(extensionContext.getStore(namespace).get("context", ClientTestContext::class.java))
}

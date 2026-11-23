package com.mcdebug.sdk

import com.mcdebug.sdk.session.LocalSessionRuntime
import com.mcdebug.sdk.session.SessionLaunch
import com.mcdebug.sdk.session.SessionManager
import com.mcdebug.sdk.session.SessionObservation
import com.mcdebug.sdk.session.SessionProcess
import com.mcdebug.sdk.session.SessionRecord
import com.mcdebug.sdk.session.SessionRuntime
import com.mcdebug.sdk.session.StartRequest
import com.mcdebug.sdk.session.defaultRegistryDir
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 1.21.1 的消费者直接 import `com.mcdebug.cli.*`（当时 SDK 还在 CLI 模块里）。这里锁定
 * 旧包名下的别名仍然指向 SDK 类型，避免下次重构悄悄删掉兼容层。
 */
@Suppress("DEPRECATION")
class LegacyAliasesTest {
    @Test
    fun `legacy cli package aliases resolve to the sdk types`() {
        val sameClass = listOf(
            com.mcdebug.cli.DebugApi::class to DebugApi::class,
            com.mcdebug.cli.WorldApi::class to WorldApi::class,
            com.mcdebug.cli.ServerApi::class to ServerApi::class,
            com.mcdebug.cli.GuiApi::class to GuiApi::class,
            com.mcdebug.cli.ScreenshotOptions::class to ScreenshotOptions::class,
            com.mcdebug.cli.RpcClient::class to RpcClient::class,
            com.mcdebug.cli.RpcClientOptions::class to RpcClientOptions::class,
            com.mcdebug.cli.RpcException::class to RpcException::class,
            com.mcdebug.cli.RpcProcessIdentity::class to RpcProcessIdentity::class,
            com.mcdebug.cli.RpcHandshake::class to RpcHandshake::class,
            com.mcdebug.cli.RpcRequestTimeoutException::class to RpcRequestTimeoutException::class,
            com.mcdebug.cli.ScreenshotComparison::class to ScreenshotComparison::class,
            com.mcdebug.cli.ScreenshotReview::class to ScreenshotReview::class,
            com.mcdebug.cli.ScreenshotRegion::class to ScreenshotRegion::class,
            com.mcdebug.cli.ScreenshotDifferenceBounds::class to ScreenshotDifferenceBounds::class,
            com.mcdebug.cli.ScreenshotComparisonResult::class to ScreenshotComparisonResult::class,
        )
        // `KClass` 比较的是包装对象；别名是否指向同一个类型，要比底层的 Java Class。
        sameClass.forEach { (legacy, current) -> assertEquals(current.java, legacy.java, "$legacy must alias $current") }
    }

    @Test
    fun `legacy session package aliases resolve to the sdk session types`() {
        assertEquals(SessionManager::class.java, com.mcdebug.cli.session.SessionManager::class.java)
        assertEquals(SessionRecord::class.java, com.mcdebug.cli.session.SessionRecord::class.java)
        assertEquals(StartRequest::class.java, com.mcdebug.cli.session.StartRequest::class.java)
        assertEquals(SessionProcess::class.java, com.mcdebug.cli.session.SessionProcess::class.java)
        assertEquals(SessionRuntime::class.java, com.mcdebug.cli.session.SessionRuntime::class.java)
        assertEquals(LocalSessionRuntime::class.java, com.mcdebug.cli.session.LocalSessionRuntime::class.java)
        assertEquals(SessionLaunch::class.java, com.mcdebug.cli.session.SessionLaunch::class.java)
        assertEquals(SessionObservation::class.java, com.mcdebug.cli.session.SessionObservation::class.java)
        assertEquals(defaultRegistryDir(), com.mcdebug.cli.session.defaultRegistryDir())
    }

    @Test
    fun `legacy values and functions delegate to the sdk`() {
        // Gson 没有重写 equals，所以 assertEquals 比较的就是对象身份：别名必须返回同一个实例。
        assertEquals(gson, com.mcdebug.cli.gson)
        assertEquals(parseJson("""{"a":[1,2]}"""), com.mcdebug.cli.parseJson("""{"a":[1,2]}"""))
        assertEquals(jsonObj { addProperty("a", 1) }, com.mcdebug.cli.jsonObj { addProperty("a", 1) })
        assertEquals(TCP_ENDPOINT_PREFIX, com.mcdebug.cli.TCP_ENDPOINT_PREFIX)
        val legacyRequest: com.mcdebug.cli.session.StartRequest = StartRequest("legacy")
        assertEquals("legacy", legacyRequest.name)
        val legacyPosition: com.mcdebug.cli.Pos = listOf(1, 2, 3)
        val legacyBox: com.mcdebug.cli.Box = mapOf("from" to listOf(0, 0, 0))
        assertEquals(3, legacyPosition.size)
        assertEquals(1, legacyBox.size)
    }
}

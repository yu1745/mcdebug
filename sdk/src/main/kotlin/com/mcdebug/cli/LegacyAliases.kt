package com.mcdebug.cli

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/*
 * 旧消费者兼容层（1.21.1 线）。
 *
 * 1.21.1 的 RPC 客户端、类型化 API、截图比较与会话管理都在 CLI 模块的 `com.mcdebug.cli` 包里，
 * 消费者（`mcdebugTest`）直接 import 这些类型。它们现在属于 `sdk`（`com.mcdebug.sdk`），
 * 这里保留旧包名下的源码级别名（会话类见 `com.mcdebug.cli.session`）：已有 Kotlin 消费者只需
 * 把依赖换成 `mcdebug-sdk` 即可继续编译。
 *
 * 限制：typealias 只对 Kotlin 源码可见（Java 调用方请直接使用 `com.mcdebug.sdk`），
 * 且只保证源码兼容——已编译的旧 class 文件需要重新编译。新代码一律使用 `com.mcdebug.sdk`。
 */

// ---- JsonCodec ----
@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("gson", "com.mcdebug.sdk.JsonCodecKt"))
val gson: Gson get() = com.mcdebug.sdk.gson

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("parseJson(text)", "com.mcdebug.sdk.JsonCodecKt"))
fun parseJson(text: String): JsonElement = com.mcdebug.sdk.parseJson(text)

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("jsonObj(block)", "com.mcdebug.sdk.JsonCodecKt"))
fun jsonObj(block: JsonObject.() -> Unit): JsonObject = com.mcdebug.sdk.jsonObj(block)

// ---- RpcClient ----
@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("RpcException", "com.mcdebug.sdk.RpcException"))
typealias RpcException = com.mcdebug.sdk.RpcException

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("RpcRequestTimeoutException", "com.mcdebug.sdk.RpcRequestTimeoutException"))
typealias RpcRequestTimeoutException = com.mcdebug.sdk.RpcRequestTimeoutException

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("RpcProcessIdentity", "com.mcdebug.sdk.RpcProcessIdentity"))
typealias RpcProcessIdentity = com.mcdebug.sdk.RpcProcessIdentity

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("RpcHandshake", "com.mcdebug.sdk.RpcHandshake"))
typealias RpcHandshake = com.mcdebug.sdk.RpcHandshake

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("RpcClientOptions", "com.mcdebug.sdk.RpcClientOptions"))
typealias RpcClientOptions = com.mcdebug.sdk.RpcClientOptions

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("RpcClient", "com.mcdebug.sdk.RpcClient"))
typealias RpcClient = com.mcdebug.sdk.RpcClient

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("TCP_ENDPOINT_PREFIX", "com.mcdebug.sdk.TCP_ENDPOINT_PREFIX"))
const val TCP_ENDPOINT_PREFIX: String = com.mcdebug.sdk.TCP_ENDPOINT_PREFIX

// ---- DebugApi ----
@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("Pos", "com.mcdebug.sdk.Pos"))
typealias Pos = com.mcdebug.sdk.Pos

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("Box", "com.mcdebug.sdk.Box"))
typealias Box = com.mcdebug.sdk.Box

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("WorldApi", "com.mcdebug.sdk.WorldApi"))
typealias WorldApi = com.mcdebug.sdk.WorldApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("BeApi", "com.mcdebug.sdk.BeApi"))
typealias BeApi = com.mcdebug.sdk.BeApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("InvApi", "com.mcdebug.sdk.InvApi"))
typealias InvApi = com.mcdebug.sdk.InvApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("StorageApi", "com.mcdebug.sdk.StorageApi"))
typealias StorageApi = com.mcdebug.sdk.StorageApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("SnapshotApi", "com.mcdebug.sdk.SnapshotApi"))
typealias SnapshotApi = com.mcdebug.sdk.SnapshotApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("TraceApi", "com.mcdebug.sdk.TraceApi"))
typealias TraceApi = com.mcdebug.sdk.TraceApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("ScreenApi", "com.mcdebug.sdk.ScreenApi"))
typealias ScreenApi = com.mcdebug.sdk.ScreenApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("RedstoneApi", "com.mcdebug.sdk.RedstoneApi"))
typealias RedstoneApi = com.mcdebug.sdk.RedstoneApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("EntityApi", "com.mcdebug.sdk.EntityApi"))
typealias EntityApi = com.mcdebug.sdk.EntityApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("FixtureApi", "com.mcdebug.sdk.FixtureApi"))
typealias FixtureApi = com.mcdebug.sdk.FixtureApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("FluidApi", "com.mcdebug.sdk.FluidApi"))
typealias FluidApi = com.mcdebug.sdk.FluidApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("WaitApi", "com.mcdebug.sdk.WaitApi"))
typealias WaitApi = com.mcdebug.sdk.WaitApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("CraftApi", "com.mcdebug.sdk.CraftApi"))
typealias CraftApi = com.mcdebug.sdk.CraftApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("ScanApi", "com.mcdebug.sdk.ScanApi"))
typealias ScanApi = com.mcdebug.sdk.ScanApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("ServerApi", "com.mcdebug.sdk.ServerApi"))
typealias ServerApi = com.mcdebug.sdk.ServerApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("GuiApi", "com.mcdebug.sdk.GuiApi"))
typealias GuiApi = com.mcdebug.sdk.GuiApi

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("ScreenshotOptions", "com.mcdebug.sdk.ScreenshotOptions"))
typealias ScreenshotOptions = com.mcdebug.sdk.ScreenshotOptions

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("DebugApi", "com.mcdebug.sdk.DebugApi"))
typealias DebugApi = com.mcdebug.sdk.DebugApi

// ---- 截图比较 ----
@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("ScreenshotDifferenceBounds", "com.mcdebug.sdk.ScreenshotDifferenceBounds"))
typealias ScreenshotDifferenceBounds = com.mcdebug.sdk.ScreenshotDifferenceBounds

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("ScreenshotRegion", "com.mcdebug.sdk.ScreenshotRegion"))
typealias ScreenshotRegion = com.mcdebug.sdk.ScreenshotRegion

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("ScreenshotComparisonResult", "com.mcdebug.sdk.ScreenshotComparisonResult"))
typealias ScreenshotComparisonResult = com.mcdebug.sdk.ScreenshotComparisonResult

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("ScreenshotComparison", "com.mcdebug.sdk.ScreenshotComparison"))
typealias ScreenshotComparison = com.mcdebug.sdk.ScreenshotComparison

@Deprecated("已迁到 com.mcdebug.sdk", ReplaceWith("ScreenshotReview", "com.mcdebug.sdk.ScreenshotReview"))
typealias ScreenshotReview = com.mcdebug.sdk.ScreenshotReview

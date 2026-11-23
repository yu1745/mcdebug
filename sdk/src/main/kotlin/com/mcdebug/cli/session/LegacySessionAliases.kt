package com.mcdebug.cli.session

import java.nio.file.Path

/*
 * 旧消费者兼容层（1.21.1 线）：会话管理当时位于 CLI 模块的 `com.mcdebug.cli.session` 包，
 * 现在属于 `sdk`（`com.mcdebug.sdk.session`）。这里只保留旧包名下的源码级别名，
 * 只对 Kotlin 源码可见且不保证二进制兼容；新代码一律使用 `com.mcdebug.sdk.session`。
 */

@Deprecated("已迁到 com.mcdebug.sdk.session", ReplaceWith("defaultRegistryDir", "com.mcdebug.sdk.session.SessionRecordKt"))
fun defaultRegistryDir(): Path = com.mcdebug.sdk.session.defaultRegistryDir()

@Deprecated("已迁到 com.mcdebug.sdk.session", ReplaceWith("StartRequest", "com.mcdebug.sdk.session.StartRequest"))
typealias StartRequest = com.mcdebug.sdk.session.StartRequest

@Deprecated("已迁到 com.mcdebug.sdk.session", ReplaceWith("SessionProcess", "com.mcdebug.sdk.session.SessionProcess"))
typealias SessionProcess = com.mcdebug.sdk.session.SessionProcess

@Deprecated("已迁到 com.mcdebug.sdk.session", ReplaceWith("SessionRecord", "com.mcdebug.sdk.session.SessionRecord"))
typealias SessionRecord = com.mcdebug.sdk.session.SessionRecord

@Deprecated("已迁到 com.mcdebug.sdk.session", ReplaceWith("SessionManager", "com.mcdebug.sdk.session.SessionManager"))
typealias SessionManager = com.mcdebug.sdk.session.SessionManager

@Deprecated("已迁到 com.mcdebug.sdk.session", ReplaceWith("SessionRuntime", "com.mcdebug.sdk.session.SessionRuntime"))
typealias SessionRuntime = com.mcdebug.sdk.session.SessionRuntime

@Deprecated("已迁到 com.mcdebug.sdk.session", ReplaceWith("SessionLaunch", "com.mcdebug.sdk.session.SessionLaunch"))
typealias SessionLaunch = com.mcdebug.sdk.session.SessionLaunch

@Deprecated("已迁到 com.mcdebug.sdk.session", ReplaceWith("SessionObservation", "com.mcdebug.sdk.session.SessionObservation"))
typealias SessionObservation = com.mcdebug.sdk.session.SessionObservation

@Deprecated("已迁到 com.mcdebug.sdk.session", ReplaceWith("LocalSessionRuntime", "com.mcdebug.sdk.session.LocalSessionRuntime"))
typealias LocalSessionRuntime = com.mcdebug.sdk.session.LocalSessionRuntime

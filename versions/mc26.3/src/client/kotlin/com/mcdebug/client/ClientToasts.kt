package com.mcdebug.client

import com.mcdebug.rpc.RpcEnv

/**
 * 托管客户端（`session start`）默认不显示提示弹窗：教程、配方解锁、进度和模组通知都会
 * 遮挡截图并改变画面。只有带会话令牌的启动才隐藏；普通启动与 `session attach` 保持原版行为。
 */
object ClientToasts {
    // 旧版 CLI 也会传会话令牌，普通启动则沿用原版行为。
    @Volatile
    @get:JvmStatic
    var enabled: Boolean = RpcEnv.sessionToken.isBlank() || System.getenv("MCDEBUG_SHOW_TOASTS") == "true"
}

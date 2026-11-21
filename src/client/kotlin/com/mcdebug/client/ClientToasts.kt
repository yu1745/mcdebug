package com.mcdebug.client

import com.mcdebug.rpc.RpcEnv

object ClientToasts {
    // 旧版 CLI 也会传会话令牌，普通启动则沿用原版行为。
    @Volatile
    @get:JvmStatic
    var enabled: Boolean = RpcEnv.sessionToken.isBlank() || System.getenv("MCDEBUG_SHOW_TOASTS") == "true"
}

package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.google.gson.JsonElement

/**
 * CLI 侧共享支撑：连接生命周期（批处理复用）、版本不匹配告警、退出码映射、
 * 命令行字符串 tokenize（批处理 -c 参数）、JSON 路径解析（watch）。
 */

/** 批处理模式下的共享连接（-c 多命令连一次跑多条）；非批处理为 null。 */
var batchClient: RpcClient? = null

/** 批处理模式下的共享**客户端平面**连接（`gui.*`）；与 server 连接各自独立。 */
var batchClientPlane: RpcClient? = null

/** 是否正在跑 `--batch`（-c 多命令）：决定连接是复用还是用完即关。 */
var batchActive: Boolean = false

/** CLI 版本（fat jar manifest Implementation-Version；dev 运行时为 "dev"）。 */
fun cliVersion(): String =
    McDebugCli::class.java.`package`?.implementationVersion?.takeIf { it.isNotBlank() } ?: "dev"

/**
 * 以正确的生命周期打开一个连接：
 *  - 批处理模式下复用共享连接（不关闭，由批处理 finally 统一关闭）；
 *  - 单命令模式新建连接，块结束后关闭。
 * 新连接建立后比较服务端 modVersion 与 CLI 版本，不一致时 stderr 打一行警告（不阻塞执行）。
 */
fun withClient(cli: McDebugCli, block: (RpcClient) -> Unit) {
    if (batchActive) {
        val options = cli.clientOptions()
        val c = batchClient?.also { it.requireSameBatchOptions(options) } ?: RpcClient(options).also {
            batchClient = it
            warnVersionMismatch(it)
        }
        block(c)
        return
    }
    RpcClient(cli.clientOptions()).use { c ->
        warnVersionMismatch(c)
        block(c)
    }
}

/**
 * 客户端平面（`gui.*`）连接。默认走客户端发现文件 `mcdebug/clientPort`，可用
 * `--client-socket` / `--client-tcp` 覆盖；批处理下同样复用一条连接。
 *
 * 与 [withClient] 分开是有意的：客户端平面只在游戏客户端里存在，而单机客户端
 * 一个进程里两个平面同时在跑，所以两条连接互不替代。
 */
fun withClientPlane(cli: McDebugCli, block: (RpcClient) -> Unit) {
    if (batchActive) {
        val options = cli.clientPlaneOptions()
        val c = batchClientPlane?.also { it.requireSameBatchOptions(options) } ?: RpcClient(options).also {
            batchClientPlane = it
            warnVersionMismatch(it)
        }
        block(c)
        return
    }
    RpcClient(cli.clientPlaneOptions()).use { c ->
        warnVersionMismatch(c)
        block(c)
    }
}

/**
 * 按方法名选择平面：`gui.*` 走客户端平面，其余走服务端平面。
 * `raw` 用这个，避免 agent 为了调一个 gui 方法还要记住多一层开关。
 */
fun withPlaneFor(cli: McDebugCli, method: String, block: (RpcClient) -> Unit) {
    if (method.startsWith("gui.")) withClientPlane(cli, block) else withClient(cli, block)
}

/** 版本比较只读连接首条 server.ready，不调用 status，不依赖游戏主线程。 */
fun warnVersionMismatch(client: RpcClient, localVersion: String = cliVersion()) {
    val handshake = client.handshake()
    if (localVersion == "dev" || handshake.modVersion == localVersion) return
    System.err.println("warning: ${handshake.plane} mcdebug ${handshake.modVersion} != CLI $localVersion，部分命令可能不可用")
}

/** 统一退出码映射：0=成功；1=CLI 用法/解析错误；2=服务端拒绝（RPC error，含 -320xx）；3=方法未实现。 */
fun exitCodeFor(e: RpcException): Int =
    if (e.rpcCode == -32601) 3 else 2

/** 显式退出（消息 + 退出码），如 status --health 在旧服务端上的友好报错。 */
class CliExit(val code: Int, message: String) : RuntimeException(message)

/**
 * 把一条 "-c" 命令字符串拆成 argv token。支持单/双引号与反斜杠转义，
 * 与 shell 的常规行为一致（不展开变量/glob）。
 */
fun tokenizeCommandLine(s: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote: Char? = null
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            quote != null -> {
                if (c == quote) {
                    quote = null
                } else if (c == '\\' && quote == '"' && i + 1 < s.length && (s[i + 1] == '"' || s[i + 1] == '\\')) {
                    cur.append(s[i + 1]); i++
                } else {
                    cur.append(c)
                }
            }
            c == '\'' || c == '"' -> quote = c
            c == '\\' && i + 1 < s.length -> { cur.append(s[i + 1]); i++ }
            c.isWhitespace() -> { if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() } }
            else -> cur.append(c)
        }
        i++
    }
    if (quote != null) throw IllegalArgumentException("unbalanced quote in command: $s")
    if (cur.isNotEmpty()) out.add(cur.toString())
    return out
}

/**
 * 客户端 JSON 路径解析（watch entity 用）：支持 "a.b"、"a[0]"、"a.b[1]"。
 * 返回 null 表示路径不存在。
 */
fun jsonGetPath(root: JsonElement, path: String): JsonElement? {
    var current: JsonElement? = root
    val tokens = tokenizeJsonPath(path)
    for (tok in tokens) {
        current = when {
            current == null -> return null
            current.isJsonObject && tok is String -> current.asJsonObject.get(tok)
            current.isJsonArray && tok is Int -> {
                val idx = tok
                if (idx < 0 || idx >= current.asJsonArray.size()) return null
                current.asJsonArray[idx]
            }
            else -> return null
        }
    }
    return current
}

private fun tokenizeJsonPath(path: String): List<Any> {
    val tokens = mutableListOf<Any>()
    val sb = StringBuilder()
    var i = 0
    while (i < path.length) {
        val c = path[i]
        when {
            c == '.' -> {
                if (sb.isNotEmpty()) { tokens.add(sb.toString()); sb.clear() }
            }
            c == '[' -> {
                if (sb.isNotEmpty()) { tokens.add(sb.toString()); sb.clear() }
                val end = path.indexOf(']', i)
                if (end < 0) throw IllegalArgumentException("unclosed '[' in path: $path")
                val idx = path.substring(i + 1, end).trim().toIntOrNull()
                    ?: throw IllegalArgumentException("bad index in path: $path")
                tokens.add(idx)
                i = end
            }
            else -> sb.append(c)
        }
        i++
    }
    if (sb.isNotEmpty()) tokens.add(sb.toString())
    return tokens
}

package com.mcdebug.rpc

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 双通道容错启动的单元测试（0.5.1）：
 *
 *  - TCP 端口被占用时只 WARN、unix socket 继续服务（单边失败不拒绝启动）；
 *  - 两边都绑定失败时 start() 才抛 IllegalStateException；
 *  - 两边都成功时两种传输并行服务同一 dispatcher，SERVER_READY 通知携带 tcpPort，
 *    且 tcpPort 发现文件只写实际绑定端口。
 *
 * 传输层真实端到端验证：不构造 MinecraftServer（传 null，连接只读 SERVER_READY
 * 通知不发请求），依赖 -Dmcdebug.socket / -Dmcdebug.tcpPort 系统属性走
 * resolveSocketPath / resolveTcpConfig 的优先分支，全程不触碰 FabricLoader。
 */
class RpcServerBindTest {

    @TempDir
    lateinit var tmp: Path

    @AfterEach
    fun clearSystemProperties() {
        System.clearProperty("mcdebug.socket")
        System.clearProperty("mcdebug.tcpPort")
        System.clearProperty("mcdebug.tcpEnabled")
    }

    /** 占住一个本机 TCP 端口，返回端口号（用于模拟端口冲突）。 */
    private fun occupyTcpPort(): Pair<ServerSocketChannel, Int> {
        val blocker = ServerSocketChannel.open()
        blocker.bind(InetSocketAddress("127.0.0.1", 0))
        return blocker to (blocker.localAddress as InetSocketAddress).port
    }

    private fun readLine(channel: SocketChannel): String {
        val reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8))
        return reader.readLine() ?: error("connection closed before SERVER_READY")
    }

    @Test
    fun tcpPortOccupiedWarnsButUnixStillServes() {
        val (blocker, busyPort) = occupyTcpPort()
        try {
            val sock = tmp.resolve("primary.sock")
            val portFile = tmp.resolve("port")
            val tcpPortFile = tmp.resolve("tcpPort")
            System.setProperty("mcdebug.socket", sock.toString())
            System.setProperty("mcdebug.tcpPort", busyPort.toString())

            val server = RpcServer(RpcDispatcher(), { portFile }, { tcpPortFile })
            try {
                val bound = server.start(null)
                assertEquals(sock, bound, "unix socket 必须成功绑定")
                assertNull(server.tcpPort, "TCP 端口被占用时 tcpPort 应为 null（容忍失败）")

                // 发现文件：port 写 socket 路径；tcpPort 文件因 TCP 未绑定而不写
                assertEquals(sock.toString(), Files.readString(portFile).trim())
                assertFalse(Files.exists(tcpPortFile))

                // unix 传输仍然真实服务：能连上并收到 SERVER_READY 通知
                val client = SocketChannel.open(StandardProtocolFamily.UNIX)
                client.connect(UnixDomainSocketAddress.of(sock))
                val ready = readLine(client)
                assertTrue(ready.contains("\"server.ready\""), "应收到 SERVER_READY: $ready")
                assertTrue(ready.contains("\"socket\""), "SERVER_READY 应带 socket 字段: $ready")
                client.close()
            } finally {
                server.stop()
            }
        } finally {
            blocker.close()
        }
    }

    @Test
    fun bothListenersFailThrows() {
        val (blocker, busyPort) = occupyTcpPort()
        try {
            // unix socket 绑定必然失败：父目录是一个普通文件
            val parentFile = tmp.resolve("not-a-dir")
            Files.writeString(parentFile, "x")
            val sock = parentFile.resolve("sock")
            System.setProperty("mcdebug.socket", sock.toString())
            System.setProperty("mcdebug.tcpPort", busyPort.toString())

            val server = RpcServer(RpcDispatcher(), { tmp.resolve("port") }, { tmp.resolve("tcpPort") })
            try {
                val e = assertThrows(IllegalStateException::class.java) { server.start(null) }
                assertTrue(e.message!!.contains("both"), "两边都失败时应报错: ${e.message}")
            } finally {
                server.stop()
            }
        } finally {
            blocker.close()
        }
    }

    @Test
    fun bothListenersServeInParallel() {
        val sock = tmp.resolve("both.sock")
        val portFile = tmp.resolve("port")
        val tcpPortFile = tmp.resolve("tcpPort")
        System.setProperty("mcdebug.socket", sock.toString())
        System.setProperty("mcdebug.tcpPort", "0")  // 临时端口：绑定后回读实际端口

        val server = RpcServer(RpcDispatcher(), { portFile }, { tcpPortFile })
        try {
            val bound = server.start(null)
            assertEquals(sock, bound)
            val tcpPort = requireNotNull(server.tcpPort) { "TCP 应绑定成功" }
            assertTrue(tcpPort > 0)

            // 发现文件：两个都写
            assertEquals(sock.toString(), Files.readString(portFile).trim())
            assertEquals(tcpPort.toString(), Files.readString(tcpPortFile).trim())

            // unix 与 TCP 同时可服务
            val unixClient = SocketChannel.open(StandardProtocolFamily.UNIX)
            unixClient.connect(UnixDomainSocketAddress.of(sock))
            val unixReady = readLine(unixClient)
            assertTrue(unixReady.contains("\"server.ready\""))
            unixClient.close()

            val tcpClient = SocketChannel.open()
            tcpClient.connect(InetSocketAddress("127.0.0.1", tcpPort))
            val tcpReady = readLine(tcpClient)
            assertTrue(tcpReady.contains("\"server.ready\""))
            assertTrue(tcpReady.contains("\"tcpPort\":$tcpPort"), "SERVER_READY 应带实际 tcpPort: $tcpReady")
            tcpClient.close()
        } finally {
            server.stop()
        }
        // stop 后 socket 文件应被清理
        assertFalse(Files.exists(sock))
    }
}

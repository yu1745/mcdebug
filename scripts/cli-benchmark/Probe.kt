@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
import kotlinx.cinterop.*
import platform.posix.*

// 最小 Linux TCP 探针：不包含 CLI 框架、JSON 对象模型或会话发现。
fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--version"))) { println("mcdebug-native-probe 1"); return }
    require(args.size == 2) { "port count" }
    val count = args[1].toInt()
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    check(fd >= 0)
    try {
        memScoped {
            val address = alloc<sockaddr_in>()
            memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
            address.sin_family = AF_INET.convert()
            address.sin_port = htons(args[0].toUShort())
            address.sin_addr.s_addr = htonl(0x7f000001u)
            check(connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0)
        }
        val stream = fdopen(dup(fd), "r") ?: error("fdopen")
        try {
            memScoped {
                val buffer = allocArray<ByteVar>(65536)
                fun line(): String { check(fgets(buffer, 65536, stream) != null); return buffer.toKString() }
                check(line().contains("server.ready"))
                repeat(count) { index ->
                    val request = "{\"jsonrpc\":\"2.0\",\"id\":${index+1},\"method\":\"server.status\",\"params\":{}}\n"
                    val bytes = request.encodeToByteArray()
                    bytes.usePinned { pinned ->
                        var offset = 0
                        while (offset < bytes.size) {
                            val n = send(fd, pinned.addressOf(offset), (bytes.size-offset).convert(), 0)
                            check(n > 0); offset += n.toInt()
                        }
                    }
                    val response = line()
                    check(response.contains("\"result\"") && !response.contains("\"error\""))
                    println(response.trimEnd())
                }
            }
        } finally { fclose(stream) }
    } finally { close(fd) }
}

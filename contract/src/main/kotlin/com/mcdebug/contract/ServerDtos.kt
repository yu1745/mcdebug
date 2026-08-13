package com.mcdebug.contract

/**
 * server.* 组 DTO（样板：字段名 = JSON 字段名，见 RpcMethod 的约定）。
 * 其余组的 DTO 在迁移 handler 时按需补充。
 */

/** server.status 响应。无参数请求。 */
data class ServerStatusResp(
    val mcVersion: String,
    val modVersion: String,
    val modLoader: String,
    val protocolVersion: Int,
    val dims: List<String>,
    val players: Int,
    val dayTime: Long,
    val tick: Long,
)

/** server.listDimensions 响应。无参数请求。 */
data class ListDimensionsResp(
    val dims: List<String>,
)

/** server.runCommand 请求。 */
data class RunCommandReq(
    val command: String,
    val dim: String? = null,
)

/** server.runCommand 响应。 */
data class RunCommandResp(
    val success: Boolean,
    val result: Int,
    val output: String,
)

/** server.health 响应（0.6.0+）。无参数请求。 */
data class HealthDimResp(
    val dim: String,
    val entities: Int,
    val loadedChunks: Int,
)

data class HealthResp(
    val tps: Double,
    val mspt: Double,
    val tick: Long,
    val players: Int,
    val dims: List<HealthDimResp>,
)

object ServerMethods {
    val status = RpcMethod.noReq("server.status", ServerStatusResp::class.java)
    val health = RpcMethod.noReq("server.health", HealthResp::class.java)
    val listDimensions = RpcMethod.noReq("server.listDimensions", ListDimensionsResp::class.java)
    val runCommand = RpcMethod("server.runCommand", RunCommandReq::class.java, RunCommandResp::class.java)
}

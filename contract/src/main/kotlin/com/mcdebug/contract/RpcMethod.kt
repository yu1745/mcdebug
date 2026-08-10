package com.mcdebug.contract

/**
 * 一个 JSON-RPC 方法的契约声明：方法名 + 请求/响应 DTO 类型。
 *
 * 这是客户端和服务端共享的单一事实源：
 *  - 服务端在 `com.mcdebug.rpc` 注册同名 handler（Gson 进出）；
 *  - Kotlin 客户端用同一 DTO 调用 `method.name`；
 *  - 契约一致性由服务端测试锁定（遍历 RpcDispatcher 注册表与契约表交叉校验）。
 *
 * DTO 约定（重要）：
 *  - **字段名 = JSON 字段名**（如 `val mcVersion: String`），不用驼峰 + 注解
 *    双写——服务端 Gson 与客户端序列化库都按字段名直接反射，任何一端都不
 *    需要额外映射配置；
 *  - 请求 DTO 的字段即现有 JSON 参数名，响应 DTO 的字段即现有 JSON 结果字段
 *    ——契约变化即线上格式变化，两端同时感知；
 *  - 无参数方法用 [noReq]（Req = Unit）。
 */
class RpcMethod<Req : Any, Resp : Any>(
    val name: String,
    val reqType: Class<Req>,
    val respType: Class<Resp>,
) {
    companion object {
        /** 无参数方法：请求体为空对象。 */
        fun <Resp : Any> noReq(name: String, resp: Class<Resp>): RpcMethod<Unit, Resp> =
            RpcMethod(name, Unit::class.java, resp)
    }
}

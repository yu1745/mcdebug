package com.mcdebug.contract

import com.mcdebug.api.BlockEntityOps
import com.mcdebug.api.CraftingOps
import com.mcdebug.api.EntityOps
import com.mcdebug.api.FixtureOps
import com.mcdebug.api.FluidOps
import com.mcdebug.api.InventoryOps
import com.mcdebug.api.RedstoneOps
import com.mcdebug.api.ReflectOps
import com.mcdebug.api.ScanOps
import com.mcdebug.api.ScreenOps
import com.mcdebug.api.ServerOps
import com.mcdebug.api.SnapshotOps
import com.mcdebug.api.StorageOps
import com.mcdebug.api.TraceOps
import com.mcdebug.api.WorldOps
import com.mcdebug.McDebug
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.wait.WaitOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 契约一致性校验：服务端 RpcDispatcher 的注册表 ↔ contract 模块方法清单。
 *
 * 任何一侧新增/改名/删除方法而不同步另一侧，测试立即失败。
 * 组前缀映射与 McDebugMod.onServerStarted 的 registerGroup 保持一致。
 *
 * `Methods` 的清单是三个 Minecraft 版本的并集；这里按**本版本**的子集校验
 * （`Methods.missingIn` 登记了本版本尚未提供的方法）。
 */
class RpcContractTest {

    /** (组前缀, 组实现) —— 与 McDebugMod 的注册顺序一致。 */
    private val groups: List<Pair<String, RpcHandlerGroup>> = listOf(
        "world" to WorldOps,
        "be" to BlockEntityOps,
        "inv" to InventoryOps,
        "scan" to ScanOps,
        "server" to ServerOps,
        "wait" to WaitOps,
        "fluid" to FluidOps,
        "craft" to CraftingOps,
        "storage" to StorageOps,
        "snapshot" to SnapshotOps,
        "trace" to TraceOps,
        "screen" to ScreenOps,
        "redstone" to RedstoneOps,
        "entity" to EntityOps,
        "fixture" to FixtureOps,
        "reflect" to ReflectOps,
    )

    /** 本版本实际应当注册的方法子集（并集减去 Methods.MISSING_BY_MC_VERSION 里缺席的）。 */
    private val mc = McDebug.MC_VERSION_LINE

    private val registered: Set<String> = groups
        .flatMap { (prefix, group) -> group.methods().keys.map { "$prefix.$it" } }
        .toSet()

    @Test
    fun everyRegisteredMethodIsDeclaredInContract() {
        val missing = registered - Methods.all(mc)
        assertTrue(
            missing.isEmpty(),
            "服务端已注册但不在本 Minecraft 版本的契约子集里: ${missing.sorted()}\n" +
                "（在 Methods.kt 对应组 setOf 中补上方法名；若它被登记为本版本缺席，先实现再从 MISSING_BY_MC_VERSION 移除）",
        )
    }

    @Test
    fun everyDeclaredServerMethodIsRegisteredOnServer() {
        // 子集里还有客户端面的 gui.*（由 client entrypoint 注册，dev 单测里不存在），
        // 所以这里只能比对服务端平面。
        val extra = Methods.serverMethods(mc) - registered
        assertTrue(
            extra.isEmpty(),
            "contract/Methods.kt 已声明但服务端未注册: ${extra.sorted()}\n" +
                "（在对应 Ops 的 methods() 中注册，或从 Methods.kt 移除）",
        )
    }

    @Test
    fun clientMethodsAreDisjointFromServerMethods() {
        val overlap = Methods.serverMethods(mc) intersect Methods.clientMethods(mc)
        assertTrue(
            overlap.isEmpty(),
            "同一个方法名不能同时属于两个 plane: ${overlap.sorted()}",
        )
        assertEquals(
            Methods.serverMethods(mc) + Methods.clientMethods(mc),
            Methods.all(mc),
            "本版本的全部方法必须恰好等于两个 plane 的并集",
        )
    }

    @Test
    fun clientMethodsAllBelongToClientPlane() {
        Methods.clientMethods(mc).forEach { name ->
            assertEquals("client", Methods.planeOf(name), "planeOf($name) 应为 client")
        }
        Methods.serverMethods(mc).forEach { name ->
            assertEquals("server", Methods.planeOf(name), "planeOf($name) 应为 server")
        }
        assertEquals(null, Methods.planeOf("no.suchMethod"))
        // gui.* 是客户端面，服务端 dispatcher 不应注册任何 gui.*
        assertTrue(
            registered.none { it.startsWith("gui.") },
            "gui.* 只能由客户端 entrypoint 注册，服务端注册表里出现了: " +
                registered.filter { it.startsWith("gui.") },
        )
    }

    @Test
    fun methodNamesMatchJsonRpcConvention() {
        // 方法名必须是非空、无空白、点分命名（JSON-RPC 对大小写无要求）。
        // 两个 plane 一起校验。
        Methods.all(mc).forEach { name ->
            assertTrue(
                name.matches(Regex("[^\\s]+\\.\\S+")),
                "方法名不符合约定（点分、无空白）: $name",
            )
        }
    }

    @Test
    fun serverDtosMatchRegisteredServerMethods() {
        // DTO 按需补充：已声明的必须真实存在，未声明的允许（后续补齐）。
        val declared = ServerMethods::class.java.declaredFields
            .mapNotNull { f ->
                f.isAccessible = true
                f.get(null) as? RpcMethod<*, *>
            }
            .map { it.name }
            .toSet()
        val registeredServer = registered.filter { it.startsWith("server.") }.toSet()
        assertTrue(
            declared.all { it in registeredServer },
            "contract 声明了服务端未注册的方法: ${declared - registeredServer}",
        )
    }
}

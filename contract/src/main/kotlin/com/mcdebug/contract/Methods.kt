package com.mcdebug.contract

/**
 * 全部 JSON-RPC 方法名清单（唯一事实源），按**平面（plane）**分成两组：
 *
 * - **`SERVER_METHODS`**：服务端平面。由 `server` entrypoint（专用服务器 / 集成服务器的
 *   服务端侧）注册，读写的都是世界/方块实体/物品栏等**服务端权威状态**。其中
 *   `screen.*` 是服务端假玩家菜单（只返回槽位 JSON，**不产生任何像素**）。
 * - **`CLIENT_METHODS`**：客户端平面（`gui.*`）。由 `client` entrypoint 注册，只在真实
 *   客户端存在：真实渲染帧、真实 Screen、真实点击。像素只能由这里产生。
 *
 * 两个平面各自校验：`RpcContractTest` 静态校验服务端注册表 ↔ `SERVER_METHODS`、两平面
 * 不相交、命名合规；客户端注册表的双向校验在客户端启动时做运行时自检
 * （`McDebugClientMod` 比对 `GuiOps.methods()` 与 `CLIENT_METHODS`，不一致打 ERROR 并写进
 * `gui.status.contractMismatch`），因为 `src/client` 不参与服务端单测的编译。
 * 加/改/删方法名时两边都要同步，否则测试/自检失败。
 *
 * 方法名 = `<组>.<后缀>`，与 `RpcHandlerGroup.methods()` 的注册 key 一一对应。
 */
object Methods {
    // ------------------------------------------------------------------
    // 服务端平面
    // ------------------------------------------------------------------

    val world = setOf(
        "world.getBlock", "world.setBlock", "world.setBlocks", "world.placeAsPlayer",
        "world.useItem", "world.useItemHold", "world.useOnBlock", "world.attackBlock",
        "world.breakBlock", "world.interactEntity", "world.attackEntity", "world.getRegion",
        "world.selectBlocks", "world.fillBox", "world.clearBox", "world.forceloadChunk",
        "world.unforceloadChunk",
    )
    val be = setOf(
        "be.getNbt", "be.setNbt", "be.getField", "be.setField", "be.tick",
    )
    val inv = setOf(
        "inv.getSize", "inv.getSlot", "inv.setSlot", "inv.insert", "inv.extract",
    )
    val scan = setOf(
        "scan.findBlocks", "scan.countByBlock", "scan.findEntities",
    )
    val server = setOf(
        "server.status", "server.health", "server.listDimensions", "server.runCommand",
        "server.forgeFluidCapability", "server.mantleInteract",
    )
    val wait = setOf("wait.until")
    val fluid = setOf(
        "fluid.info", "fluid.get", "fluid.insert", "fluid.extract",
    )
    val craft = setOf("craft.craft", "craft.find")
    val storage = setOf(
        "storage.list", "storage.get", "storage.insert", "storage.extract", "storage.transfer",
    )
    val snapshot = setOf("snapshot.capture", "snapshot.diff")
    val trace = setOf("trace.start", "trace.stop", "trace.get")
    val screen = setOf(
        "screen.openBlock", "screen.snapshot", "screen.setPlayerSlot",
        "screen.clickSlot", "screen.quickMove", "screen.close",
    )
    val redstone = setOf(
        "redstone.getPower", "redstone.isPowered", "redstone.setLever",
        "redstone.pulse", "redstone.notifyNeighbors",
    )
    val entity = setOf(
        "entity.spawn", "entity.getNbt", "entity.setNbt", "entity.teleport",
        "entity.remove", "entity.listItems", "entity.collectItems",
    )
    val fixture = setOf("fixture.capture", "fixture.load")
    val reflect = setOf(
        "reflect.resolve", "reflect.get", "reflect.call", "reflect.new",
        "reflect.refs", "reflect.release", "reflect.mappings",
    )

    /** 服务端平面全部方法名（专用服务器与集成服务器的服务端侧都应完整注册）。 */
    val SERVER_METHODS: Set<String> = buildSet {
        addAll(world)
        addAll(be)
        addAll(inv)
        addAll(scan)
        addAll(server)
        addAll(wait)
        addAll(fluid)
        addAll(craft)
        addAll(storage)
        addAll(snapshot)
        addAll(trace)
        addAll(screen)
        addAll(redstone)
        addAll(entity)
        addAll(fixture)
        addAll(reflect)
    }

    // ------------------------------------------------------------------
    // 客户端平面（gui.*）—— 真实渲染/真实 Screen/真实点击
    // ------------------------------------------------------------------

    val gui = setOf(
        "gui.status", "gui.connect", "gui.openBlock", "gui.screenshot",
        "gui.screen", "gui.click", "gui.close", "gui.reloadResources", "gui.stop",
        "gui.key", "gui.type", "gui.drag", "gui.disconnect",
        "gui.act", "gui.settle", "gui.reinit", "gui.problems", "gui.hotswap",
    )

    /** 客户端平面全部方法名。 */
    val CLIENT_METHODS: Set<String> = gui

    /** 全部方法名（两个平面的并集）。 */
    val ALL: Set<String> = SERVER_METHODS + CLIENT_METHODS

    /** 方法所属平面：`"server"` / `"client"`；未声明返回 null。 */
    fun planeOf(method: String): String? = when (method) {
        in SERVER_METHODS -> "server"
        in CLIENT_METHODS -> "client"
        else -> null
    }
}

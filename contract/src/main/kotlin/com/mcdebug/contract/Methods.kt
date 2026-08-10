package com.mcdebug.contract

/**
 * 全部 JSON-RPC 方法名清单（唯一事实源）。
 *
 * 服务端测试 `RpcContractTest` 会遍历 RpcDispatcher 注册表与本清单交叉校验：
 * 两端任何一方加了/改了/删了方法名都会编译期可见或测试失败，防止契约漂移。
 *
 * 方法名 = `<组>.<后缀>`，与 `RpcHandlerGroup.methods()` 的注册 key 一一对应。
 */
object Methods {
    val world = setOf(
        "world.getBlock", "world.setBlock", "world.setBlocks", "world.placeAsPlayer",
        "world.useItem", "world.useItemHold", "world.useOnBlock", "world.attackBlock",
        "world.breakBlock", "world.interactEntity", "world.attackEntity", "world.getRegion",
        "world.selectBlocks", "world.fillBox", "world.clearBox", "world.forceloadChunk",
        "world.unforceloadChunk",
    )
    val be = setOf(
        "be.getNbt", "be.setNbt", "be.getField", "be.setField",
    )
    val inv = setOf(
        "inv.getSize", "inv.getSlot", "inv.setSlot", "inv.insert", "inv.extract",
    )
    val scan = setOf(
        "scan.findBlocks", "scan.countByBlock", "scan.findEntities",
    )
    val server = setOf(
        "server.status", "server.listDimensions", "server.runCommand",
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

    /** 全部方法名（供服务端契约校验测试使用）。 */
    val ALL: Set<String> = buildSet {
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
}

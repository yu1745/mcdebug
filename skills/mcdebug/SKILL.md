---
name: mcdebug
description: 通过 mcdebug CLI（mdb / java -jar mcdebug-cli.jar）操作正在运行的 Minecraft 26.3（Fabric 或 NeoForge）：在服务端平面设置方块、NBT、库存、存储和实体，在客户端平面截图、点击、悬停及热重载 GUI Screen，并用 wait.until 断言。用于测试或调试运行中模组的方块、机器、GUI，或通过 mcdebug RPC 套接字操作服务端。
---

# mcdebug CLI 使用说明

本构建面向 Minecraft 26.3，游戏需要 JDK 25，CLI 支持 Java 17+。
保留 CLI 原有的 GLFW 数值键码和鼠标按钮编号（0 左键 / 1 右键 / 2 中键），内部转换为 SDL。

mcdebug 是通过 Unix 套接字提供 JSON-RPC 的模组，一个 JAR 同时包含服务端和客户端，CLI 与之通信。
每个子命令的 `--help` 都包含详细结构和注意事项，**先读帮助，不要猜选项**。
`mcdebug raw <method> '<json>'` 可调用任意 RPC，包括没有专用子命令的方法。

## 0. 查找或安装 CLI

按顺序使用第一个可用入口：`PATH` 中的 `mcdebug`、`PATH` 中的 `mdb`
（由 `scripts/build-native.sh` 构建的原生程序，约 11 毫秒启动），或缓存中的
`${XDG_DATA_HOME:-~/.local/share}/mcdebug/<version>/mcdebug-cli.jar`。

若均不存在，把发布 JAR 下载到该缓存，每个版本使用独立目录，再将启动脚本放入
`~/.local/bin`，多数发行版默认将其加入 `PATH`。需要 Java 17+，用 `java -version` 检查；
`gui hotswap` 另需完整 JDK。选择游戏内模组的版本，即 `status` 中的 `modVersion`，或最新发布版本：

```bash
V=0.6.3   # 最新版本见 gh release list -R yu1745/mcdebug -L 1 或 GitHub 发布页面
D="${XDG_DATA_HOME:-$HOME/.local/share}/mcdebug/$V"
U="https://github.com/yu1745/mcdebug/releases/download/v$V"
mkdir -p "$D" "$HOME/.local/bin"
curl -fL -o "$D/mcdebug-cli.jar" "$U/mcdebug-cli.jar"
echo "$(curl -fsL "$U/mcdebug-cli.jar.sha256")  $D/mcdebug-cli.jar" | sha256sum -c -   # 必须显示 OK
printf '#!/bin/sh\nexec java -jar "%s" "$@"\n' "$D/mcdebug-cli.jar" > "$HOME/.local/bin/mcdebug"
chmod +x "$HOME/.local/bin/mcdebug"
mcdebug --version
```

v0.6.3 之前的发布没有 `mcdebug-cli.jar` 附件。也可从源码构建：
`./gradlew :cli:copyCliJar` 会生成 `./mcdebug-cli.jar`。
CLI 内嵌**两个加载器**的模组 JAR 和本技能，均与 CLI 同版本：
`mcdebug jar --output <gameDir>/mods` 安装 Fabric 模组，
`mcdebug jar --loader neoforge --output <gameDir>/mods` 安装 NeoForge 模组，
两者都会移除另一加载器或旧版本的 JAR。`mcdebug skill` 将本技能刷新为 CLI 对应版本。
`mcdebug skill --name neoforge-fabric-port` 安装内嵌移植工作流和全部参考文件。

下文的 `mcdebug` 代表找到的任一入口。

## 1. 选择目标

- **自有开发客户端，模组开发时优先使用**：托管会话会启动工程的 `runClient` 并发现两个平面：

  ```bash
  mcdebug jar --output run/mods                     # 将同版本模组装入消费者的 mods/，移除旧版本
  mcdebug session start dev --project . --world "New World"                  # world 是 saves/ 下的目录名
  mcdebug --session dev status                      # 后续每次调用都带 --session dev
  mcdebug session stop dev                          # 仅限自有会话，会保存世界
  ```

  多工程构建中，指定 `--game-dir core/run` 即可，任务会变为 `:core:runClient`；
  裸 `runClient` 会同时启动各子工程的客户端。每个 gameDir 只运行一个客户端，
  第二个实例会因 `session.lock: already locked` 回到标题界面。
  到达 `world_ready` 通常需要几十秒，取决于工程。若 MC 或构建提前退出，`start` 会立即返回
  `failed`，`lastError` 包含崩溃行，`session logs dev` 提供完整输出。
  Fabric 加载器错误，如重映射或缺少依赖，也会返回 `failed`，但错误窗口可能使 JVM 继续存活，
  此时使用 `session stop --force`。
  `--width/--height` 的优先级低于工程 `loom.runs.client` 中的 `programArgs '--width' ...`。
  会话记录仍显示请求尺寸，`gui status` 显示实际窗口。
  托管会话的 `--render` 默认是 **demand**，模组全局默认仍是 continuous。
  任务需要正常连续帧循环时传入 `--render continuous`。
  托管启动默认**隐藏提示弹窗**（移动教程、配方解锁、进度，以及走标准弹窗队列的模组通知）。
  `session start --show-toasts` 在启动时开启，`mcdebug --session NAME gui toasts on|off` 在当前进程内切换；
  关闭时清理已显示和排队中的弹窗，重新开启后只显示之后的弹窗，`gui status` 的 `toastsEnabled` 报告状态。
  模组通过启动令牌环境变量识别托管启动，普通启动与 `session attach` 保持原版行为。
  旧 CLI 启动的托管客户端同样隐藏，可用 `raw gui.toasts '{"enabled":true}'` 打开；
  新 CLI 启动旧模组时弹窗照常显示，`gui toasts` 返回方法不存在（退出码 3）。

  状态为 `starting` → `client_ready`（标题界面）→ `world_ready`（需要 `--world`，且必须已进入单人世界），
  随后为 `stopping` / `stopped`；失败状态为 `failed`（检查 `lastError`）和 `dead`。
  `session logs NAME --lines N` 读取日志尾部，选项是 `--lines`，不存在 `--tail`。
  `session stop` 后保留发现文件 `mcdebug/port`、`clientPort`、锁、`client.json`、`session-stop.json`，
  只移除套接字，因此 `port` 文件存在不代表端点存活。
  即使停止已报告 `stopped`，Gradle 包装进程仍可能残留约 10 秒；立即检查残留进程可能误报，
  在同一 gameDir 立即重启也可能冲突。

  记录位于 `$XDG_STATE_HOME/mcdebug/sessions/<name>.json`，可用 `--sessions-dir` 或环境变量
  `MCDEBUG_SESSIONS_DIR` 更改。`session remove NAME` 可随时移除连接会话记录，游戏继续运行；
  自有会话须先到达 stopped / failed / dead。不要手动删除锁或发现文件，它们用于防止操作错误的 JVM。
  传输选项按平面区分：`--socket` / `--tcp` 指向**服务端**，
  `--client-socket` / `--client-tcp` 指向客户端，`--session NAME` 按方法自动路由。
  向 `gui.*` 传 `--socket` 会被静默忽略，仍走客户端发现；未显式指定时，以**当前目录**发现端点，
  因此应在游戏或工程目录执行，或使用绝对路径。
  Unix 套接字路径超过操作系统上限，通常约 107 字节，或 JVM 不支持 AF_UNIX 时，
  模组会回退到回环 TCP，发现文件记录 `tcp:127.0.0.1:N`，CLI 自动遵循，此为正常行为。
- **已有服务端或客户端**：使用 `--socket <gameDir>/mcdebug/socket` 连接服务端平面，
  `--client-socket <gameDir>/mcdebug/clientSocket` 连接客户端平面，或 `--tcp host:port` 跨机器连接。
  在 gameDir 中执行可自动发现。
- 先运行 `mcdebug status` / `mcdebug gui status --brief`。CLI 与模组版本不一致会输出警告。

## 2. 服务端平面：布置与断言

```bash
mcdebug raw world.setBlock '{"pos":[100,64,100],"block":"mymod:machine"}'
mcdebug raw inv.setSlot    '{"pos":[100,64,100],"slot":0,"item":"minecraft:coal","count":8}'
mcdebug raw storage.insert '{"target":{"kind":"block","pos":[100,64,100]},"handle":"teamreborn:energy","resource":{"kind":"energy"},"amount":10000}'
mcdebug raw be.getNbt      '{"pos":[100,64,100]}'
mcdebug raw wait.until     '{"predicate":"be[100,64,100].progress >= 100","timeoutTicks":400}'
```

- 方块状态使用 `"stateProps":{"facing":"east"}` 或 `"state"`，CLI 使用可重复的 `--state k=v`。
  省略的属性保留默认值。不指定属性时会按邻居调整形状，类似 `/setblock`，例如红石线连接和栅栏相连；
  不执行其他放置逻辑，如朝向玩家或物品放置副作用。要有实心地板，红石线、拉杆、重力方块和玩家都会从空中掉落。
- 坐标为 `[x,y,z]`；盒状区域为 `{"from":[..],"to":[..]}`，两角均包含在内，须放在 `box` 键下，
  如 `raw scan.findEntities '{"box":{"from":[0,60,0],"to":[10,80,10]}}'`。
- `inv.*` 只访问方块实体容器。访问**玩家背包**时，先用玩家周围的 `scan.findEntities` 获得 UUID，
  再通过 `raw entity.getNbt '{"uuid":"..."}'` 读取 `Inventory[]`，其中 `Slot` 是玩家自身编号；
  或在容器 GUI 打开时使用 `gui screen`。
  给玩家物品使用 `raw server.runCommand '{"command":"give @p minecraft:diamond 4"}'`；
  清空、传送、设置时间等任意服务端命令也可通过此方式执行。
  命令反馈随 RPC 响应返回，默认不向玩家发送原版管理员聊天提示；
  需要时用 `raw --show-chat server.runCommand ...` 或在原始 RPC 参数中传 `"showChat":true`，
  旧模组忽略该参数并保持原有聊天行为。
- `storage list` 显示方块暴露的句柄。`storage.*` 是通用物品、流体和能量 API，
  句柄为 `vanilla:inventory`、`fabric:item`、`fabric:fluid`、`teamreborn:energy`，
  两个加载器名称相同，底层分别由 Fabric Transfer API 或 NeoForge 能力实现。
  **数量使用加载器原生单位**，跨加载器比较前先读取 `server.status.fluidUnit`：
  Fabric 为 `droplet`，81000 = 1 桶；NeoForge 为 `mb`，1000 = 1 桶。
  能量读取 `server.status.energyUnit`，Fabric 为 `E`，NeoForge 为 `FE`。
  NeoForge 的 `fluid.* --index` 只选择**报告**哪个罐，填充与抽取通过整个 `IFluidHandler` 路由；
  `supportsInsertion/supportsExtraction` 表示**当前状态**，例如满罐返回 `supportsInsertion:false` 但仍可抽取，
  Fabric 则使用能力标志。不要跨加载器比较这两个值，也不要将 false 理解为永远不接收。
  26.3 两个加载器的 `reflect.*` 均使用官方名称，只有 Fabric 支持短类名。
  `reflect get` 和 `reflect set` 均接收 `--path`；`set` 写入**最后**一段，
  例如 `--path overworld.dayTime` 写入 overworld 的 dayTime；`--field` 是直接形式。
  大集合返回摘要，引用含 `size`，被截断时附带 `truncated:true` 和 `returned:N`；
  内联普通值数组以 `{"$truncated":<how many more>}` 结束。
  判断键不存在前先读 `size`，通过 `reflect get` / `call` 的 `--limit N`（1..10000）和 `--offset M` 分页。
  例如读取全部配方 ID：`reflect get --root server --path 'recipeManager.byName.keySet()' --limit 10000`。
  要使生物在多次反射调用间不移动，先 `/tick freeze`，完成后 `/tick unfreeze`。
  大量命令可写入世界数据包函数 `<world>/datapacks/<p>/data/<ns>/function/x.mcfunction`，
  1.20.x 使用 `functions/`，执行 `reload` 后一次 `server.runCommand` 调用 `function <ns>:x`，再用一次扫描读取结果。
  `inserted` / `extracted` 可能远小于 `amount`，因为方块自身通常有**每 tick** 传输限制；
  同一 tick 中紧密循环或 `--batch` 重试会得到 0。`remaining` 只描述本次调用，应用 `storage get` 复查。
  想快速填满时应写方块实体 NBT，不要反复调用传输。
- **没有玩家的服务端不会持续保留实体**：原版会移除附近无玩家的生物，专用服务端实测约 3 秒，
  即使设置 `PersistenceRequired` 也一样。因此 `entity spawn` → `scan.findEntities` 看似失效时可能只是实体已消失。
  先铺地板，生成不会自动贴地，猪曾从 120 掉到 111。
  将生成与断言放在一次 `-c` 批处理中，一次往返且耗时不足 1 秒；下结论前再用 `scan.findEntities` 核对数量。
- **缓慢过程用快进**：对作物生长、长配方机器和环境转化等耗时多个游戏分钟或天的过程，
  用 `mcdebug wait until "<pred>" --timeout-ticks 72000 --sprint`。
  服务端申请原版 `/tick sprint` 预算，即超时加余量，由引擎全速运行自己的 tick，
  命中、超时或断连时自动停止。也可通过同一原版引擎手动执行
  `raw server.runCommand '{"command":"/tick sprint 3d"}'`，有界且自动停止，
  `"/tick sprint stop"` 提前停止；或 `"/tick rate 200"` 持续提速，**不会自动恢复**，须用 `/tick rate 20` 还原。
  `server health` 显示加速后的实际 TPS，上限随目标速率变化。
  只有 tick 驱动的状态会加速，按 `System.currentTimeMillis` 等墙钟计时的冷却不会。
  不要混用手动 `/tick sprint` 和 `--sprint`；集成服务器必须未暂停。
- 物品参数中的 `--nbt` **只表示 `minecraft:custom_data` 复合标签**。
  `inv.getSlot` 会原样回传，因此往返一致不能证明模组已读取它。
  注册表数据或原版数据，如附魔、储存附魔、药水内容、损伤、自定义名称，须使用 `--components` / `"components"`，
  格式为原版补丁：`--components '{"minecraft:enchantments":{"levels":{"minecraft:unbreaking":3}}}'`；
  `"!minecraft:id":{}` 移除默认组件。所有接收物品的命令均支持，包括
  `world use-on-block/use-item/attack-block/...`、`inv set-slot/insert`、
  `storage insert/extract/transfer --item` 及护甲或堆叠 JSON 对象。
  返回堆叠的非默认组件位于 `components`，自定义数据仍位于 `nbt`，无法编码的组件列在 `components."#errors"`。
- `status` 的 `playerList` 列出真实玩家的 name、uuid、dim、pos，UUID 可用于 `entity teleport/get-nbt`。
- 服务端菜单 `screen open-block` 优先使用方块的 `MenuProvider`。加载器拒绝时，例如 Fabric 扩展菜单或
  需要额外打开数据的菜单，mdb 会像玩家一样空手右键，结果包含 `openedBy: menu_provider|use_block`。
  `screen click-button --button N` 调用原版 `clickMenuButton`，可选择切石配方、织布图案、附魔选项或模组配方和流体，
  `accepted` 是菜单返回值。配合 `inv set-slot` 和 `screen quick-move` 可无图形运行完整工作台类流程。
- `wait until` 可观察任意存储：`storage[x,y,z].<item|fluid|energy|inventory>.<path into the storage get answer>`，
  例如 `'storage[0,64,0].fluid.tanks.0.amount >= 81000'`。
  `slots` 只列非空槽，应按 `slots.N.index` / `stack.item` 匹配，不要按数组位置猜槽位。
- **场景的像素验证**：容器或 UI 可固定，`--mode gui` 裁剪到 GUI，`gui screen` 给出槽位矩形。
  世界帧没有区域裁剪选项，天气和实体动画可使两张未经编辑的帧有数十个百分点的像素差异。
  世界场景应用逻辑断言，如 `gui open-block` 成功、绘制日志含所放物品、服务端状态一致；
  `pixelHash` 只表示帧发生变化，`gui compare` 应用于稳定 UI，并设置 `--ignore` 区域。
- 两个平面的槽位坐标不同：`gui screen` 使用**绝对 GUI 单位**，与 `gui click --gui` 一致；
  `screen.*` 的 `slotDetails` 和服务端菜单使用容器相对坐标，须用 `gui screen` 中的 `gui.left/top` 转换。
  `snapshot` / `storage` 中的槽位 `capacity` 是实际可接收容量，
  即 `min(slot limit, the stack's own max size)`，空槽按 64。
  槽中物品变化时容量也可能变化，不能作为不同物品间固定比较的夹具值。
- `trace get` 的帧是 `{tick, snapshot}` 对象，snapshot 结构与 `snapshot.*` 相同；
  比较轨迹应逐帧读取关心字段，不要比较整个帧。
- 捕获盒状区域快照用 `snapshot capture`，不存在 `snapshot.take`。
  `snapshot --help` 列出操作，没有通用的列出全部 RPC 方法命令，应使用各命令组的 `--help` 和本技能。
- `be.setNbt` 将给定标签加载到方块实体，未提供的键如何处理取决于该实体的加载代码，只写需要修改的键。
  `be set-field` 写入单一路径后回读；加载代码忽略未知键或派生值时，返回 `ok:false reason:NOT_APPLIED`。
  普通数字沿用已有字段的数值类型。应用后字段可能立即被方块自身 tick 改变，不代表未应用。
  一个值可能分布于镜像字段或拆分 long 等多个字段，应通过 `storage get`、`inv.getSlot` 或 GUI 等实际 API 验证，
  不能只回读 NBT。
- `raw craft.craft` 接收 `{"grid":[ ... 9 cells ... ]}`，每格必须为 **null 或对象**，
  如 `{"item":"minecraft:oak_log","count":1}`；裸字符串会返回 `-32602` 并指出 `grid[i]`。
  `craft do <item>` 是自动构造合成网格的快捷方式。
- 方块槽位编号由其自身定义，应通过打开的 `gui screen` 或 `inv get-size` 加 `inv get-slot` 确认输入和输出，不能猜。
  `inv extract` 是存储式抽取，要求 `--item`；清空整个槽用空物品的 `inv set-slot`。
- **不驱动世界 tick**：用 `wait.until` 等待，谓词 DSL 支持 `be[..].<path>`、`inv[..].<slot>.count`、
  `block[..].id`、`block[..].prop.lit == true`、`AND/OR/NOT` 和 `sum(inv[..].*.count)`，见 `wait --help`。
  **优先短等待**，设置几秒 tick，条件只是较慢时再等待；超时不破坏状态，下次可继续。
  错误路径、错误槽位或未供电机器上的长等待只会耗尽整个超时时间。
  正式等待前，先用 `be.getNbt` / `inv.getSlot` 读取谓词字段，或用 `timeoutTicks` 1–20 试跑并检查 `lastValue`。
  null 或非预期值通常表示谓词错误。唯一例外 `be.tick`（`ticks` 为 0..12000）同步运行单个方块实体的真实 ticker，
  速度快但不推进世界时间，也不 tick 邻居，其他方块的供能、推送物品和红石作用均不会发生；
  该真实 ticker 仍会消耗能量、燃料和输入。`wait.until` 的 `ranTicks` 表示条件实际用了多久。
- `trace start` 每 `--interval-ticks` 记录快照，`--max-ticks` 自动停止，返回 `traceId` 用于 `trace get` / `trace stop`。
  与快照共用 `--include`，如 `block`、`inventory`、`entity`；机器槽位需要 `inventory`。
  `trace get` 返回全部帧，应通过过滤器提取，不要一次阅读全部内容。
- `snapshot capture` 加 `snapshot diff` 比较状态，默认忽略元数据 `/tick` / `/time`。
  差异路径是快照中的 JSON 指针，`/blocks/13/...` 是数组索引而非坐标。
  一个堆叠可能在每个暴露它的句柄中各出现一次；实体和记账 NBT 自身会变化，须将 `--include` 缩小到被测内容。
  快照包含绝对坐标，同样的结构搬到另一原点也会全部显示变化。
- `fixture capture` 将区域保存为 JSON，只有 `--include-nbt` 才包含箱中物品和机器 NBT；
  `fixture load --origin` 可将其放到任意位置。
- `redstone` 提供 `set-lever --powered|--unpowered`、`pulse`、`get-power`（`received` 和分面的 `inputs` / `outputs`），
  修改时跳过更新的可用 `notify-neighbors` 补发。
- 其他状态用 `reflect`。从 `--root server|level|be|entity|player` 和 `--pos` / `--uuid` / `--name` / `--dim` 出发，
  沿 `--path a.b[0].c` 访问；每段先查字段，再查无参方法，最后查 `getX` / `isX`，不支持参数或表达式，
  带参调用用 `reflect call`。支持 Mojang 名称，`reflect resolve --root ... --path ... --members` 可列出成员。
  对象返回 `{"$ref":N}`，可在后续命令使用，数量有界且服务端停止后失效。优先一次 `--path`，少在引用间往返。
  `reflect set` 写非 final 字段，数字转换为字段类型，报告修改前后值；
  `reflect call` 重载有歧义时要求 `--arg-types int,long`。
  它绕过方块自身逻辑，不同步、不执行 `setChanged`，有正常 API 时优先用正常 API。
  客户端的 Screen、控件和缓存使用同一命令加 `--client`，根为 `minecraft|screen|player|level|server`，
  如 `reflect get --client --root screen --path 'children[0].message.string'`。
  引用位于客户端进程，后续每次使用都必须带 `--client`。
  客户端方块实体**不携带物品**，即使 GUI 显示内容，客户端箱子或机器 BE 仍为空，
  原版将库存同步到打开的菜单，而非 BE。读取客户端显示用 `--root screen --path 'menu.slots[N].item'`，
  权威状态用服务端平面。服务端到客户端相差一个数据包，读取前先 `gui settle`。
  客户端 `reflect set` 也不会写 options.txt；`OptionInstance.set` 只改变运行值，
  可在 `gui status` 或视频设置看到，但不会保存到磁盘。
- `screen.*` 是**服务端假玩家菜单**，只有槽位 JSON，没有像素；像素只能来自 `gui.*`。

## 3. 客户端平面：GUI 操作流程

> **无焦点环境中的提示框需要轻量渲染模式。** 无窗口管理器的无头或 CI 环境中，
> 游戏窗口不会取得焦点，Minecraft 在失焦时忽略真实光标移动。
> 默认 `continuous` 模式下，`gui hover` 虽移动指针，游戏却仍用旧位置计算悬停，
> 表现为 `tooltips: []` 或 `hoveredSlot` 指向错误背包槽。
> 应以 `clientRenderMode=demand` 或 `gui-only` 启动，模组会向渲染帧提供**虚拟光标**，
> 失焦时也能悬停。点击、截图、`gui screen`、`scroll`、`key`、`type` 和 `drag` 在两类模式中均可使用。

```bash
mcdebug gui open-block --x 100 --y 64 --z 100            # 也可用 gui open-screen pkg.Fixtures#myScreen
mcdebug gui screenshot --mode gui --annotate             # *.annotated.png：青色为槽位编号，品红为控件编号
mcdebug gui screen --brief                               # 槽位和控件 JSON，使用 GUI 单位
mcdebug gui click --slot 0 --shot gui                    # 点击、界面稳定、截图，一次往返完成
mcdebug gui hover --slot 0                               # 返回该帧真实绘制的提示框
mcdebug gui hover --slot 0 --hold-shift                  # 本帧按住 Shift，用于按住 Shift 显示更多的提示
mcdebug gui screenshot --mode gui --draw-log             # 各次 blit、填充、文字及其 GUI 矩形和纹理 UV
mcdebug gui problems --brief                             # WARN / ERROR 日志和被捕获的渲染异常
```

- **`--slot N` 是当前菜单的索引**，即 `gui screen` 的 `slots[].index` 和青色数字，不是玩家背包编号。
  菜单先列方块自身槽位，因此玩家 `Inventory` 的 Slot N 对应另一个菜单索引。
  找物品时先按 `item` 过滤 `gui screen` 的槽位，再使用其索引。
- 点击目标支持 `--slot N`、`--widget N`（`gui screen --brief` 中的 `[N]`）、`--text "Label"`、
  `--gui X,Y` 和上一张截图像素坐标的 `--png X,Y`。
  文字按不区分大小写的子串匹配，也匹配 renderBg 中绘制的文字和非控件列表行，例如世界列表项。
- 滚出可视区域的条目在 `gui screen` 中标为 `[clipped]`，点击会返回 `WIDGET_CLIPPED`。
  先在列表上执行 `gui scroll --dy -N --gui X,Y`。
  只有已渲染行的坐标有效，因此在此类 Screen 上查询 `gui screen` 或解析目标前，mdb 会先渲染一帧。
- `open-block` 走真实客户端使用路径：玩家必须在交互距离内，默认空手，且方块必须已存在于**客户端**。
  `reason:NO_BLOCK` 和 `block` 表示客户端所见，例如铁砧从空中掉落。
  遇到 `reason:OUT_OF_REACH` 和 `distance` / `reach` 时，先将玩家传送到附近。
- 槽位点击遵循菜单规则。`mayPlace` 拒绝物品时，物品仍留在光标上，表现为 `changed:false` 且 `carried` 非空。
  应检查 `slotAfter` / `carried`，不要只看 `ok`。
- `gui close` 等同 Esc，子界面返回父界面，例如视频设置返回选项，回到世界可能需要多次关闭。
  关闭容器时，光标上的堆叠交回原版处理。
- `gui key` / `type` 只作用于当前 Screen，没有 Screen 时返回 `-32020`。
  `type` 向有焦点的 EditBox 追加文字，许多文本框已预填内容，如铁砧中的物品名称，
  替换时使用 `gui type --text X --clear`。
  `handled:false` 表示没有控件接收字符，可能无焦点或文本框禁用，例如铁砧输入槽为空。
  `handled:true` 且文字已改变时，容器仍可能拒绝输出；铁砧改名需要经验，
  此时 `click --slot 2` 仍返回 `changed:false`。以点击报告为准，不要只看 `handled`。
- `gui act` 中后续视图失败不会丢失已执行动作，例如跳到选项后 `--shot gui` 失败。
  动作结果保留，错误以 `screenshotError` / `screenError` 和 `ok:false` 返回。
- `--text` 对整个 Screen 做子串匹配，如按钮文字也含标签文字，可能先命中按钮；
  例如 `New World` 同时包含在 `Create New World` 中。
  先用搜索框缩小范围、传 `--index`，或根据 `gui screen` / 截图点击该行的 `--gui X,Y`。
  Esc、Enter 等导致跳转的按键返回 `ok:true reason:CHANGED_BY_INPUT`。
- 携带堆叠在槽位上执行 `gui drag`，会触发原版拖拽分配 `QUICK_CRAFT`。
- 普通 `gui click/hover/...` 无法执行时退出码为 2，标准错误包含 `error [-320xx]`，
  如 `-32021 WIDGET_CLIPPED`；相同错误在 `gui act` 中以 JSON `ok:false` 加 `error` 返回，退出码仍为 0，须检查 `ok`。
- `gui hover` 没绘制内容时返回 `tooltips:[]` 和 `ok:true`。
  `reason:EMPTY_SLOT` 及 `stack` 表示槽位为空，是悬停索引错误，不应盲目重试。
- `gui scroll --dy` 的单位是滚轮刻度，选项列表每刻度约一行，负数向下。
  超量滚动无害，`--dy 30` 可回到顶部。
- `gui open-screen` 只自动填充 Screen / Player / Minecraft / Options 构造参数。
  需要其他参数的类，如 `ChatScreen(String)` 或创造模式背包，须提供 `pkg.Class#factory`。
- 点击选项或视频设置会修改该 gameDir 的真实 `options.txt`。
- `gui disconnect` 将客户端留在标题界面并移除服务端平面套接字。
  可通过 `gui click --text Singleplayer`、`gui type --text <save>` 搜索，
  再 `gui click --text <save>` 和 `gui click --text "Play Selected World"` 重新进入。
- 服务端写入后，观察前先执行 `mcdebug gui settle`；点击会自行等待稳定。
- 从 `gui screen`、点击报告、`be.getNbt` 等数据读取结果，不要只凭像素。
- Screen 渲染异常不再使客户端崩溃，只停止该 Screen 绘制，`gui problems` 提供堆栈。

### 3b. CLI 选项与原始 RPC 参数

CLI 选项是 RPC 参数的便捷形式。使用 `raw` 时，必须按处理器实际读取的名称传参：

| CLI 选项 | 原始 RPC 参数 |
|---|---|
| `gui screenshot --mode window\|gui` | `mode: "window" \| "gui"`；**没有** `crop` 参数，`mode:"gui"` 裁剪容器矩形，其他裁剪用 `--out` 后在本机处理 |
| `gui screenshot --hash-only` | `format: "none"`，不生成 PNG 或文件 |
| `gui screenshot --hide-cursor` | `cursor: "hide"`，默认也可用 `"keep"` |
| `gui screenshot --annotate` / `--draw-log` | `annotate: true` / `drawLog: true`，另有 `delayTicks`、`inline`、`name` |
| `gui click --slot/--widget/--text/--png/--gui` | `slot` / `widget` / `text` / `png:[x,y]` / `gui:[x,y]` |

未知原始参数会被**静默忽略**，拼错 `crop` 可能返回整个窗口而非报错。
关注结果尺寸时，应检查响应中的 `transform.crop` / `pngSize`。
`gui click --gui` / `--png` 只报告落点 `guiX/guiY`，不像 `--slot` / `--widget` / `--text`
那样包含 `via` / `before`。`gui drag` 只回显**终点**，须自行保存 `--from`。
没有 Screen 时，`gui key` / `gui type` 与客户端未就绪共用 `-32020 CLIENT_NOT_READY`，
应根据 `data.reason` 的原文 `no Screen is open` 区分。

- `gui open-screen` 接收**运行时类名**或 `inventory` 别名，没有列出全部 Screen 类的命令。
  未知名称返回 `CLASS_NOT_FOUND`，仅附带猜测的类名。
  应从运行中的界面获取：`gui screen` / `gui status` 输出当前类，
  `reflect resolve --client --root screen` 给出可直接复用的完整运行时名称。
  自己工程的 Screen 可通过仅在开发类路径中的 `pkg.Fixtures#make` 工厂访问。

## 4. 无需重启的代码更新

```bash
./gradlew classes                                                # 分离源集时，客户端代码使用 clientClasses
mcdebug gui hotswap --classes build/classes/kotlin/client        # 仅方法体，全部成功才应用，并重新初始化 Screen
mcdebug gui reload-screen --classes build/classes/kotlin/client  # 当前 Screen 自身类可任意变化，沿用同一菜单
mcdebug gui reload-resources                                     # 纹理、语言和模型
```

使用构建实际输出的目录，如 `build/classes/java/main`、`.../kotlin/client`。
类增删成员时，热替换通过 `failed[].changes` 报告；Screen 类可改用 reload-screen，其他情况重启会话。
两种方式都不会重新运行已有对象的构造器。耗时主要在构建，编辑后的首次增量编译明显比后续更慢；
热替换自身远小于 1 秒，比完整重启快。`attached:true` 表示本次将热替换代理装入 JVM，
false 表示已经装入，是正常情况而非失败。

- `changed:0` 加标准错误提示表示目录中没有更新的类，可能选错目录或编译任务。
  相对 `--classes` 基于 CLI 当前目录，而非游戏目录；在其他目录执行时传绝对路径。
- reload-screen 显示的是独立类加载器中的副本。后续 hotswap 修改真实类，
  `skippedReloadCopies` 列出被跳过副本，不会修改当前副本，`hint` 会说明。
  再次 reload-screen，或关闭并重新打开 GUI，才能使用已热替换的真实类。
- 失败时退出码为 2，不重新定义任何类，操作全部成功才应用。
- `reload-screen` 的 `reloads` 是该 Screen 到目前为止的重载次数，`classes` 列出已加载内容。

## 4b. 两个平面共用一个 CLI

`world.*` / `be.*` / `inv.*` / `storage.*` / `scan.*` / `wait.*` / `reflect.*` 使用**服务端**平面，
`gui.*` 使用**客户端**平面。`--session NAME` 按方法选择平面；显式传输须自己选择，
`--socket` / `--tcp` 指向服务端，`--client-socket` / `--client-tcp` 指向客户端。
只指定服务端传输却调用客户端方法时，CLI 会在发送前拒绝，退出码为 1，
不会得到服务端 `-32601 method not found`。
向 `gui.*` 传入的 `--socket` 会被静默忽略，该调用仍使用相对于当前目录的客户端发现。

## 5. 回归验证

- `gui compare` 不匹配时退出码为 1，标准输出同时含 JSON 行和便于阅读的 `error: ...` 行。
  应解析退出码或 JSON 行，不能把整个输出流当成 JSON。
  相同视图仍会因玩家模型动画和隐藏光标时的插值产生差异，应对相关区域设置 `--ignore x,y,w,h`。
  尺寸不匹配会立即失败，不写差异图。

先执行 `mcdebug gui screenshot --mode gui --hide-cursor --out new.png`，再执行
`mcdebug gui compare --expected golden.png --actual new.png --ignore x,y,w,h --report r.html`。
不匹配退出码为 1；`--ignore` 使用 PNG 像素且宽高必须大于 0。
读取 `$?` 时不要加管道，或先 `set -o pipefail`，因为 `| head` 会掩盖退出码。
只有显式 `--accept` 才写基准图。
`--mode gui` 裁剪**容器** GUI，因此必须打开箱子、熔炉或背包等容器 Screen；
选项类 Screen 会返回 `NO_CONTAINER_SCREEN`，应使用 `--mode window`。
即便如此，半透明纹理边缘仍会与世界背景混合；相同截图仅每通道差 1 时，可用 `--channel-tolerance 2`。
`--annotate` 配合 `--out x.png` 还会写入 `x.annotated.png`；原始 `path` 仍位于
`<gameDir>/mcdebug/screenshots/`，本机副本位于 `copiedTo`。
`--hash-only` 可低成本检测是否发生变化。

`--draw-log` 增加 `drawLog: {count, truncated, coordinateSpace, entries[]}`。
每项含 `op`，如 `blit` / `fill` / `text` / `item` / `tooltip`，
以 GUI 单位表示的 `rect` `[x,y,w,h]`，以及各操作的 `text`、`color`（`#AARRGGBB`）、`texture`、`uv`。
相同文字可能被绘制多次，例如 Screen 把原版标签放在屏幕外再绘制自定义标签，
应保留 `rect` 位于 GUI 内的条目。
`gui problems` 覆盖整个 JVM，包含其他模组的启动噪声，应比较动作前后的 `--since` 游标，不要读全部列表。

## 开销

使用 `java -jar` 时，单次调用的主要开销是 JVM 启动，RPC 自身通常只占一小部分，
截图和稳定等待另需几帧或几 tick。
连续多步可在同一 JVM 中使用 `mcdebug --session dev --batch -c "..." -c "..."`，
通过 `gui act` 一次往返获取动作、稳定、截图与 Screen，或使用原生 `mdb`。
`-c` 内的 JSON 可直接写成 `-c 'raw be.getNbt {"pos":[0,64,0]}'`，大参数放文件，用 `raw method @params.json`。
`reload-resources` 重载所有资源包，耗时按秒计，不是毫秒。

## 退出码与错误

`0` 表示成功，包括业务 `ok:false`；`1` 为用法错误，`2` 为 RPC 错误，`3` 为方法不存在，通常模组过旧。
`-32602` 表示参数缺失或错误，消息会指出名称；`-32603` 为服务端异常，
附带 `data.exception` 和 `data.at` 堆栈；`-32020` 为客户端未就绪或未进入世界；
`-32021` 为 GUI 元素未找到，数据中列出候选项。

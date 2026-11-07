# Minecraft 1.20 → 1.21 版本迁移陷阱

本文件独立记录 Minecraft 版本变化，区别于同版 NeoForge → Fabric 的加载器适配。即使输入是“已移植到 1.21”的第三方基线，也要核实它是否仍沿用 1.20 的计算；编译通过不证明单位正确。仅列已经核实的通用 API 差异。

## 1. TextureAtlasSprite 的 UV 插值由 0–16 改为 0–1

**边界：** `TextureAtlasSprite#getU(float)` / `getV(float)` 在 1.20 接收以 16 为跨度的局部贴图坐标，在 1.21 接收归一化局部坐标。它们的返回值仍是整张 atlas 上的 UV。

设 `x` 是贴图内的比例，范围为 0–1：

```java
// 1.20
float u = sprite.getU(16.0f * x);
// 1.21
float u = sprite.getU(x);
```

- 已有 0–16 坐标 `u16` 时，改为 `sprite.getU(u16 / 16.0f)`；已有 `pixelX / spriteWidth` 时直接传入，不再乘 16。V 同理。
- 1.21 的 `getUOffset` / `getVOffset` 返回相对 sprite 的 0–1 比例；不要继续把结果当成 0–16，或再次无条件除以 16。
- `getU0/getU1/getV0/getV1` 已是 atlas 上的坐标，不走上述换算。
- **不能全局删除或添加 16。** `BlockFaceUV` / 模型 JSON 的面 UV 仍使用 0–16；GUI 像素坐标、纹理尺寸以及 atlas 归一化 UV 是不同的量。沿实际调用链确认在哪一处转换。

**典型症状：** 正反面使用 `getU0/getU1` 因而看起来正确，手工生成的物品侧面却拉伸、出现邻近 sprite 的颜色或 atlas 杂纹；错误通常不会抛异常。

**核实与验证：** 先读目标版本的 Minecraft 源码或反汇编，确认 `getU` / `getV` 的实际插值公式，再核对调用处的单位。只针对受影响渲染路径检查真实侧面、纹理边界和需要保留的像素偏移；GUI 正面截图不足以证明侧面 UV 正确。通常无需为单位修正另建冒烟测试。

## 2. 物品数据从 NBT 迁到 codec/数据组件后，新旧键名并存

**边界：** 1.21 把物品数据放进数据组件，原先手写 NBT 的值类型多数改由 codec 序列化，字段名随之改变（例如流体栈由 `FluidName`/`Amount` 变成 `id`/`amount`）。第三方 1.21 基线常常只迁移了主写入路径，次要写入器、读取端和数据文件仍沿用 1.20 键名。

**典型遗留位置：**
- 不经过 codec、直接拼 `CompoundTag` 的辅助写入器（创造栏/配方查看器的预填变体、“未校验”快捷构造）。
- 只读单个键的派生值：配方查看器 subtype、tooltip、模型 predicate、排序/比较键。
- 数据包里写成 SNBT 字符串的谓词与图标：进度的 `minecraft:custom_data`、战利品/配方的物品组件。
- 物品数据仍包在 `CUSTOM_DATA` 里的项目最容易出错：组件类型不会报错，只是新旧子树互不匹配。

**典型症状：** 不崩溃、不报解析错误；预填物品取出后为空，配方查看器把不同状态的物品判成重复（如出现“duplicate items”告警），进度永远无法达成。

**核实与验证：** 对每一种序列化格式，列出全部写入器和读取器，并 grep 旧键名，Java 与 `data/**/*.json` 都要查。每个写入器都要做“写入 → 原生读取”往返，比较读出的数量和类型，不只看 NBT 是否存在。数据文件里的谓词，用真实写入路径产出的物品触发一次，不要手写理想化的 NBT。开发阶段没有旧存档兼容要求时，统一成一种格式即可，不要为旧键名补读取分支。

## 3. 注册表 codec 配合裸 `NbtOps`/`JsonOps` 静默失败

**边界：** 1.21 的附魔、伤害类型、纹饰等改为数据驱动注册表，`ItemEnchantments.CODEC` 这类 codec 的键走 `RegistryFixedCodec`，只有 `RegistryOps` 才能编解码。1.20 时代用裸 `NbtOps.INSTANCE` 做“快照再恢复”或手工序列化的代码照搬过来后，`encodeStart`/`parse` 返回 `DataResult.error`；再配上 `.result().orElse(...)` 回退，错误被吞掉，结果是空值。

**典型症状：** 不报错；临时改写后恢复的数据被清空（例如挖掘时临时叠加附魔，恢复后工具原有附魔全部丢失），同步/存档的字段读出为空。

**核实与验证：** grep `NbtOps.INSTANCE`、`JsonOps.INSTANCE` 与 `.result().orElse`/`orElseGet` 的组合，确认 codec 是否含注册表引用。只是在内存里暂存时，直接保留不可变的组件值；必须序列化时用 `provider.createSerializationContext(ops)`。验证要用“非空”的输入：空集合往往能编码成功，掩盖问题。

## 4. `getDefaultTooltipHideFlags` 消失后 tooltip 重复

**边界：** 1.20 Forge 的 `IItemExtension#getDefaultTooltipHideFlags` 在 1.21 被移除，隐藏改由 `ATTRIBUTE_MODIFIERS`/`ENCHANTMENTS` 组件的 `showInTooltip` 决定。自绘属性列表或依赖按键（shift/ctrl）切换视图的模组，原覆盖方法变成无人调用的死代码。

**典型症状：** 不报错；物品 tooltip 同时出现模组自己的属性行和原版 "When in Main Hand"/附魔行。常规视图可能看起来正常，只有按住 shift/ctrl 的视图才重复。

**核实与验证：** grep `HideFlags`/`getDefaultTooltipHideFlags`/`TooltipPart`，确认是否仍有调用方。在目标平台按 1.20 的掩码语义接管 `ItemStack#addAttributeTooltips` 和对应组件的 tooltip，按键相关时要在每次绘制时求值，不能写进组件。验收要真实按住修饰键截图：合成的按键事件不一定改变 GLFW 键状态，截图前确认 tooltip 确实切换了视图。

## 5. Forge 条件配方拆分后的分支语义

**边界：** 1.20 的 `forge:conditional` 按顺序取第一个条件成立的分支；1.21 只剩每个文件一组 `conditions`，迁移时要把每个分支拆成单独文件，并给后面的分支加上“前面所有分支都不成立”。手写或脚本生成的否定链很容易出错：漏掉外层条件、把本分支自己的条件也放进否定里（该分支永远为假）、`and` 的成员丢失。

**典型症状：** 不报错，数据加载干净；只有某种模组组合下才缺配方（例如“装了 A 没装 B”时某合金或熔化配方消失）。默认环境下抽样测不出来。

**核实与验证：** 不要逐个文件读条件。把条件当布尔公式，原子是 `tag_filled`、`mod_loaded`、`config` 等，对每个逻辑配方枚举全部原子真值组合，比较“旧版首个命中分支”与“新版条件成立的文件集合”，二者必须恰好一一对应。再在运行中的服务端取实际加载的配方 ID 和实时标签内容，把条件求值结果与实际是否加载对照。

## 6. Forge 属性改映射到原版属性前先核对基础值与运算

**边界：** Forge 的 `swim_speed`、`entity_gravity`、`step_height_addition`、`block_reach` 等在 1.21 有的变成原版属性，有的没有原版对应；Fabric API 也不提供 Forge 自定义属性。把数据里的属性 ID 换成一个“名字相近”的原版属性时，语义可能完全不同。例如 `water_movement_efficiency` 是基础值 0、范围 0..1 的阻力混合系数，不是基础值 1 的速度乘数。

**典型症状：** 不报错，修饰器正常显示；属性最终值不变或恒为 0。原因是 `add_multiplied_*` 乘在基础值 0 上。

**核实与验证：** 对每个被改名的属性，核对新旧属性的基础值、取值范围，以及原版代码里它在哪里起作用；乘法运算的修饰尤其要核对基础值。没有等价物时，在目标平台注册同语义的属性，在原 Forge 补丁点用 mixin 应用。用实体实测数值：冻结 tick（`/tick freeze`）后直接调用移动方法，比较不同属性值下的位移。

## 7. 第三方基线的数据可能对应一个未发布的上游提交

**边界：** 第三方移植声称的版本号（如 3.11.2）不一定是它数据的真实来源，它的内容可能取自上游开发分支上两个 release 之间的某个提交。用 release tag 做数据对照，会让大量“内容版本差”淹没真正的移植缺陷。

**核实与验证：** 把基线里不受格式迁移影响的数据文件（不含 `forge:`/`c:` 字符串的那部分）计算 git blob 哈希，在上游分支的每个提交上用 `git ls-tree` 统计精确匹配数（无需下载 blob，`--filter=tree:0` 的克隆即可），取匹配度最高的平台区作为数据基准，再做规范化比较。

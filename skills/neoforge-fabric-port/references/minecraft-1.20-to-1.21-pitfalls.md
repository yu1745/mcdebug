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

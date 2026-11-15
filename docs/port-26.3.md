# Minecraft 26.3 port

Based on native `1.21.1` commit `72a70c2`. Branch: `26.3`; worktree: `mcdebug-wt-263`.
The 1.21 worktree is unchanged. No new test files or test cases were added.

## Build

Use JDK 25 and the bundled Gradle 9.6.0 wrapper:

```sh
./gradlew build :cli:copyCliJar
scripts/fabric-smoke.sh
scripts/neoforge-smoke.sh
```

Fabric: non-remapping Loom 1.17.21, Loader 0.20.1, API 0.166.2+26.3.
NeoForge: 26.3.0.103, ModDevGradle 2.0.148. Kotlin remains 2.3.10.
Both mod jars bundle stdlib and contract classes. CLI/contract retain Java 17 bytecode;
both builds use JDK 25. The CLI embeds the matching Fabric and NeoForge jars.

## Required API changes

- Official names in dev and production. `ResourceLocation` becomes `Identifier` and
  resource keys expose `identifier()`. Fabric's generated gzip resource is now a class
  index for short names; there is no intermediary member translation or remap task.
  Qualified `reflect.resolve` returns `runtimeClass` with the official name; an identical
  `mojangClass` is omitted, matching NeoForge's existing behavior.
- Registry lookups use `lookupOrThrow` / `getValue`. State properties use `Property.Value`.
  Component patches expose `split()`; encoding still reports additions/removals and errors
  individually using the same RPC fields.
- BE loading and entity save/load use registry-aware `ValueInput` / `ValueOutput` via
  `NbtIo`. Numeric tags use value accessors; compound getters return optionals. Existing
  NBT tests were adapted to the new API, including the existing Unicode round trips.
- Crafting uses `CraftingRecipe`, recipe resource keys and `assemble(input)`. Remaining
  items still come from the matched crafting recipe.
- Fake players use `snapTo`, equipment-slot inventory ticks, double fall distances,
  permanent invulnerability and the new respawn data. Entity interactions receive a local
  hit position. The new InteractionResult records retain the old `action` labels
  `success_no_item_used` / `consume_partial` when their item context does not indicate item use.
  NeoForge now fires its interaction event inside `Player.interactOn`, so
  its explicit pre-hook returns PASS to avoid duplicate events.
  Its fake-player packet listener also returns false for payload channel probes, following
  NeoForge's own FakePlayer: menu data synchronization checks extended payload support before
  sending, and a fake connection has no Netty channel to inspect.
- `Minecraft.gui` owns Screen/overlay state. Input uses `KeyEvent`, `CharacterEvent` and
  `MouseButtonEvent`. `InputEvents` translates existing RPC GLFW codes, modifier masks and
  button numbers (0 left / 1 right / 2 middle) to SDL scancodes, keycodes and buttons.
  Unicode typing sends full codepoints instead of separate UTF-16 surrogates.
- Rendering is split between state extraction and GPU rendering. Demand/gui-only scheduling
  gates both phases while leaving natural ticks, tasks and window events intact. The
  wakeable limiter uses `LockSupport` instead of removed GLFW event calls.
- GUI observation hooks target `GuiGraphicsExtractor` and `GuiTextRenderState`, covering
  direct text, widget text, tiled sprites, items and tooltips. The Screen guard wraps the
  final extraction method shared by both loaders, replacing loader-specific draw targets.
  The virtual cursor is applied to the graphics extractor as well as Screen arguments.
- Screenshots use vanilla asynchronous GPU readback, supporting the render backend without
  OpenGL framebuffer calls. Frame geometry, widget boxes and draw logs are frozen before
  readback; bounded reservations remain held through readback/encoding/cancellation. PNG
  encoding uses NativeImage's channel writer, preserving one encode per output.
- The settle barrier still uses command suggestions. Packet thread dispatch now takes a
  `PacketProcessor`, so its mixin descriptor changes.
- NeoForge replaces legacy item/fluid/energy capabilities with transactional
  `ResourceHandler` / `EnergyHandler`. Item and fluid moves commit inside one transaction;
  simulations abort. `ItemAccess` tracks bucket replacement and item-hosted energy changes.
  Fluid units remain mB (Fabric droplets); `fluid.*` tank indexing retains the prior
  NeoForge behavior. Energy support flags now probe a simulated single-unit operation
  because the new API no longer exposes `canReceive/canExtract` capability flags.
  Both loaders cap item slot capacity by the stack limit (64 for an empty slot).
- `/tick sprint stop` now throws when no sprint is active. The existing smoke assertion
  checks its error text; other smoke changes only update target versions and renamed classes.

## Validation

- Full `./gradlew build :cli:copyCliJar`: PASS, including both self-contained jar gates.
- Existing root tests: 155 passed. Existing CLI tests: 141 total, 5 existing skips,
  zero failures/errors. No new test files or test cases.
- Both existing dedicated-server smoke scripts: PASS, each with 81/81 server methods.
- Existing `parity-diff.py`: 53/53 agree, zero unexpected differences. The documented
  Fabric-only short-name resolver remains an expected difference. Consumer clients used
  the same offline username for the status comparison; the task file was unchanged.
- Fabric dev client: create world, clear/type name including emoji, acknowledged settle,
  chest GUI, slot and coordinate clicks, hover tooltip, annotated/cropped screenshot,
  draw log, passive drag splitting, Escape, and normal save/stop all passed. Screenshot
  352×336 for a 176×168 GUI at scale 2; text/items/sprites recorded.
- Isolated consumers load only the jars extracted by the CLI from `run/mods`, with no
  mcdebug source sets or separate Kotlin language mod. Both loaders reached `world_ready`
  through managed sessions, opened a chest, changed server inventory by clicking, and
  produced real cropped/annotated screenshots. NeoForge hover records the actual tooltip;
  native right click splits five items into three carried and two left in the chest.
- OpenGL and Vulkan readback both verified under Xvfb/Mesa. Fabric was explicitly launched
  with `--graphicsBackend VULKAN`; the log confirmed Vulkan with llvmpipe. The 352×336 PNG
  and Gold Ingot tooltip were inspected. This does not assert cross-backend pixel equality.
- CLI also starts and reports its version on OpenJDK 17.
- Test sessions are stopped normally through the CLI; verification logs/screenshots are
  retained locally in `/tmp/mcdebug-263/`.

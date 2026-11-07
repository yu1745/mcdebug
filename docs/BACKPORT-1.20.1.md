# 1.20.1 Forge backport (spike result, 2026-11-06)

Goal: take the client plane (gui.*: screenshot/click/settle/hotswap) and the shared server plane from `1.21.1`
to Forge 1.20.1, so TiC 3.12.1 (1.20.1, Forge) can be screenshotted as a behavior baseline for the Fabric 1.21.1 port.

## Findings
- `main`'s Forge jar was never reobfuscated (`jar` lacked `finalizedBy 'reobfJar'`): the published jar crashes the
  server with `NoSuchMethodError getAllLevels()`. Fixed in `forge/build.gradle` (this branch).
- `main`'s Forge module has its own 6.3k-line copy of the server ops; `1.21.1` replaced them by a shared tree
  behind `LoaderApi`. `main`'s Forge module no longer compiles against the shared rpc files (they use `McDebug`).
- Spike (`forge-spike/`, shared `src/main`+`src/client` synced into a Forge 1.20.1 build, untracked):
  128 compile errors on first pass: ~73 server plane in 14 files, ~55 client plane in 7 files, plus the 3 client mixins.

## Error classes (all 1.21 → 1.20.1 API differences)
- data components / CustomData / DataComponentPatch → NBT tags (`saveWithoutMetadata(registries)`, `isSameItemSameComponents`)
- crafting: `CraftingInput`, `RecipeHolder` (1.21) vs `CraftingContainer`, `Recipe` (1.20)
- `tickRateManager`, `ClientInformation`, `CommonListenerCookie`, FakePlayer constructors
- client: `DeltaTracker` (partial tick float), `AbstractContainerScreen` accessors (mixin), `GenericMessageScreen`, `NativeImage` accessor

## Plan
1. Forge loader module patterned on `neoforge/` (`LoaderApi`, `ClientLoaderApi`, storage via Forge capabilities, entrypoints).
2. Version-shim the server-plane error classes in place on the `1.20.1` branch (branch is not meant to merge back).
3. Client plane + mixins (`MixinTargets` for Forge 1.20.1), then `gui.screenshot` smoke on the title screen.
4. Reobfuscated release jar; verify on the Forge oracle server + a Forge client with TiC 3.12.1.

## Status (2026-11-06, later)
Done on branch `1.20.1` (uncommitted): Forge loader module (`forge/src/main/kotlin/com/mcdebug/loader/forge/`), shared
server plane + client plane edited in place for 1.20.1 (128 → 0 errors), per-loader mixins in `forge/src/main/java`
(`blitSprite` hooks dropped, `render(FJZ)V`, `ForgeHooksClient.drawScreen`), MixinGradle refmap, MixinExtras via jarJar,
`jar` reobfuscated. Verified: dedicated Forge server (mdb `status`), dev client with Mantle + TiC 3.12.1 (`-Poracle`),
`gui.status` + `gui.screenshot` on the title screen (AMD radeonsi, DISPLAY=:0).
Known gaps: no tick sprint/TickRateManager on 1.20.1; `reflect.*` member names are Mojang names only (dev);
`gui.disconnect` uses `clearLevel`; GuiGraphics draw-log lacks sprite hooks; `mdb session start` cannot launch FG6 runClient
(FG adds its own `--gameDir .`, ModLauncher rejects the duplicate) — launch runClient by hand with MCDEBUG_* env vars:
`DISPLAY=:0 MCDEBUG_CLIENT_SOCKET=<gameDir>/mcdebug/clientSocket ./gradlew runClient -Poracle -PgameDir=<gameDir>`.
Next: create/join a world, drive TiC screens (part builder, tool station, smeltery) and capture the baseline PNG set.

# mcdebug on Forge-family 1.21.1 — feasibility research

Date of research: **2026-10-30**. All version/date claims verified against primary sources on that date
(GitHub API/releases/source, Modrinth API, the Sinytra Probe compatibility service, Gradle Plugin Portal,
`files.minecraftforge.net`). Everything marked **[unverified]** was *not* confirmed.

Method note: except where a URL is a doc page, I queried the live APIs directly (GitHub REST, Modrinth v2,
`https://probe.sinytra.org/api/v1/probe`) and quoted the response. mcdebug's own jar
(`build/libs/mcdebug-0.7.0.jar`, built 2026-10-30 with Loom 1.15) was unpacked and inspected.

---

## A) SINYTRA CONNECTOR

### A1. Versions, maintenance status, and the supported-module list

**Is 1.21.1 supported, and is Connector still maintained as of 2026?** Yes to both, but 1.21.1 is now the
*legacy* line, not the primary one — the project website still claims otherwise and that page is stale.

| Fact | Value | Source |
|---|---|---|
| Repo | `Sinytra/Connector`, not archived | [github.com/Sinytra/Connector](https://github.com/Sinytra/Connector) |
| Repo last push | **2026-10-30T10:11:45Z** (same day as this research) | GitHub REST `repos/Sinytra/Connector` |
| Default branch | **`26.2.x`** (1.21.1 lives on branch `1.21.x`) | as above + `branches` |
| Branches | `1.20.1`, `1.21.x`, `26.1.x`, `26.2.x`, `dev`, `master` | `repos/Sinytra/Connector/branches` |
| Latest 1.21.1 release | **`2.0.0-beta.17+1.21.1`**, published **2026-08-15** | [release page](https://github.com/Sinytra/Connector/releases/tag/2.0.0-beta.17%2B1.21.1) |
| Last commit on `1.21.x` | **2026-09-26** (`5a2f668a`, "Add overrides and mixin config filter") | `commits?sha=1.21.x` |
| Latest overall | `3.1.0-beta.4+26.2` and `3.0.0-beta.7+26.1.2`, both 2026-10-30 | `gh release list` |
| Latest 1.20.1 (LTS) | `1.0.0-beta.49+1.20.1`, 2026-07-04 | `gh release list` |
| Downloads (Modrinth) | 27,710,970 | [modrinth.com/mod/connector](https://modrinth.com/mod/connector) |

⚠️ **Contradiction to flag.** The official FAQ at <https://connector.sinytra.org/faq> says
*"✅ 1.21.1 is our primary supported version. This is the one that will receive new fixes and compatibility
improvements. ⚠️ 1.20.1 is our long-term-support version"* — but that page is footed "© 2025 Sinytra" and the
GitHub record contradicts it: the default branch is `26.2.x`, `1.21.x` has had no commit since 2026-09-26,
while `26.2.x` is committed to daily. Treat the FAQ text as stale; **1.21.1 is in maintenance, receiving
occasional fixes, not active compatibility work.** Connector is *not* abandoned, but 1.21.1 compatibility
improvements are no longer the priority the FAQ claims.

**Forgified Fabric API (FFAPI) — the Fabric API replacement.**

| Fact | Value | Source |
|---|---|---|
| Latest 1.21.1 release | **`0.116.15+2.3.6+1.21.1`**, published **2026-10-15** | [release page](https://github.com/Sinytra/ForgifiedFabricAPI/releases/tag/0.116.15%2B2.3.6%2B1.21.1) |
| Repo last push | 2026-10-23 | `repos/Sinytra/ForgifiedFabricAPI` |
| 1.21.1 branch | `1.21.1` (exists) | `branches` |
| Main FML mod id | **`fabric_api`** — so a Fabric mod declaring `"fabric-api": "*"` is satisfied | [Connector developer docs](https://moddedmc.wiki/en/project/connector/latest/docs/developers) |

Note the FFAPI version string encodes the **upstream Fabric API version it tracks**: `0.116.15` mirrors Fabric
API `0.116.x` — i.e. the same minor line mcdebug builds against (`gradle.properties`:
`fabric_api_version=0.116.17+1.21.1`).

**Which modules are supported — measured, not guessed.** I unpacked both jars and diffed the nested module jars:

- Upstream `fabric-api-0.116.17+1.21.1.jar` (from [Modrinth](https://modrinth.com/mod/fabric-api)) — **49** modules.
- `forgified-fabric-api-0.116.15+2.3.6+1.21.1.jar` — **44** modules.

**Present upstream but NOT shipped by FFAPI 1.21.1 (6):**

| Module | Status upstream |
|---|---|
| `fabric-crash-report-info-v1` | current, non-deprecated → **real gap** |
| `fabric-dimensions-v1` | current, non-deprecated → **real gap** |
| `fabric-command-api-v1` | in `deprecated/` upstream → cosmetic |
| `fabric-commands-v0` | in `deprecated/` upstream → cosmetic |
| `fabric-keybindings-v0` | in `deprecated/` upstream → cosmetic |
| `fabric-rendering-v0` | in `deprecated/` upstream → cosmetic |

**Present in FFAPI but not in the upstream aggregator jar (1):** `fabric-gametest-api-v1-2.0.5`
(upstream keeps gametest out of the release aggregator). There is no upstream doc page listing
"supported/unsupported modules"; the module list is only derivable from the jar or the repo tree, which is
what I did. **Uncertainty:** FFAPI "has checks in place to ensure full api compatibility with Fabric API" for
`net.fabricmc.*.api` packages but explicitly disclaims internal/implementation APIs
([FFAPI README](https://github.com/Sinytra/ForgifiedFabricAPI/blob/1.21.1/README.md)) — so *API-level* parity
is claimed, *behavioural* parity is on a best-effort basis.

**mcdebug impact: none of the 6 missing modules are used by mcdebug.** mcdebug uses
`fabric-api-base`, `fabric-lifecycle-events-v1`, `fabric-api-lookup-api-v1` and
`fabric-transfer-api-v1` — all four present.

### A2. Mixins, Kotlin, loader API, Transfer API

**(a) Mixins into client rendering classes.** Connector does support refmapless mixins (the Loom-1.5+
mode that remaps mixin classes in place) — the tracking issue *"Connector is unable to handle refmapless
mixins"* ([#795](https://github.com/Sinytra/Connector/issues/795)) was **closed as completed on 2024-04-06**,
and Sinytra's Adapter issue *"Support no-refmap method patches and ModifyConstant injectors"*
([Adapter#12](https://github.com/Sinytra/Adapter/issues/12)) closed 2026-07-15. Client-rendering Fabric mods
are routinely tested and pass; from the official Probe service for **1.21.1** on
`2.0.0-beta.17+1.21.1` / NeoForge `21.1.264`:

| Mod | Result |
|---|---|
| Indium (Fabric-only Sodium-compat renderer) | ✅ passing, `1.0.35+mc1.21`, tested 2026-10-22 |
| Krypton (Fabric-only networking) | ✅ passing, `0.2.8`, tested 2026-10-22 |
| Fabric Language Kotlin | ✅ passing, `1.14.2+kotlin.2.4.21`, tested 2026-10-22 |
| Mod Menu | ❌ failing, `11.0.5`, tested 2026-10-22 |
| Carpet | ❌ failing, `1.4.147`, tested 2026-10-24 |

(Source: `POST https://probe.sinytra.org/api/v1/probe`, `{"platform":"modrinth","slug":…,"game_version":"1.21.1"}`;
the human-readable listing is at <https://connector.sinytra.org/compatibility>.) Note the Probe docs' own
caveat: it *"runs Connector's code transformations on the mod jar file and validates the results"* **outside**
the game — *"it is possible that despite the jar being successfully patched, there are still remaining issues
that will only trigger in game"* ([compatibility page](https://connector.sinytra.org/compatibility)).

**But** the specific shape mcdebug uses is the shape that keeps breaking. mcdebug's client plane is 10 mixin
classes, **refmapless**, with **bare intermediary method names and no descriptors** — verified directly in
`build/libs/mcdebug-0.7.0.jar`:

```
RuntimeVisibleAnnotations:
  org.spongepowered.asm.mixin.injection.Inject(
    method=["method_25295"]
    at=[@org.spongepowered.asm.mixin.injection.At(value="HEAD")])
```
and the constant pool is pure intermediary: `Lnet/minecraft/class_332;` (GuiGraphics),
`net/minecraft/class_4587` (PoseStack), `class_2960` (ResourceLocation), `method_51448`, …

Two open/newly-closed issues hit exactly this:

- **[FFAPI #260 — "Incompatibility with Fabric mods built with Fabric Loom 1.14+."](https://github.com/Sinytra/ForgifiedFabricAPI/issues/260)** — **still OPEN**, created 2026-02-26, last updated 2026-08-03; labelled `bug, 1.21.x`; a commenter confirms it reproduces on 1.21.1. The only known remedy in-thread is a third-party patch mod ("Mishang Urban Construction Sinytra Connector Fix") — i.e. no upstream fix.
- **[FFAPI #266 — "Crash on startup when using mod built with Loom 1.15 or higher"](https://github.com/Sinytra/ForgifiedFabricAPI/issues/266)** — closed **completed** 2026-06-21. Root cause, per Connector's maintainer Su5eD: *"I checked the difference in one of the mixins between 1.14 and 1.15 jars and the mixin was missing its method descriptor in the 1.15 version. I believe this is related to a bug that was fixed by [FabricMC/tiny-remapper#164]. I tried using loom 1.17 (1.16 still had the issue) and the descriptors are included again now."* The reporter noted both failing mixins were **`@WrapOperation`** — which is precisely the annotation family mcdebug uses exclusively (AGENTS.md §14: "一律用 `@WrapOperation`/`@Inject`").
- **[Connector #2134 — "[Dev Issue] Incorrect mappings of the method 'tick'"](https://github.com/Sinytra/Connector/issues/2134)** — **OPEN** (created 2026-03-02). A mod author's own diagnosis: when Connector converts a *bare intermediary method name* to the Mojang mapping, overloaded targets are ambiguous — *"in the Mojang official mapping, there are two methods named 'pick' in the class GameRenderer… the method 'method_56153' should be converted to a specific method signature … instead of 'pick' to avoid ambiguity."* mcdebug's `@Inject(method="method_25295")` on `GuiGraphics` (whose `blit` family is heavily overloaded) is exactly this ambiguity class.
- **[Connector #2295 — "Bundled FART 1.0.14 has a race condition producing randomly broken transformation output (unmapped names -> AbstractMethodError)"](https://github.com/Sinytra/Connector/issues/2295)** — **OPEN**, created 2026-08-12. Nondeterministic transformation: *"measured: 1 broken output in 8 samples"*, manifesting as `AbstractMethodError` for lambdas implementing MC interfaces. **This issue was filed by `yu1745` (mcdebug's own account) against IC2 Refabricate 0.5 + `fabric-language-kotlin` under Connector**, and includes a proposed upstream fix. This is the strongest single signal: the exact consumer stack (IC2 Fabric mod + FLK) has already produced an unreproducible-by-design transformation failure on Connector.

⇒ **Verdict for (a): supported in principle, but mcdebug's specific mixin shape is the known-bad shape, and two of the three relevant issues are open.** Fix path before trying: bump Loom to ≥ 1.17 (per #266) and add method descriptors to every mixin `method=` value (kills the #2134 ambiguity class). Both are cheap and independently good hygiene.

**(b) `fabric-language-kotlin`.** Supported and actively tested: Probe reports FLK `1.14.2+kotlin.2.4.21`
**passing** on Connector `2.0.0-beta.17+1.21.1` with NeoForge `21.1.264`, tested 2026-10-22
(`probe.sinytra.org/api/v1/probe`). FLK itself is maintained, last release `1.14.2+kotlin.2.4.21` on
2026-10-09 ([releases](https://github.com/FabricMC/fabric-language-kotlin/releases)). mcdebug's
`fabric.mod.json` uses `{"adapter": "kotlin", …}` for both entrypoints, so FLK must be installed on the
NeoForge side too.

⚠️ **Residual risk — Kotlin-for-Forge coexistence.** If the user's NeoForge pack already contains
*Kotlin for Forge* (very common), adding FLK has historically crashed at boot:
[Connector #1654](https://github.com/Sinytra/Connector/issues/1654) ("Kotlin Fabric together with Kotlin
NeoForge crashes on startup", 1.21.1, closed 2025-02-25) and
[#1671](https://github.com/Sinytra/Connector/issues/1671) (closed 2026-02-23). Both are closed, and the
in-thread workaround was pinning FLK to 1.13.0. **Probe tests FLK in isolation — it does not test FLK
alongside Kotlin for Forge.** **[unverified]** whether current FLK 1.14.x coexists cleanly with current
Kotlin for Forge.

**(c) Fabric Loader API used directly.** Connector ships a Fabric Loader fork,
[`Sinytra/ForgifiedFabricLoader`](https://github.com/Sinytra/ForgifiedFabricLoader) — *"An implementation of
Fabric Loader api on top of NeoForged's Fancy Mod Loader"* — bundled inside the FFAPI jar. Verified: FFAPI
`0.116.15+2.3.6+1.21.1` contains `META-INF/jars/forgified-fabric-loader-2.5.68+0.18.4+1.21.1-full.jar`, and
Connector's own `META-INF/MANIFEST.MF` declares `Fabric-Loader-Version: 0.19.3`.

Which API surface exists on the `1.21.x` branch of that fork (from its git tree):

- ✅ `net/fabricmc/loader/api/FabricLoader.java`, `api/MappingResolver.java`
- ✅ `net/fabricmc/loader/impl/FabricLoaderImpl.java`, `impl/MappingResolverImpl.java`
- ✅ `net/fabricmc/loader/impl/launch/FabricLauncherBase.java` — and `FabricLauncherFML.java`

So the **classes mcdebug touches exist**, and because mcdebug declares `"fabricloader": ">=0.14.21"`
(a low bound) there is no loader-version rejection. mcdebug's actual loader-API surface is small — four uses of
`FabricLoader` plus one of `FabricLauncherBase`:

| File | Use |
|---|---|
| `src/main/kotlin/com/mcdebug/util/Mappings.kt` | `FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace` |
| `src/main/kotlin/com/mcdebug/McDebugMod.kt` | `FabricLoader` (game dir / config) |
| `src/client/.../ClientInfo.kt`, `McDebugClientMod.kt` | `FabricLoader` |
| `src/client/.../Hotswap.kt:182` | `FabricLauncherBase.getLauncher()` |

⚠️ **The `FabricLauncherBase` use is the weak point.** That is an *impl/internal* class; under Connector it is
`FabricLauncherFML`, not Knot, so `getLauncher().getClassByteArray(name, propagateError)` has FML semantics.
mcdebug already guards this (`Hotswap.transformedBytes` only trusts the launcher's pre-mixin pipeline when the
returned bytes `contentEquals(fileBytes)`, i.e. the dev-classpath case, and falls back to raw file bytes
otherwise; the `MixinEnvironment…activeTransformer` cast is null-guarded). Expected outcome: **`gui.hotswap`
degrades or reports failure under Connector rather than crashing the client** — but this is a prediction, not
a verified result. **[unverified]** — must be tested empirically.

**(d) Fabric Transfer API — NOT a gap. This is the single most useful finding.** FFAPI 1.21.1 ships a full
port, verified by unpacking the jar:

```
META-INF/jars/fabric-transfer-api-v1-5.4.4+6244b11119.jar
```

and the module source tree on branch `1.21.1` contains every API class mcdebug would use —
[`storage/Storage.java`](https://github.com/Sinytra/ForgifiedFabricAPI/tree/1.21.1/fabric-transfer-api-v1/src/main/java/net/fabricmc/fabric/api/transfer/v1/storage)
(`Storage`, `SlottedStorage`, `StorageUtil`, `StorageView`, `TransferVariant`, `StoragePreconditions`),
[`item/ItemStorage.java`](https://github.com/Sinytra/ForgifiedFabricAPI/blob/1.21.1/fabric-transfer-api-v1/src/main/java/net/fabricmc/fabric/api/transfer/v1/item/ItemStorage.java),
[`fluid/FluidStorage.java`](https://github.com/Sinytra/ForgifiedFabricAPI/blob/1.21.1/fabric-transfer-api-v1/src/main/java/net/fabricmc/fabric/api/transfer/v1/fluid/FluidStorage.java),
`transaction/Transaction.java`, `transaction/TransactionContext.java`,
`transaction/base/SnapshotParticipant.java`, `context/ContainerItemContext.java`,
`base/SingleVariantStorage.java`, `base/CombinedStorage.java`, plus the client-side
`client/fluid/FluidVariantRendering.java`. There is also a dedicated NeoForge bridge package
`org.sinytra.fabric.transfer_api.compat` (`NeoItemStorage`, `NeoFluidStorage`, `ItemStorageItemHandler`,
`FluidStorageFluidHandler`, `NeoCompatUtil`) registered through
[`TransferApiNeoCompat.java`](https://github.com/Sinytra/ForgifiedFabricAPI/blob/1.21.1/fabric-transfer-api-v1/src/main/java/org/sinytra/fabric/transfer_api/TransferApiNeoCompat.java),
which wires `ItemStorage.SIDED` / `FluidStorage.SIDED` into NeoForge's
`Capabilities.ItemHandler.BLOCK` / `Capabilities.FluidHandler.BLOCK` in both directions (with a
`COMPUTING_CAPABILITY_LOCK` ThreadLocal to break the recursion between the two lookup systems).

FFAPI's README states the design intent plainly: *"Where possible, Fabric APIs such as `FabricItem`,
`ItemStorage` and `FluidStorage` are bridged to NeoForge's counterparts."*

**Differences vs upstream (measured by tree diff), worth knowing but not blocking for mcdebug:** FFAPI's
transfer module lacks five of upstream's vanilla-integration mixins —
`BucketItemAccessor`, `ChiseledBookshelfBlockEntityMixin`, `CrafterBlockMixin`, `DropperBlockMixin`,
`HopperBlockEntityMixin`. Those vanilla paths are instead served by NeoForge's own capability patches, which
is likely intentional (FFAPI's stated design goal: *"keep modifications to minecraft's code at minimum"*).

**Known open transfer-API bugs (all about third-party mod interop, not about the API being absent/unusable):**
[FFAPI #215](https://github.com/Sinytra/ForgifiedFabricAPI/issues/215) (1.21.1, crash auto-outputting from
Iron Furnaces/Functional Storage into Oritech machines, open since 2025-04-08) and
[FFAPI #276](https://github.com/Sinytra/ForgifiedFabricAPI/issues/276) (1.21.1, *"Presence of Forgified Fabric
API breaks item transport on Every Compat dynamic chests"*, open since 2026-06-29 — a case where FFAPI's
capability registration *silently shadows* another mod's capability provider).

### A3. Does Connector work on plain Forge 1.21.1? **No.**

Plain Forge 1.21.1 exists — Forge `52.1.20` is both `latest` and `recommended` for 1.21.1
(`https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json`). But **no Connector build
targets it**:

- Connector's own `META-INF/neoforge.mods.toml` on branch `1.21.x` declares:
  ```toml
  [[dependencies.connector]]
      modId="neoforge"
      type="required"
      versionRange="[21.1.97,)"
  [[dependencies.connector]]
      modId="minecraft"
      type="required"
      versionRange="[1.21,1.22)"
  ```
  → a hard NeoForge dependency. Verified by downloading the `-full` jar from the release.
- Modrinth's loader tags: the Connector project lists loaders `["forge","neoforge"]`, but **every 1.21.1
  version is tagged `neoforge` only**; all `forge`-tagged versions are the 1.20.1 `1.0.0-beta.x` line
  (`fabric-version` API, `GET /v2/project/connector/version`). The last Forge-tagged release is
  `1.0.0-beta.49+1.20.1` (2026-07-04).
- Repo description and README: *"A compatibility layer that allows running Fabric mods on **NeoForge**"*.

**⇒ If a user runs plain Forge 1.21.1, Connector is not an option at all.**

### A4. Practical limitations and failure modes

**Install footprint.** 1.21.1 requires exactly two extra jars: **Connector + FFAPI**. From Connector's
`1.21.x` README: *"1. Install **NeoForge**… 2. Install **Connector**… 3. Download the **Forgified Fabric API**.
It is meant to be a direct *replacement* for the Fabric API and is **not compatible with it**. We'll try our
best to avoid loading the Fabric API if it's installed automatically (e.g. by your modpack manager), but if
you have the option to avoid installing it, please do so."*
⚠️ **The docs site (26.1.x) differs from the 1.21.x README**: <https://moddedmc.wiki/en/project/connector/latest/docs/introduction>
lists a **third** dependency, [Launchpad](https://github.com/Sinytra/Launchpad) — but Launchpad has **no 1.21.1
build** (Modrinth `game_versions` = `26.1, 26.1.1, 26.1.2, 26.2` only; repo branches `26.1.x`, `26.2.x`,
`master`). So for 1.21.1 the answer is **Connector + FFAPI**, no Launchpad. The `Launchpad` recommendation
only applies to the 26.x lines, where Connector no longer loads Fabric mods itself.

Also note: Connector's jar is only distributed as **one** asset, `connector-2.0.0-beta.17+1.21.1-full.jar`
(4.57 MB, containing `mappings/mappings.tiny` + `mappings.tsrg`). Same single file on Modrinth. So there is no
"thin jar downloads mappings at runtime" variant to worry about on 1.21.1.

**Does it break with mods that mixin vanilla classes also touched by Forge patches?** Yes — this is the
canonical failure mode. The FAQ: *"some functionality may work slightly different than expected. This is not
caused by Connector's presence itself, but rather happens due to specific interactions between Fabric mods,
Mixins, patched NeoForge code and other mods"* (<https://connector.sinytra.org/faq>). Concretely documented in
the open issue list:

- [#2151](https://github.com/Sinytra/Connector/issues/2151) mixin injection failure in `ItemStack` (1.21.1)
- [#2169](https://github.com/Sinytra/Connector/issues/2169) *"BlockColors being rewritten in forge to use its own registries breaks fabric mods relying on it"*
- [#2166]/[#2313](https://github.com/Sinytra/Connector/issues/2313) *"Fabric mod's `onInitializeClient()` timing inside `Minecraft.<init>` risks racing NeoForge's own `EntityRenderersEvent.RegisterLayerDefinitions` merge, causing 'No model for layer' crashes for unrelated native mods"* — **open**, and a commenter reproduced the same signature on Forge 1.20.1; explicitly **timing-dependent** (*"two boots crashed with byte-identical missing-layer lists, while launches in between completed normally"*). This is the pattern to expect for a mod that does substantial work in a client entrypoint.
- [#2235]/[#2295](https://github.com/Sinytra/Connector/issues/2295) nondeterministic FART transformation (see A2a)
- [#2206](https://github.com/Sinytra/Connector/issues/2206) Fabric-registered biomes missing from NeoForge's `DynamicRegistryManager`
- [#2300](https://github.com/Sinytra/Connector/issues/2300) Jankson-based Fabric mods and owo-lib

**Documented, user-visible failure mode: the Mixin Safeguard.** `config/connector.json` ships
`"enableMixinSafeguard": true`, documented as: *"If enabled, halts loading and shows a warning screen when an
installed mod contains failing Mixins that Connector was unable to fix, preventing a hard crash. This warning
will only be shown once, after mod transformation, and is enabled by default."*
(<https://github.com/Sinytra/Connector/blob/1.21.x/docs/configuration.mdx>). So a mod whose mixin set Connector
can't patch is **blocked loudly at startup** — not silently broken. That is the realistic outcome for
mcdebug's client plane if the intermediary→Mojang conversion of its refmapless `@Inject`/`@WrapOperation`
targets fails.

Other documented limits: OptiFine incompatible; **custom server APIs (Mohist, Magma, Arclight) and mobile
launchers (Pojav, FoldCraft) and pirated launchers are not supported** (FAQ).

**Is a dedicated server supported and common?** Yes — explicitly. Connector's source contains
[`ServerMainMixin`](https://github.com/Sinytra/Connector/blob/1.21.x/src/mod/java/org/sinytra/connector/mod/mixin/boot/ServerMainMixin.java)
which injects into `net.minecraft.server.Main.main` at the `ServerModLoader.load()` call site to run
`ConnectorLoader.load()`, i.e. the dedicated-server path is a first-class code path, not an accident of the
client path. And `neoforge.mods.toml` sets `displayTest = 'IGNORE_ALL_VERSION'` with the comment *"Connector
is optional for both sides"* — so a client without Connector can join a Connector server and vice versa;
server-side-only Fabric mods need Connector only on the server. **The FAQ's server guidance is negative only
about hybrid server APIs, which implies stock NeoForge dedicated servers are fine.**

For mcdebug this matters: the **server plane** (`world.*` … `store.*`) only needs NeoForge + Connector + FFAPI
on the dedicated server, which is the lowest-risk half of the experiment. The **client plane** (`gui.*`) needs
the same stack on the *client*, and that is where every open client-side bug above lives. **Recommendation:
test the server plane first; treat the client plane as a separate, higher-risk milestone.**

### A5. Would mcdebug actually work — unix socket, arbitrary reflection, and the namespace question

**Unix domain socket.** Nothing in Connector's transformation touches `java.nio.channels`/`ServerSocketChannel`
AF_UNIX usage or `File`/`Path` handling. Under NeoForge the JVM, the process, and the filesystem are the same
JVM/process/filesystem as under Fabric; only the *mod loader* and *MC class names* differ. **Expected: works
unchanged.** Caveat: mcdebug's config resolution uses "gameDir" — under NeoForge that is the same working
directory concept, so `<gameDir>/mcdebug/socket` should land in the same place. **[unverified]** empirically,
but there is no mechanism by which it would fail.

**Arbitrary reflection by name — this is the interesting part, and the answer is favourable.**

*How Connector handles the namespace.* Verified from source, not from prose:

1. Connector bundles a Tiny v1 mapping file with three namespaces. Unpacked from the jar, header is:
   ```
   v1   official   intermediary   mojang
   CLASS  eza  net/minecraft/class_3673  com/mojang/blaze3d/Blaze3D
   ```
   (`mappings/mappings.tiny` and a parallel `mappings.tsrg`, both inside `connector-2.0.0-beta.17+1.21.1-full.jar`.)
2. It remaps each Fabric mod **from `intermediary` to the NeoForge runtime namespace** at load time:
   [`JarTransformer.SOURCE_NAMESPACE = "intermediary"`](https://github.com/Sinytra/Connector/blob/1.21.x/transformer/src/main/java/org/sinytra/connector/transformer/jar/JarTransformer.java),
   and [`IntermediateMapping`](https://github.com/Sinytra/Connector/blob/1.21.x/transformer/src/main/java/org/sinytra/connector/transformer/jar/IntermediateMapping.java)
   deliberately filters to only the intermediary-shaped names
   (`net/minecraft/class_`, `field_`, `method_`, `comp_`) *"as those don't need to be remapped and will only
   cause issues with our barebones find/replace remapper"*.
3. **The runtime namespace is `mojang`, not `intermediary`.** [`ForgifiedFabricLoader`'s `LoaderUtil`](https://github.com/Sinytra/ForgifiedFabricLoader/blob/1.21.x/src/main/java/net/fabricmc/loader/impl/util/LoaderUtil.java) declares
   `public static final String RUNTIME_MAPPING = "mojang";` and
   [`MappingConfiguration.getTargetNamespace()`](https://github.com/Sinytra/ForgifiedFabricLoader/blob/1.21.x/src/main/java/net/fabricmc/loader/impl/launch/MappingConfiguration.java)
   returns it. So **the user's premise is inverted**: under Connector a Fabric mod does *not* "see intermediary
   names at load time". Its bytecode is rewritten to **Mojang** names, and the game itself is Mojang-named.

   ⚠️ **[unverified]**: I verified the constant and the config class, but not that
   `FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace` returns `"mojang"` at runtime in a live
   Connector 1.21.1 instance. That's a five-minute empirical check and it is *the* gating question for the
   whole Connector path for mcdebug.

4. Connector **does** rewrite reflection, but only in a very narrow, statically-analysable form.
   [`ReflectionRenamingTransformer`](https://github.com/Sinytra/Connector/blob/1.21.x/transformer/src/main/java/org/sinytra/connector/transformer/patch/ReflectionRenamingTransformer.java)
   uses an ASM `SourceInterpreter` and only fires when it sees a method call whose first three arguments are
   **single `LDC` string constants** (owner, name, descriptor) — i.e. literal
   `Class.getDeclaredMethod("net/minecraft/class_310", "method_1551", "()V")`-style calls. It then rewrites
   those `cst` values in place. Anything computed at runtime, built by concatenation, or read from a bundled
   resource is invisible to it.

**What this means for mcdebug — three conclusions:**

1. **mcdebug is already namespace-direction-aware and would pick the right direction under Connector.**
   `Mappings.kt` does:
   ```kotlin
   private val intermediaryRuntime: Boolean by lazy {
       runCatching { FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace == "intermediary" }
           .getOrDefault(true)
   }
   ```
   Under Connector this yields `false` → mcdebug resolves names as **Mojang at runtime**, which is correct,
   because Connector remapped mcdebug's own bytecode to Mojang names and the live MC classes are Mojang-named.
   Externally-supplied intermediary names still work, because mcdebug translates intermediary → Mojang from its
   own bundled table. **The direction logic is correct for Connector as written.**

2. **mcdebug's runtime-computed reflection strings are *not* rewritten by Connector — and that is fine, because
   they don't need to be.** `ReflectCore.kt:119-121` is `val runtime = Mappings.runtimeClassName(name); Class.forName(runtime)` —
   the owner string is a computed local, not an `LDC` constant at the call site, so
   `ReflectionRenamingTransformer` will not (and cannot) touch it. mcdebug reads its mapping table from
   `/mappings/reflect.txt.gz` at runtime (generated at build time from Loom's 1.21.1 intermediary↔Mojang
   mappings), which is namespace-compatible with Connector's bundled `mappings.tiny`
   (`official`/`intermediary`/`mojang` for the same MC version). A mod that *hardcoded* intermediary strings
   would depend on Connector's rewriting; mcdebug does not. **This is the strongest pro-Connector finding in
   this report.**

3. **The remaining reflection risk is name-ambiguity, and mcdebug is partly insulated.** Connector #2134 shows
   conversion can be ambiguous for **overloaded** members; mcdebug's own table is aware of this — `Mappings.kt`
   documents that *"a Mojang name shared by several intermediary members (overloads renamed differently) yields
   several candidates and the caller picks the one whose signature fits"*, and `ReflectCore` handles overload
   scoring. Since mcdebug performs its own disambiguation by descriptor at runtime, it is **less** exposed to
   #2134 than a mod relying on Connector's static rewriting. The exposure that remains is not in `reflect.*`
   but in the **mixin annotations** (see A2a): those *are* static `LDC` constants
   (`method=["method_25295"]`, no descriptor) sitting inside the mixin classes, i.e. squarely inside the code
   path Connector must rewrite and where #2134's ambiguity bites. Adding descriptors to those annotations
   removes the ambiguity entirely.

---

## B) KILT (KiltMC)

**Direction — this is the crucial, easily-missed point.** Kilt does the **inverse** of Connector. Repo
description: *"A Fabric mod that brings **(Neo)Forge mods into the Fabric ecosystem**"*
([KiltMC/Kilt](https://github.com/KiltMC/Kilt)); Modrinth: *"Allows the loading of (Neo)Forge mods in the
Fabric ecosystem. Very experimental."* ([modrinth.com/mod/kilt](https://modrinth.com/mod/kilt)). Kilt's own
README FAQ makes the contrast explicit: *"Connector's aim is to have Fabric mods on Forge, while Kilt aims to
have Forge mods on Fabric."*

**Consequence for mcdebug:** Kilt does **not** let a NeoForge/Forge user run mcdebug. It lets a **Fabric**
user run their Forge mods. The only way Kilt helps is if the user *migrates their server from NeoForge to
Fabric + Kilt* — a change to the user's setup, not to mcdebug.

**Is it maintained? Yes — surprisingly actively, contrary to its own stale README.**

| Fact | Value | Source |
|---|---|---|
| Latest release | **`v21.1.18`, 2026-10-28** (2 days before this research) | [releases](https://github.com/KiltMC/Kilt/releases) |
| Default branch | `version/1.21.1` | `repos/KiltMC/Kilt` |
| Last commit | **2026-10-28T23:15:03Z** | `commits` |
| Repo pushed_at | 2026-10-30T12:16:47Z | `repos/KiltMC/Kilt` |
| Branches | `version/1.20.1`, `version/1.21.1`, `version/26.1.x`, many PR branches | `branches` |
| Release cadence on 1.21.1 | 18 releases; 21.1.11→18 all in Aug–Oct 2026 | releases |
| Downloads / followers | 272,377 / 773 | Modrinth API |
| Modrinth `game_versions` | `1.20.1`, `1.21.1` **only** | Modrinth API |
| Loader | `fabric` only | Modrinth API |

**Version support.** Kilt `v21.1.x` tracks Minecraft **1.21.1** and NeoForge **21.1.x** — the v21.1.18 release
notes list *"Update to NeoForge 21.1.265."* It therefore targets the *same* NeoForge 1.21.1 line that Connector
does, which is why mcdebug's archived 1.20.1 line used a "Kilt container test environment" (AGENTS.md §12) and
why two Kilt-era diagnostic RPCs (`server.mantleInteract`, `server.forgeFluidCapability`) existed.

⚠️ Kilt's README is **stale and contradicts its release history**: it still says *"A NeoForge 1.21.1 build is
currently in progress, however that is going to take some time"*, while 1.21.1 builds have shipped 18 times
since. Judge Kilt by releases, not by that README.

**Is it a realistic option for 1.21.1?** It is technically real and current, but the project's own framing is a
hard warning: *"Kilt is currently highly unstable, may break worlds, may cause crashes, hence why it is
currently published as **Alpha** to Modrinth and **Beta** to CurseForge"*, plus *"do not report [bugs] to the
mod developers unless they also occur on Forge"* (README, `version/1.21.1` branch). For a debug/automation mod
whose whole value proposition is *trustworthy assertions about a machine's state*, standing on an
Alpha-quality translation layer that itself warns about world corruption is a poor trade — and it shifts the
risk onto the user's modpack rather than onto mcdebug. Two-person project, 270 open issues.

⚠️ **Uncertainty:** Kilt's `WHY.md`/docs site (<https://kilt.bluspring.xyz/>) may carry a more current support
matrix than the README; I did not read it. Also, exactly which NeoForge mods work is not systematically tested
the way Connector's Probe service tests Fabric mods — **Kilt has no equivalent public compatibility matrix**,
so risk assessment for a specific modpack would be anecdotal.

**One relevant technical datapoint:** Kilt remaps Forge mods *from SRG into Fabric's intermediary*
(*"Kilt first remaps them from the Forge SRG format into Fabric's Intermediary format, then applies some of
its own 'fixers'"* — README), and it maintains its own forks of NeoForge, FancyModLoader, ForgeAutoRenamingTool
and `srg2intermediary` for this ([KiltMC org repos](https://github.com/orgs/KiltMC/repositories)). The
v21.1.18 notes include *"Implement reflection fixers"* and *"Fix Sable: Ragdoll. Also probably a lot of mods
that rely on reflection."* — i.e. Kilt, like Connector, has to special-case reflection, and for the mirror-image
reason. Neither translation layer makes reflection free.

---

## C) ARCHITECTURY / MULTI-LOADER BUILD

### Current state for 1.21.1

| Component | Status for 1.21.1 | Source |
|---|---|---|
| **architectury-loom** | Latest release **1.17**, 2026-07-08; repo last push 2026-09-12; default branch `dev/1.17` | [releases](https://github.com/architectury/architectury-loom/releases) |
| **architectury-api** | **`13.0.11`**, 2026-07-23 — published for **`fabric` and `neoforge` ONLY** | Modrinth API `GET /v2/project/architectury-api/version` |
| Repo branch for 1.21.1 | **there is no `1.21.1` branch** — only `1.21` (last commit 2026-07-23, "Merge pull request #733 from st0x0ef/1.21") | `branches`, `commits?sha=1.21` |
| Cache of 413 versions | loader counts across all versions: `fabric` 215, `quilt` 130, `forge` 120, `neoforge` 78 — but **zero `forge` among the six versions whose `game_versions` include 1.21.1** | Modrinth API |

**⚠️ Plain Forge 1.21.1 is not an Architectury target.** Architectury API has no Forge build for 1.21.1
(only `13.0.6`, `13.0.8`, `13.0.11`, each ×2 for fabric+neoforge). And Architectury's own docs have dropped
Forge from the framing entirely — <https://docs.architectury.dev/api> now reads: *"Architectury API is a
multiplatform Minecraft mod API. It abstracts the differences between **Fabric and NeoForge** so you can write
your mod's logic once, in a shared common codebase, and ship it on both loaders."* Loom's docs similarly carry
"Using NeoForge" and "NeoForge Migration" but no current Forge guide (only "Using Legacy MCP (1.16.5 or
lower)") — <https://docs.architectury.dev/loom/introduction>.

**So Architectury for 1.21.1 = Fabric + NeoForge, exactly like Connector's target. If "Forge-family" includes
plain Forge 1.21.1, Architectury does not solve it either.** This is probably the most consequential finding
in section C.

**Architectury Loom caveats (its own docs, <https://docs.architectury.dev/loom/introduction>):**
*"Architectury Loom currently only supports IntelliJ IDEA and Visual Studio Code. Other IDEs are not tested
and are not supported."* / *"Architectury Loom is an experimental project."* / mapping options: Yarn, Official
Mojang Mappings, or MCP / Gradle 8.1+.

### Kotlin support in a common+loader setup

**There is no first-class Kotlin story in Architectury.** Evidence: a code search for `kotlin` across
`architectury/architectury-loom` returns **no matches**, and a search restricted to
`architectury/architectury-api path:docs` returns **no matches** (GitHub code-search API, 2026-10-30). Loom's
docs pages have no Kotlin section. Architectury Loom is "a fork of Juuxel's Loom fork … that is a fork of
Fabric Loom" ([README](https://github.com/architectury/architectury-loom)) — so Kotlin arrives via Fabric
Loom's inherited Kotlin support plus **you wiring up the loader-specific Kotlin runtimes yourself**
(`fabric-language-kotlin` on Fabric, `kotlin-for-forge`/NeoForge's Kotlin on the other side). The
`architectury-templates` repo that the docs point at via
<https://generate.architectury.dev/> was **last pushed 2023-11-12** — stale.

**Realistic extra cost for mcdebug specifically.** mcdebug's *loader-specific* surface is genuinely small:

- **Lifecycle hooks** — `ServerLifecycleEvents` / `ServerTickEvents` (Fabric API) → NeoForge `FMLLoader`/event bus equivalents. Small, mechanical.
- **Transfer/storage API** — `ItemStorage.SIDED` / `FluidStorage.SIDED` / `Storage` / `Transaction` → NeoForge `Capabilities.ItemHandler.BLOCK` / `FluidHandler.BLOCK` + `IItemHandler`/`IFluidHandler`, or a hand-written abstraction. Medium; note FFAPI's own bridge code (`NeoItemStorage`, `ItemStorageItemHandler`, `SlottedItemStorageItemHandler`, …) is a ready-made reference for exactly this mapping.
- **Loader API** — `FabricLoader` (game dir, mod metadata, `MappingResolver`) → NeoForge `FMLPaths`/`ModList`. Small, but `MappingResolver` has **no NeoForge equivalent** — under NeoForge the runtime *is* Mojang-named, so mcdebug's mapping layer reduces to "identity + a user-name table", which is a simplification, not a port.
- **`Mappings.kt` / `reflect.*`** — the intermediary half becomes dead weight on NeoForge; the Mojang→Mojang path is trivial. Small.
- **The large client-side mixin set — this is the real cost.** 10 mixin classes, all currently written against **intermediary** targets, refmapless, with bare method names. On NeoForge they must be re-pointed at Mojang names *and re-verified against NeoForge-patched vanilla*: Forge patches touch exactly the classes mcdebug instruments (`GuiGraphics`, `GameRenderer`, `Screen`, `RenderSystem`, `Minecraft`). And there is **no Transfer-API-style bridge for mixin targets** — no compatibility shim saves you here. Additionally mcdebug's AGENTS.md §14 documents that Loom 1.15 writes single-value `at` as an array, breaking MixinExtras in consumer projects, and that injection points *must* be validated in a real client because `defaultRequire: 1` turns an injection miss into a startup failure. On NeoForge + a multi-loader build the number of (loader × mixin-remap × patch-set) combinations to validate multiplies.
- **Argument that it's cheaper than it looks:** the client mixin set is *conceptually* loader-independent — it targets vanilla classes that both loaders patch. If the mixin count were 2–3 the port would be trivial; at 10, each with `defaultRequire: 1`, the verification cost dominates.

### Modern alternatives people actually use in 2026

- **Stonecutter** — *"Modern Gradle plugin for multi-version management"*, by KikuGie. Gradle Plugin Portal lists **`dev.kikugie.stonecutter` version `0.9.8`, created 31 August 2026**
  (<https://plugins.gradle.org/plugin/dev.kikugie.stonecutter>), sources at
  <https://github.com/kikugie/stonecutter>, docs <https://stonecutter.kikugie.dev/>.
  ⚠️ **Discrepancy, flagged:** the GitHub repo reports `pushed_at = 2025-12-25T16:00:27Z` with default branch
  `display`, and the newest commit I could reach on branch `0.9` is 2025-12-20 — which is inconsistent with a
  plugin version published 2026-08-31. I could not reconcile this (possible mirror/rename involvement: a repo
  `stonecutter-versioning/stonecutter` carries byte-identical metadata). Treat "0.9.8 / 2026-08-31" as
  authoritative for *availability* and the repo push date as **unreliable**.
  Stonecutter is a **multi-version** tool that is also used for **multi-loader**: MCPFabric below uses it for
  exactly that.
- **Modstitch** — *"A gradle plugin to unify official tooling for both Fabric and (Neo)Forge"*, repo
  <https://github.com/modstitch/modstitch> (last push 2026-04-01, 34★), docs
  <https://isxander.github.io/modstitch-docs/>. Docs site is thin (a single "Getting Started / What is
  Modstitch?" page as rendered 2026-10-30). Again **Fabric + (Neo)Forge** — no plain Forge.
- **Stonecutter multi-loader template** — <https://github.com/rotgruengelb/stonecutter-mod-template>
  ("Stonecutter Multi-Loader & Multi-Version Minecraft Mod Template", pushed 2026-10-20, 51★), described as
  targeting Fabric, NeoForge and Forge. This is the most direct starting point if a Forge target is truly needed.
- **`kikugie/modkit`** — *"Suite of Gradle plugins for building Minecraft mods"*, pushed 2026-09-15, 0★; too
  immature to rely on.
- **Manual `common` + `fabric` + `neoforge` Gradle multi-project** — what the closest prior art actually does
  (see D).
- ❌ **`EdwinMindcraft/forgified-fabric-loom`** — "Gradle build system, that works for forge as well",
  **last pushed 2021-03-28**, 0★. Dead. Do not use.

### ⚠️ "NeoForge's Fabric compatibility claims" — **[not found / unverified]**

I could **not** locate any official NeoForge statement, doc page, or release note claiming that NeoForge runs
Fabric mods natively. Searches of `docs.neoforged.net` and the web for such a claim returned only third-party
loader-comparison pages and unrelated GitHub issues. Everything I found indicates the opposite framing: a
Fabric mod is not a NeoForge mod, and the ecosystem's answer to "run Fabric mods on NeoForge" is a
**third-party** mod — Sinytra Connector, which describes itself as *"a translation/compatibility layer that
allows running Fabric mods on **NeoForge**"*, plus its companion FFAPI (a **port** of Fabric API to NeoForge)
and, for 26.x, Launchpad. If the user has a specific NeoForge statement in mind, it should be re-checked; I
found no primary source for it and I am explicitly not asserting one.

---

## D) PRIOR ART — cross-loader debug/automation RPC mods

**Short answer: yes, credible prior art exists, it is recent, it is 1.21.1, and at least two projects ship
both Fabric and NeoForge builds of a socket/HTTP JSON-RPC automation mod. None is a drop-in replacement for
mcdebug, but `mcpfabric` covers a large, overlapping feature set and is the closest thing to an alternative.**

### D1. `mcpfabric` — closest match ⭐

| Fact | Value |
|---|---|
| Repo | <https://github.com/denfry/mcpfabric> — *"Control and observe a running Minecraft: Java Edition game from AI agents over MCP (Fabric and NeoForge, 1.21.1+)"* |
| License | MIT |
| Created / last push | 2026-10-05 / 2026-10-26 |
| Stars | 14 |
| Modrinth | <https://modrinth.com/mod/mcpfabric> — 5,503 downloads, updated 2026-10-16 |
| **1.21.1 builds** | **both**: `mcpfabric-0.8.0+1.21.1.jar` (fabric) and `mcpfabric-neoforge-0.8.0+1.21.1.jar` (neoforge), both 2026-10-16 |
| Modrinth loaders / versions | `["fabric","neoforge"]`; `1.21.1`–`1.21.11`, `26.1`–`26.3` |
| **Build strategy** | **"A single source tree targets many Minecraft versions using [Stonecutter](https://stonecutter.kikugie.dev/). Each version below ships its own jar"** — this is direct evidence for the section-C toolchain question |

Architecture (from its README): a mod embedding a **local HTTP bridge** in Minecraft, plus a TypeScript MCP
server. `POST /rpc` (JSON-RPC) + `GET /events` (SSE), **bearer token, 127.0.0.1 only**; all game access goes
through the main-thread executor (`server.execute` / `Minecraft.execute`); separate common (`info`, `world`,
`entities`, `players`, `command`, `chat`, `events`) and client (`player`, `control`, `interact`, `inventory`,
`vision`, `navigation`, `session`, `ui`) tool groups. **Works on the client *and* dedicated servers.**

Feature overlap with mcdebug is substantial: `get_block`/`set_block`/`fill_blocks`, `query_entities`,
`run_command`, **`screenshot` (PNG)**, `get_screen`/`close_screen`, **`get_ui` (widgets of the open screen)**,
**`click_widget`, `set_text_field`** (behind an `enableUiControl` flag, off by default), `get_inventory`,
`drop_slot`/`swap_slots`, plus **`open_container`/`container_transfer`/`close_container`**.

**Where it is *not* a drop-in for mcdebug:**
- Transport is **HTTP on loopback**, not a unix socket + TCP; auth is a bearer token vs mcdebug's deliberate no-auth + firewall model (AGENTS.md §1).
- Writes go **through the command dispatcher** (*"writes (`setblock`/`summon`/`give`/`tp`/effects/weather/time) go through the command dispatcher with output capture"*) rather than direct block-entity NBT read/write. There is **no equivalent of mcdebug's `be.getNbt`/`be.setNbt`/`be.tick`**, no `snapshot.diff`, no `trace.*`, no predicate-DSL `wait.until`, no `reflect.*`, no `storage.*`/Transfer-API surface, no `gui.hotswap`/`reloadScreen`/golden-image compare.
- GUI control is widget-level (`click_widget`, `set_text_field`) with **no slot-index clicking, no hover/tooltip capture, no draw log, no annotated screenshots, no rendered-geometry reporting** — mcdebug's whole agent-iteration loop.
- **Trust/robustness caveats:** 14 stars, created 2026-10-05 (three weeks old at time of research), and its README states *"**No hidden telemetry:** the current **Fabric** release sends no usage analytics"* — an oddly scoped sentence that implies the NeoForge release may differ. **[unverified]** — I did not inspect either jar. Its own caution: *"MCP Fabric can grant an AI operator-level control."*

### D2. `arclightpowered/minecraft-mcp` (by IzzelAliz) — closest *architecturally* ⭐

| Fact | Value |
|---|---|
| Repo | <https://github.com/arclightpowered/minecraft-mcp> — *"Minecraft client mod exposing a local MCP-style JSON-RPC endpoint for AI agents"* |
| License | **MIT** (`LICENSE`: "MIT License, Copyright (c) 2026 IzzelAliz"); `gradle.properties` sets `mod_license=MIT`. (GitHub's license classifier says "Other" — the file itself is MIT.) |
| Created / last push | 2026-05-13 / 2026-09-22; last commit 2026-06-01 |
| Stars / releases | 5★ / **no GitHub releases** (source-only) |
| Loaders | **"Supported loaders: Fabric 1.21.1, NeoForge 1.21.1"** |
| Transport | *"Default transport is local HTTP JSON-RPC at `/mcp`… binds to `127.0.0.1` by default and requires a bearer token"*; discovery file `mcp/server.json` |
| **Build strategy** | manual multi-project: root `build.gradle` + `settings.gradle` with `common/`, `fabric/`, `neoforge/`. The neo subproject applies `net.neoforged.gradle.userdev` (NeoGradle). `gradle.properties`: `minecraft_version=1.21.1`, `fabric_loader_version=0.16.14`, `fabric_api_version=0.116.1+1.21.1`, `neoforge_version=21.1.222` |

Feature scope is **strikingly close to mcdebug's**: server + client bridges, packet recording/filtering,
**schematic** tools, **scenario engine with assertions**, inventory/container tools, player-action tools,
screenshot tools, command-suggestion sync, condition/predicate properties. Docs directory:
`docs/protocol.md`, `docs/scenarios.md`, `docs/scenario-conditions.md`, `docs/inventory-container-tools.md`,
`docs/player-action-tools.md`, `docs/packet-tools.md`, `docs/schematics.md`, `docs/headless.md`,
`docs/agent-usage.md`.

This is the **best available reference implementation** for exactly the multi-loader refactor mcdebug is
contemplating: same MC version, same loaders, same "external tool drives a live game" concept, MIT-licensed,
and by a well-known Fabric modder (IzzelAliz). Caveats: 5 stars, no releases, no commits since 2026-06-01, and
it is **client-only** in its README framing (`minecraft-mcp` / `MinecraftClientBridge`).

### D3. Other MCP-style mods (weaker / wrong targets)

| Project | Loaders | 1.21.1? | Note |
|---|---|---|---|
| [`mcp-server-mod`](https://modrinth.com/mod/mcp-server-mod) (cuspymd) | fabric only | **no** (1.21.4+) | 1,585 downloads |
| [`fabric-api-mcp-server`](https://modrinth.com/mod/fabric-api-mcp-server) (chapmanjw) | fabric only | **no** (1.21.11+) | 1,485 downloads; *"exposes the server-side Minecraft API and the Fabric API as MCP tools"* — the closest *conceptual* match to mcdebug on the server plane, but Fabric-only and too new a version |
| [`yuniko-software/minecraft-mcp-server`](https://github.com/yuniko-software/minecraft-mcp-server) | none (Mineflayer) | n/a | 800★, the popular "MCP for Minecraft" — an **external protocol-level bot**, so it works against any server (incl. Forge/NeoForge) but has no mod-side access: no block-entity NBT, no server-side state, no client GUI |

### D4. Carpet / GameTest / other "automation on Forge-family"

- **Carpet** — Fabric only. Modrinth loaders = `["fabric"]`; and it does **not** work through Connector:
  Probe reports `carpet` `1.4.147` **failing** on 1.21.1 (tested 2026-10-24), and `carpet` `26.2`
  **❌ Incompatible** on the 26.2 test run (<https://connector.sinytra.org/compatibility>). Carpet/TIS-style
  tools have **no working NeoForge path**, either native or via Connector. **This is a significant negative
  datapoint for mcdebug's Connector path**, since Carpet is the closest Fabric-side analogue of a
  "server-side scriptable automation" mod.
- **NeoForge GameTest** — a real, first-party, in-process test framework, but **not** a remote RPC bridge:
  <https://docs.neoforged.net/docs/misc/gametest> (*"Game Tests are a way to run in-game unit tests"*) and
  NeoForge's own <https://github.com/neoforged/neoforge/blob/26.2.x/docs/TESTFRAMEWORK.md>. Fabric has the
  equivalent at <https://docs.fabricmc.net/develop/automatic-testing> (and FFAPI ships
  `fabric-gametest-api-v1`). All in-JVM: no socket, no external client, no screenshots, no GUI driving.
- **CC:Tweaked** — **native NeoForge on 1.21.1** per Probe (`type: native`, loader `neoforge`), as is
  WorldEdit, JEI, EMI, REI, ModernFix, Embeddium. CC:Tweaked gives in-game Lua computers with an HTTP client
  and inventory peripherals — genuinely a remote-automation substrate on NeoForge — but it cannot read/write
  arbitrary block-entity NBT, cannot screenshot, and cannot drive the client GUI. Note this also surfaced in
  Connector's tracker as a cross-loader pain point:
  [Connector #1231](https://github.com/Sinytra/Connector/issues/1231) *"Plethora Peripherals (fabric) and
  CC:Tweaked (forge)"*.
- **Axiom** — a cross-loader world editor with its own server-side protocol; [Connector #2190](https://github.com/Sinytra/Connector/issues/2190)
  *"Axiom 5.4.1 crashing on NeoForge 1.21.1 startup"* is closed, and Probe reports Axiom `6.1.3` compatible on
  26.2. It is a *building/editing* tool, not a test-automation RPC — **not** a substitute.

### D5. Are any of these a drop-in alternative to writing a port?

**No — but `mcpfabric` is the only one that comes close enough to be worth an explicit evaluation**, and
`arclightpowered/minecraft-mcp` is worth reading as a reference implementation. The gap is not "can an external
tool read/write blocks on NeoForge 1.21.1" (mcpfabric answers yes, today, from a single source tree) — the gap
is mcdebug's *specific* contract: unix-socket-first transport, `be.getNbt`/`be.setNbt`/`be.tick`, `snapshot.diff`,
`trace.*`, the `wait.until` predicate DSL, `reflect.*`, Transfer-API `storage.*`, and the whole pixel-level GUI
loop (slot clicks, hover/tooltip from the real draw log, annotated screenshots, golden-image compare, hotswap,
reloadScreen). No prior-art project reproduces that set, and each project's own README frames it as an
AI-agent toy rather than a JUnit-drivable test harness. If the goal is "Forge-family users can debug their
machines with mcdebug", none of these replaces mcdebug.

---

## CHEAPEST CREDIBLE PATH — verdict and ranking

### Framing correction that changes the whole answer

**"Forge-family 1.21.1" is two different targets, and only one of them is reachable cheaply.**

| Target | Reachable without a native port? | Evidence |
|---|---|---|
| **NeoForge 1.21.1** | **Yes** — Connector (+FFAPI). Also the target Architectury 13.x and mcpfabric's neoforge jar serve. | Connector `neoforge.mods.toml` requires `neoforge [21.1.97,)`; Architectury API `13.0.11+neoforge` |
| **Plain Forge 1.21.1** (`52.1.20`, the current recommended build) | **No.** Connector is NeoForge-only; Architectury has no Forge 1.21.1 build; Kilt is Fabric-side. | Modrinth loader tags (all 1.21.1 Connector versions = `neoforge`); Architectury 1.21.1 versions = `fabric`+`neoforge` only |

The ecosystem has consolidated on **NeoForge** for 1.21.1. If any user really is on plain Forge 1.21.1, the
only honest options are a native Forge port or telling them to move to NeoForge.

### Ranking

**#1 — CONNECTOR (NeoForge 1.21.1). Cheapest to *attempt*; medium risk; smallest code change.**
This is the only option that is genuinely "avoid a native port", and mcdebug is unusually well-positioned for it:

- ✅ **mcdebug's reflection design is already correct for Connector, by accident of good design.**
  `ForgifiedFabricLoader`'s `RUNTIME_MAPPING = "mojang"`, so `Mappings.kt`'s
  `currentRuntimeNamespace == "intermediary"` probe returns `false` → mcdebug resolves reflection names as
  Mojang at runtime, which is exactly what Connector's remapped bytecode and Mojang-named live classes require.
  A mod that hardcoded intermediary strings would be more exposed than mcdebug is. *[Gating check: verify
  `currentRuntimeNamespace` empirically — see below.]*
- ✅ **Connector's `ReflectionRenamingTransformer` only rewrites `LDC` string constants**, so mcdebug's
  runtime-computed names pass through untouched — and they don't need rewriting, because mcdebug
  self-translates from its own bundled intermediary↔Mojang table, which is namespace-compatible with
  Connector's bundled `mappings.tiny` (`official`/`intermediary`/`mojang`).
- ✅ **`fabric-transfer-api-v1` is a non-issue**: FFAPI 1.21.1 ships `fabric-transfer-api-v1-5.4.4` with the full
  API surface (`Storage`, `ItemStorage.SIDED`, `FluidStorage.SIDED`, `Transaction`, `SnapshotParticipant`,
  `StorageUtil`) plus a two-way NeoForge capability bridge. This was the biggest unknown and it is resolved
  favourably.
- ✅ **`fabric-language-kotlin` is supported and officially tested passing** on `2.0.0-beta.17+1.21.1`
  (2026-10-22).
- ✅ **None of the 6 modules FFAPI omits are used by mcdebug.**
- ✅ **Dedicated servers are a first-class code path** (`ServerMainMixin`), and Connector is marked optional per
  side (`displayTest = IGNORE_ALL_VERSION`), so a server-only install for the server plane works.
- ✅ **Zero user-visible install cost beyond 2 jars** (Connector + FFAPI; no Launchpad on 1.21.1), and
  mcdebug's `"fabric-api": "*"` dependency is satisfied by FFAPI's `fabric_api` mod id.
- ✅ **Loud, not silent, failure**: the Mixin Safeguard (`enableMixinSafeguard`, default true) halts loading
  with a warning screen if Connector can't patch a mod's mixins.

**Risks, ranked, with concrete mitigations:**

1. 🔴 **Loom 1.15 mixin remap bug — actionable.** mcdebug is built with `loom_version=1.15-SNAPSHOT`
   (`gradle.properties`) and its jar carries **intermediary names, no refmap**. FFAPI
   [#266](https://github.com/Sinytra/ForgifiedFabricAPI/issues/266) documents a Loom-1.15 crash whose
   root cause is a tiny-remapper bug dropping mixin method descriptors, with `@WrapOperation` implicated —
   and mcdebug uses `@WrapOperation`/`@Inject` **exclusively**. Maintainer's own resolution: *"I tried using
   loom 1.17 (1.16 still had the issue) and the descriptors are included again now."*
   → **Mitigation: bump Loom to ≥ 1.17.** Cheap, low-risk, independently desirable.
2. 🔴 **[FFAPI #260](https://github.com/Sinytra/ForgifiedFabricAPI/issues/260) is still OPEN** for "Fabric mods
   built with Loom 1.14+" (labelled `1.21.x`). So even after the Loom bump, this class of bug is not
   confirmed dead. Budget for it, and test the *packaged* jar (not `runClient`) in a real NeoForge+Connector
   client.
3. 🟠 **Ambiguous bare intermediary method names in mixin annotations ([Connector #2134](https://github.com/Sinytra/Connector/issues/2134), OPEN).**
   mcdebug ships `@Inject(method=["method_25295"])` with **no descriptor** on `GuiGraphics`, whose `blit`
   family is heavily overloaded. With `injectors.defaultRequire = 1`, a bad pick = client startup failure.
   → **Mitigation: add explicit method descriptors to every `method=` in the mixin annotations** (also removes
   the ambiguity class entirely). Cheap.
4. 🟠 **Nondeterministic transformation output ([Connector #2295](https://github.com/Sinytra/Connector/issues/2295), OPEN).**
   FART race condition; *"1 broken output in 8 samples"*; already observed **on this exact consumer stack**
   (IC2 Refabricate + FLK) and reported by the mcdebug account. Manifests as random `AbstractMethodError`.
   → Not mitigable from mcdebug's side. Accept as a known flakiness source; a fix PR is linked in the issue.
5. 🟠 **Client-plane scope is where the risk concentrates.** Every open client-side Connector bug I found
   (`onInitializeClient` timing vs NeoForge model layers [#2313], `BlockColors` rewrites [#2169], `ItemStack`
   mixin failure [#2151]) is on the *client*. And Carpet — the closest Fabric analogue of a scriptable
   server tool — is **failing** on 1.21.1 via Probe.
   → **Mitigation: ship/stage in two steps. Validate the server plane (NeoForge dedicated server + Connector +
   FFAPI + mcdebug) first — it is low-risk and self-contained. Treat the client plane (`gui.*`) as a separate
   milestone validated in a real client.**
6. 🟡 **`gui.hotswap` will likely degrade**: it calls `FabricLauncherBase.getLauncher()` (an impl/internal
   class) expecting Knot semantics; under Connector that is `FabricLauncherFML`. The code is already guarded
   (falls back to raw file bytes; null-guards the mixin transformer), so expect "hotswap reports failure"
   rather than a crash — but **[unverified]**.
7. 🟡 **Kotlin-runtime coexistence**: if the user's pack has *Kotlin for Forge* alongside FLK, historic crashes
   ([#1654](https://github.com/Sinytra/Connector/issues/1654), [#1671](https://github.com/Sinytra/Connector/issues/1671), both closed).
   Probe tests FLK alone. **[unverified]** for FLK 1.14.x + current Kotlin for Forge.

**Effort to a verdict: S.** Bump Loom → add mixin descriptors → build → run a NeoForge 1.21.1 dedicated server
with Connector + FFAPI + mcdebug and exercise `server.status` / `world.*` / `storage.*` / `reflect.*`; then the
same on a client and exercise `gui.status` / `gui.screenshot`. **That is a day or two of work to convert a
"maybe" into a definitive yes/no**, and it produces a real answer instead of an estimate. Do the
`currentRuntimeNamespace` check on the first boot — it gates everything else.

**#2 — NATIVE MULTI-LOADER BUILD (Architectury or Stonecutter/Modstitch + a NeoForge source set).**
Not "avoid a native port" — it *is* one — but it is the only path with **deterministic risk**, and it is the
only path that could ever reach plain Forge 1.21.1.

- Architectury-loom **1.17** (2026-07-08) and Architectury API **13.0.11** (2026-07-23) are current and healthy
  for 1.21.1, but **for Fabric + NeoForge only** — no Forge 1.21.1, and Architectury's own docs no longer
  mention Forge as a target. Architectury also has **no first-class Kotlin support** (zero `kotlin` matches in
  loom or docs), and the template generator repo is stale since 2023-11-12.
- Effort is dominated **not** by the loader glue (a handful of lifecycle hooks, the transfer/storage mapping —
  for which FFAPI's own `org.sinytra.fabric.transfer_api.compat` classes are a ready-made reference — and a
  simplified `Mappings.kt`, since NeoForge runs Mojang-named and `MappingResolver` has no equivalent) but by
  the **10-class client mixin set**: all currently intermediary-targeted and refmapless, re-pointed at Mojang
  names and re-verified against NeoForge-patched `GuiGraphics`/`GameRenderer`/`Screen`/`RenderSystem`/
  `Minecraft`, with `defaultRequire: 1` turning every miss into a startup failure and **no compatibility shim
  available** for mixin targets.
- Realistic effort: **M–L (weeks)**, mostly verification, not code.
- **Do this after the Connector experiment, not instead of it** — the Connector experiment is what tells you
  whether the client mixin set survives a different class-patch set *at all*, and the experiment is far cheaper
  than the refactor.
- Toolchain: if a **Forge** target is genuinely required, the only live option is a Stonecutter-based setup
  (Gradle Plugin Portal: `dev.kikugie.stonecutter` `0.9.8`, 2026-08-31) — which is exactly what `mcpfabric`
  uses to ship Fabric *and* NeoForge 1.21.1 jars from one tree — optionally starting from
  `rotgruengelb/stonecutter-mod-template` (Fabric+NeoForge+Forge, pushed 2026-10-20). Modstitch
  (`isxander`, last push 2026-04-01) is the (Neo)Forge-unifying alternative but its docs are thin.

**#3 — KILT. Last. Wrong direction, wrong risk profile.**
It is genuinely maintained (`v21.1.18`, 2026-10-28; tracks NeoForge 21.1.265) and it *is* download-able for
1.21.1 — but it solves the mirror-image problem (*Forge mods on Fabric*), so it cannot put mcdebug on a
NeoForge server. Using it means asking the user to **migrate their server from NeoForge to Fabric+Kilt**, and
the project's own README calls Kilt *"highly unstable, may break worlds, may cause crashes"*, published as
Alpha on Modrinth. It also has **no public compatibility matrix** comparable to Connector's Probe service, so
risk for a given modpack is anecdotal. For a mod whose entire value is trustworthy assertions, this is the
worst risk/benefit of the four.

⚠️ **But note the different question Kilt *does* answer**: "how does a Fabric-native mcdebug test a machine
from a Forge-family mod?" — that is precisely why mcdebug's archived 1.20.1 line used a *"Kilt container test
environment"* (AGENTS.md, header + §12). If the real requirement is "our own CI/tests need to drive a
Forge-mod machine", Kilt remains a legitimate tool; it is just not a distribution channel for Forge-family
users.

**#4 — NATIVE FORGE 1.21.1 PORT. Highest cost, and the ecosystem is actively against it.**
Forge 52.1.20 for 1.21.1 exists, but nothing in the compatibility ecosystem targets it: no Connector build, no
Architectury API build, no Architectury docs mention. Undertaking this means betting on a loader the
multi-loader toolchain has already left, for one mod version line, with the same 10-class client mixin problem
as #2 plus a second, more divergent patch set. **Not recommended.**

### Recommended sequence

1. **Cheap hardening first (hours, helps every path):** bump Loom `1.15-SNAPSHOT` → ≥ `1.17`; add explicit
   method descriptors to every mixin `method=` value in `src/client/resources/mcdebug.client.mixins.json`'s
   classes. Both fixes are justified by FFAPI #266 and Connector #2134 *independently of this decision*.
2. **Server-plane Connector spike (hours):** NeoForge 1.21.1 dedicated server + Connector `2.0.0-beta.17+1.21.1`
   + FFAPI `0.116.15+2.3.6+1.21.1` + FLK + mcdebug. On first boot, log
   `FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace`. Then run `server.status`, `world.*`,
   `storage.*` (Transfer API), and `reflect.*` (both a Mojang and an intermediary class name) over the unix
   socket and over TCP.
3. **Client-plane spike (a day or two):** same stack on a real client; `gui.status`, `gui.screenshot`,
   `gui.click --slot`, `gui.screen`, and specifically `gui.hotswap`. Watch for the Mixin Safeguard screen — its
   appearance *is* the answer.
4. **Decide.** If both spikes pass, document "Connector is a supported configuration on NeoForge 1.21.1" with
   the exact version pins, and add it to the smoke scripts. If the client spike fails on mixins, the failure
   mode is loud and specific, and you will know whether it is fixable (rename/re-describe annotations) or
   structural — which is exactly the information needed to cost option #2 properly.
5. **Plain Forge 1.21.1:** tell users the truth — move to NeoForge, or there is no path.

### Uncertainty ledger (explicitly unverified)

- `FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace == "mojang"` at runtime under Connector
  1.21.1 — **verified only as a constant + config class in source, not at runtime.** This gates the entire
  Connector verdict.
- Whether `gui.hotswap` degrades gracefully or throws under `FabricLauncherFML`.
- FLK 1.14.x coexisting with current *Kotlin for Forge* in one NeoForge pack.
- Whether the *current* FFAPI 1.21.1 build actually loads a Loom-1.15/Loom-1.17-built, refmapless,
  `@WrapOperation`-heavy mod — i.e. whether #260 still bites after #266's tiny-remapper fix.
- Kilt's current documented support matrix beyond its (stale) README; no public compatibility data exists.
- The Stonecutter GitHub `pushed_at` (2025-12-25) vs published plugin version 0.9.8 (2026-08-31) discrepancy.
- Any official NeoForge "Fabric compatibility" claim — **I found none**; do not assert one.
- mcpfabric's NeoForge build contents/licence hygiene and its telemetry statement's scope.

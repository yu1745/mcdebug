# JUnit test frontend

Updated: 2026-11-15. Status: current API and consumer setup.

[README](../README.md) · [CLI usage](usage.md)

`mcdebug-testkit` runs ordinary JUnit 5 tests in a host JVM. It talks directly to the
game's JSON-RPC endpoints through `mcdebug-sdk`; it neither executes the CLI nor loads
Minecraft, Fabric, NeoForge, or the mod under test into the test worker. Gradle's standard
`Test` task supplies test selection, assertion failures, and XML/HTML reports.

## Modules

| Module | Responsibility |
| --- | --- |
| `contract` | Method names and wire contracts |
| `sdk` | RPC transport, typed APIs, image comparison, and managed session lifecycle |
| `testkit` | JUnit integration, waits, failure evidence, and the existing server-grid runner |
| `cli` | Command parsing and terminal output, using the same SDK |

The JVM modules target Java 17 bytecode. Building them uses the existing JDK 25 toolchain.
`frontend/` is an independent Gradle build for developing and publishing `contract`,
`sdk`, and `testkit`, without configuring Loom or ModDevGradle.
The game still needs its own loader and JDK requirements.

## GitHub Maven dependency

The public `maven` branch of [yu1745/mcdebug](https://github.com/yu1745/mcdebug/tree/maven)
is a standard Maven repository. Gradle downloads prebuilt JARs, POMs, and module metadata
directly from GitHub Raw. Consumers need neither a GitHub token nor a mcdebug checkout.
No JitPack or local Maven repository is involved.

In a consumer's `build.gradle`:

```groovy
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                name = 'McdebugGitHub'
                url = 'https://raw.githubusercontent.com/yu1745/mcdebug/maven'
            }
        }
        filter { includeGroup 'com.mcdebug' }
    }
}
```

All four artifacts use the same immutable version, currently `0.8.0-mc26.3`:

| Coordinate | Usage |
| --- | --- |
| `com.mcdebug:mcdebug-testkit:0.8.0-mc26.3` | Host JUnit frontend; pulls SDK and contract transitively |
| `com.mcdebug:mcdebug-sdk:0.8.0-mc26.3` | Direct RPC SDK for other frontends |
| `com.mcdebug:mcdebug-contract:0.8.0-mc26.3` | Protocol contracts |
| `com.mcdebug:mcdebug-neoforge:0.8.0-mc26.3` | Self-contained Minecraft 26.3 agent, NeoForge 26.3.0.103+ |

The version includes the Minecraft line. Keep the test frontend and game agent aligned.
Resolve the game agent in a **separate** configuration and copy it to the dedicated game's
`mods/` directory; do not add it to the host test classpath:

```groovy
configurations {
    mcdebugAgent {
        canBeConsumed = false
        canBeResolved = true
        transitive = false
    }
}
dependencies { mcdebugAgent 'com.mcdebug:mcdebug-neoforge:0.8.0-mc26.3' }
// A consumer-owned preparation task can copy configurations.mcdebugAgent into its mods directory.
```

## Gradle task

Use a separate host-side source set. Its classpath must not extend the mod's `main`
runtime or loader classpath:

```groovy
sourceSets { mcdebugTest }
dependencies {
    mcdebugTestImplementation 'com.mcdebug:mcdebug-testkit:0.8.0-mc26.3'
    mcdebugTestImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    mcdebugTestRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
tasks.register('mcdebugTest', Test) {
    testClassesDirs = sourceSets.mcdebugTest.output.classesDirs
    classpath = sourceSets.mcdebugTest.runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty 'junit.jupiter.execution.parallel.enabled', 'false'
    systemProperty 'mcdebug.gameDir', providers.gradleProperty('mcdebugGameDir').get()
    systemProperty 'mcdebug.artifactsDir', layout.buildDirectory.dir('reports/mcdebug').get().asFile.absolutePath
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
```

The example attaches to a running client in the explicitly selected game directory.
It never stops an attached client. A missing endpoint, failed handshake, unloaded world,
or RPC contract mismatch fails execution; the client frontend does not skip these tests.
Keep this task opt-in instead of silently attaching it to a normal build with no game.

`@McDebugClientTest` serializes tests within a JUnit worker, including when a consumer
enables JUnit parallel execution elsewhere. Keep `maxParallelForks = 1` as well: a Screen,
cursor, and player inventory are shared state across tests using one game client.

## A test

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import com.mcdebug.testkit.ClientTestContext;
import com.mcdebug.testkit.McDebugClientTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@McDebugClientTest
class TankClientTest {
  @Test
  void opensRealTankMenu(ClientTestContext ctx) {
    // The consumer fixture first places the tank and moves the player within reach.
    ctx.act("gui.openBlock", Map.of("pos", List.of(14, 200, 0), "face", "south"));
    var screen = ctx.getGui().screen().getAsJsonObject();
    assertEquals("motive.client.gui.FluidStorageScreen", screen.get("class").getAsString());
    assertEquals(38, screen.getAsJsonArray("slots").size());
    ctx.capture("tank");
  }
}
```

Use `getServer()` for authoritative world/inventory state, `getGui()` for real client
Screens, and `getClient()` for a client-plane SDK. `call(method, params)` routes using
the contract and returns JSON. `act(method, params)` additionally fails on explicit
top-level `ok:false` or `success:false`. Use raw `call` for deliberate rejections and
idempotent vanilla commands that legitimately return zero. Assert their intended
postconditions with JUnit instead of treating every successful RPC as a passing test.

`await(description, Duration, BooleanSupplier)` polls a condition and retries ordinary
assertion failures until its monotonic wall-clock deadline. RPC/connection errors fail
immediately. Each RPC also has its own finite deadline; polling does not roll back or
cancel an operation already executed by the game. `waitServerTicks(n)` observes natural
server ticks, and `wait.until` can observe server predicates through the SDK.

Prepare and clean the consumer's own scene in `@BeforeEach` / `@AfterEach`. The testkit
does not guess which world blocks belong to a test or clear an arbitrary region.
Use real `gui.*` operations when testing client behavior: `screen.*` remains a server
fake-player menu API. Coordinate clicks exercise Screen hit testing and custom controls;
slot clicks are useful inventory shortcuts and go through the inventory-click path.

## Managed client

The testkit can use the SDK's existing managed-session lifecycle. Set these additional
system properties on the `Test` task:

| Property | Meaning |
| --- | --- |
| `mcdebug.launchProject` | Standalone loader runtime project with a `runClient` task |
| `mcdebug.testProject` | Project running the tests; prevents launching that same Gradle build recursively |
| `mcdebug.world` | Existing dedicated test save directory name under `saves/` |
| `mcdebug.display` | Optional child `DISPLAY`, otherwise inherited |
| `mcdebug.width`, `mcdebug.height` | Requested window, defaults 1920×1080 |
| `mcdebug.sessionsDir` | Optional isolated registry, defaults beside the game directory |
| `mcdebug.startTimeoutSeconds` | Startup deadline, defaults 180 seconds |
| `mcdebug.requestTimeoutMs` | Positive per-RPC deadline, defaults 30000 ms |

Managed launch currently inherits the SDK's Linux/`setsid` requirement. Use a dedicated
runtime project containing the built mod and agent JARs. Launching the build that is
already running the test worker can deadlock on Gradle project locks; making `Test`
depend on the long-running `runClient` task would prevent tests from starting.

The runtime shares a client across the test run, verifies process identities, and stops
only the client it launched when JUnit closes its root store. It does not delete saves
or terminate shared Gradle daemons. `restartClient()` saves/stops that owned client,
waits for its wrapper to exit, launches again, and reconnects with new identities.
It is unavailable for attached clients.

Managed readiness also waits for the joining Screen and overlay to disappear, then
performs the GUI/server barrier. Merely observing `inWorld:true` is too early to send
the first interaction after joining or restarting. Individual cases should still wait
for their specific Screen, slots, or values rather than assuming any Screen change
means the intended menu opened.

## Evidence and failures

Each invocation has a unique artifact directory, so a later passing run cannot reuse or
overwrite an earlier failure. Each test prints its evidence path into the JUnit report.

Calls through `call` and `act` record their method, parameters, result or error in
`rpc.jsonl`. Typed SDK access remains available; failure snapshots also cover its state.

- `capture(name)` saves a fresh full-window PNG, annotated PNG, and draw-log JSON on the
  test host, using inline RPC bytes rather than assuming a remote filesystem path exists.
- After a test method, newly logged ERROR entries fail the test even if its own assertions
  passed. Startup messages precede the per-test cursor. WARN entries remain diagnostic;
  a truncated error log also fails validation.
- A failed assertion or lifecycle method records its stack, client status, Screen,
  authoritative server status, new problems, and a screenshot. Diagnostic errors are
  attached to the original failure. Scene cleanup still runs afterwards.
- Standard reports remain under `build/test-results/<task>` and `build/reports/tests/<task>`.

Prefer semantic assertions on state, slots, text, and network results. Image comparisons
are available through the SDK for cases that need pixel evidence; approve baselines
explicitly and handle animation, scale, and render backend differences deliberately.
Capturing an image alone does not assert that a model or its animation is correct.

## Coverage boundary

This frontend exercises live GUI behavior, synchronization, storage effects, and rendering.
Its key/type APIs currently dispatch into an open Screen; a complete movement/combat
input driver is outside the current API. Keep game logic tests and client integration
tests as complementary checks. A passing initial suite is evidence of feasibility,
not proof of complete mod coverage or a line-coverage percentage.

## Publishing

Maintainers set `maven_version` in the root `gradle.properties`. Build the game agent,
then stage a standard repository (JARs, sources, POMs, module metadata, and checksums):

```sh
./gradlew build :cli:copyCliJar
./gradlew -p frontend stageMavenRepository
```

The default staging directory is `frontend/build/github-maven`; `-PmavenRepoDir=/path/to/repository`
selects a checkout of the existing Maven branch instead. Preserve that branch's older
versions and metadata when staging an update. Never replace an already published version;
increase `maven_version` for every artifact change. The `publish Maven repository` workflow
builds and publishes version changes on branch `26.3`, and records the source commit under
`releases/<version>.txt` on the `maven` branch.

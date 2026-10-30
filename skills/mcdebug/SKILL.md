---
name: mcdebug
description: Drive a live Minecraft (Fabric 1.21.1 or NeoForge 21.1.x) game through the mcdebug CLI (`mdb` / `java -jar mcdebug-cli.jar`) - set blocks, NBT, inventories, storages, entities on the server plane; screenshot, click, hover and hot-reload GUI Screens on the client plane; assert with wait.until. Use when testing or debugging a mod's blocks, machines or GUIs in a running game, or when operating a server through its mcdebug RPC socket.
---

# mcdebug CLI

mcdebug is a mod (one jar, server + client) that serves JSON-RPC over a Unix socket; the CLI talks
to it. Every subcommand has a detailed `--help` with schemas and pitfalls: **read it before
guessing options.** `mcdebug raw <method> '<json>'` reaches any RPC, including ones without a
subcommand.

## 0. Find or install the CLI

Use the first one that exists: `mcdebug` on `PATH`, `mdb` on `PATH` (native build from
`scripts/build-native.sh`, ~11 ms start), or a cached jar under
`${XDG_DATA_HOME:-~/.local/share}/mcdebug/<version>/mcdebug-cli.jar`.

If none is there, download the release jar into that cache (one directory per version) and
put a launcher on `~/.local/bin` (on `PATH` in most distros). It needs Java 17+ (`java -version`;
`gui hotswap` additionally needs a full JDK). Pick the version of the mod in the game
(`modVersion` in `status`), or the newest release:

```bash
V=0.6.3   # newest: gh release list -R yu1745/mcdebug -L 1, or the GitHub releases page
D="${XDG_DATA_HOME:-$HOME/.local/share}/mcdebug/$V"
U="https://github.com/yu1745/mcdebug/releases/download/v$V"
mkdir -p "$D" "$HOME/.local/bin"
curl -fL -o "$D/mcdebug-cli.jar" "$U/mcdebug-cli.jar"
echo "$(curl -fsL "$U/mcdebug-cli.jar.sha256")  $D/mcdebug-cli.jar" | sha256sum -c -   # must say OK
printf '#!/bin/sh\nexec java -jar "%s" "$@"\n' "$D/mcdebug-cli.jar" > "$HOME/.local/bin/mcdebug"
chmod +x "$HOME/.local/bin/mcdebug"
mcdebug --version
```

Releases before v0.6.3 have no `mcdebug-cli.jar` asset. From a source checkout instead:
`./gradlew :cli:copyCliJar` writes `./mcdebug-cli.jar`. The CLI carries the mod jar of **both
loaders** and this skill, all of its own version: `mcdebug jar --output <gameDir>/mods` installs
the Fabric mod, `mcdebug jar --loader neoforge --output <gameDir>/mods` the NeoForge one (each
removes the other/older jars), `mcdebug skill` refreshes this skill to match the CLI.

Below, `mcdebug` means whichever of these you found.

## 1. Get a target

- **Own dev client (preferred for mod work)**: a managed session starts the project's `runClient`
  and finds both planes for you:
  ```bash
  mcdebug jar --output run/mods                     # same-version mod jar into the consumer's mods/ (removes old ones)
  mcdebug session start dev --project . --world "New World"                  # world = saves/ dir name
  mcdebug --session dev status                      # every later call: --session dev
  mcdebug session stop dev                          # owned sessions only; saves the world
  ```
  Multi-project builds: `--game-dir core/run` is enough, the task becomes `:core:runClient` (a bare
  `runClient` there would start every subproject's client at once). One client per gameDir:
  a second one falls back to the title screen ("session.lock: already locked").
  Startup to `world_ready` takes tens of seconds (project-dependent). If MC or the build dies first, `start` returns `failed`
  at once with the crash line in `lastError`; `session logs dev` has the full output. A Fabric
  loader error (remap, missing dependency) also gives `failed`, but its error window can keep the
  JVM open: `session stop --force`.
  `--width/--height` lose to `programArgs '--width' ...` in the project's `loom.runs.client`
  (the session record still shows the requested size; `gui status` shows the real window).
  `--render` defaults to **demand** for a managed session (the mod's own global default is
  continuous); pass `--render continuous` when a task needs a normal continuous frameloop.
  States: `starting` → `client_ready` (title screen) → `world_ready` (needs `--world`, must be
  in-world *and* singleplayer), then `stopping`/`stopped`; failures are `failed` (see `lastError`)
  and `dead`. `session logs NAME --lines N` tails the session log (the option is `--lines`; there
  is no `--tail`).
  After `session stop` the discovery files (`mcdebug/port`, `clientPort`, locks, `client.json`,
  `session-stop.json`) stay; only the sockets are removed, so an existing `port` file does not mean
  a live endpoint. The Gradle wrapper of a stopped session can linger ~10 s after `stop` already
  reports `stopped`: an immediate "no leftover process" check can false-alarm, and an immediate
  restart in the same gameDir can collide with it.
  Records live in `$XDG_STATE_HOME/mcdebug/sessions/<name>.json` (`--sessions-dir` to change, or the
  `MCDEBUG_SESSIONS_DIR` environment variable);
  `session remove NAME` forgets an attached record any time (the game keeps running) and an owned
  one once it is stopped/failed/dead. Never delete lock or discovery files by hand: they guard
  against driving the wrong JVM.
  Transport flags are per plane: `--socket`/`--tcp` address the **server** plane,
  `--client-socket`/`--client-tcp` the client plane, and `--session NAME` routes each call by its
  method. Passing `--socket` to a `gui.*` method is silently ignored (that call still uses client
  discovery), and discovery without an explicit flag resolves relative to the **current directory**
  — run from the game/project directory or pass absolute paths.
  A unix socket path over the OS limit (~107 bytes, deep gameDirs) or a JVM without AF_UNIX makes
  the mod fall back to loopback TCP; discovery then holds `tcp:127.0.0.1:N` and the CLI follows it.
  That is normal, not an error.
- **Existing server/client**: `--socket <gameDir>/mcdebug/socket` (server plane),
  `--client-socket <gameDir>/mcdebug/clientSocket` (client plane), or `--tcp host:port` across
  machines. Running inside the gameDir discovers them automatically.
- `mcdebug status` / `mcdebug gui status --brief` first. A CLI/mod version mismatch prints a warning.

## 2. Server plane: set up and assert

```bash
mcdebug raw world.setBlock '{"pos":[100,64,100],"block":"mymod:machine"}'
mcdebug raw inv.setSlot    '{"pos":[100,64,100],"slot":0,"item":"minecraft:coal","count":8}'
mcdebug raw storage.insert '{"target":{"kind":"block","pos":[100,64,100]},"handle":"teamreborn:energy","resource":{"kind":"energy"},"amount":10000}'
mcdebug raw be.getNbt      '{"pos":[100,64,100]}'
mcdebug raw wait.until     '{"predicate":"be[100,64,100].progress >= 100","timeoutTicks":400}'
```

- Block state: `"stateProps":{"facing":"east"}` (or `"state"`; CLI `--state k=v`, repeatable);
  properties you leave out keep their default. Without properties the block takes the shape its
  neighbours imply, like `/setblock` (wire connects, fences join); placement logic does not run
  otherwise (no facing toward a player, no item-placement side effects). Put things on a solid
  floor: wire, levers, gravity blocks and the player all drop off air.
- Positions are `[x,y,z]`; boxes `{"from":[..],"to":[..]}`, both corners inclusive, passed under
  a `box` key: `raw scan.findEntities '{"box":{"from":[0,60,0],"to":[10,80,10]}}'`.
- `inv.*` only reaches block-entity containers. The **player inventory**: `scan.findEntities`
  (box around the player) for the uuid, then `raw entity.getNbt '{"uuid":"..."}'` -> `Inventory[]`
  (its `Slot` is the player's own numbering), or `gui screen` while a container GUI is open.
  To give the player an item: `raw server.runCommand '{"command":"give @p minecraft:diamond 4"}'`
  (any server command works this way; it is also how to clear, teleport, set time).
- `storage list` shows which handles a block exposes. `storage.*` is the generic item/fluid/energy API (handles `vanilla:inventory`, `fabric:item`,
  `fabric:fluid`, `teamreborn:energy` — the same handle names on both loaders, the implementation
  behind them is Fabric's Transfer API resp. NeoForge capabilities).
  **Amounts use each loader's native unit** — read `server.status.fluidUnit` (`droplet` on Fabric,
  81000 = 1 bucket; `mb` on NeoForge, 1000 = 1 bucket) and `server.status.energyUnit` (`E` on
  Fabric, `FE` on NeoForge) before comparing numbers across loaders. On NeoForge, `fluid.*`'s
  `--index` only selects which tank is *reported* (fill/drain go through the whole
  `IFluidHandler`), and `supportsInsertion/supportsExtraction` describe the **current** state
  (a full tank answers `supportsInsertion:false` although it can be drained) while Fabric's are
  capability flags — do not compare the two across loaders, and do not treat `false` as "this
  storage can never take it".
  `reflect.*` resolves FQNs on NeoForge (its short-name table is Fabric-only). Both `reflect get`
  and `reflect set` take `--path`; `set` writes the **last** segment (`--path overworld.dayTime`
  writes `dayTime` on `overworld`), and `--field` is the direct form. Big collections come back
  summarised: a ref answer carries `size` and, when the summary was cut, `truncated:true` +
  `returned:N`; an inline plain-value array appends a final `{"$truncated":<how many more>}`
  element. Read `size` before concluding a key does not exist.
  `inserted`/`extracted` can be far below `amount`: the block's own transfer limit applies, and
  it is usually **per tick**, so repeating the call within the same tick (a tight loop, `--batch`)
  gets 0. `remaining` is about this call only; read the result back with `storage get`. To fill
  quickly, write the block entity's NBT instead of looping.
- **Entities on a server without players do not stay**: vanilla despawns mobs with no player
  nearby (measured ~3 s on a dedicated server, even with `PersistenceRequired`), so
  `entity spawn` -> `scan.findEntities` can look broken when the entity is simply gone. Pad the
  floor (spawn does **not** snap to the ground: a pig dropped 120 -> 111), keep the whole
  spawn-assert sequence in one `-c` batch (one round trip, sub-second), and re-check the entity
  count with `scan.findEntities` before concluding anything.
- `inv.setSlot --nbt` writes 1.21.1 **item components**: a raw tag ends up under
  `components."minecraft:custom_data"` and `inv.getSlot` echoes exactly that back, so "it round
  tripped" does not prove the mod read it. Assert through the API that matters (the GUI, the
  behaviour) like any other NBT.
- **Verifying pixels against a scene**: a container/UI screen can be pinned down (`--mode gui` crops
  to the GUI, `gui screen` gives slot rectangles), but a *world* frame cannot be cropped to a
  region (no region flag) and weather/entity animation makes two unedited frames differ by tens of
  percent of pixels. For world scenes assert logically (`gui open-block` succeeded, the draw log
  contains the item you placed, the server reads the same state) and use `pixelHash` only as a
  "this frame changed" signal; reserve `gui compare` for stable UI frames with `--ignore` regions.
- Slot coordinates differ per plane: `gui screen` slots are **absolute GUI units** (the same space
  as `gui click --gui`), while `screen.*`'s `slotDetails` (and the server-side menu) use
  container-relative coordinates; convert with the container's `gui.left/top` from `gui screen`.
  `snapshot`/`storage` report a slot's `capacity` as what that slot can actually take
  (`min(slot limit, the stack's own max size)`, 64 for an empty slot), so it can change when the
  slot's item changes — not a fixture to compare across different items.
- `trace get` frames are `{tick, snapshot}` objects (the snapshot is the same shape `snapshot.*`
  returns), so a trace is diffed by reading the field you care about per frame, not by comparing
  whole frames.
- `snapshot capture` is the method for taking a box snapshot (`snapshot.take` does not exist);
  `snapshot --help` lists the operations, and there is no generic "list all RPC methods" command —
  use per-group `--help` and this skill.
- `be.setNbt` loads the given tag into the block entity; what happens to keys you leave out is
  up to that block entity's load code. Write only the keys you mean to change. `be set-field`
  writes one path, reads it back and answers `ok:false reason:NOT_APPLIED` when the load code
  ignored it (unknown key, derived value); a plain number takes the existing field's numeric type.
  A field can still change right after (the block's own tick): it was applied, the game moved on. One value may be
  stored in several fields (a mirror, a split long): verify through the API that matters
  (`storage get`, `inv.getSlot`, the GUI), not by reading the NBT back.
- `raw craft.craft` takes `{"grid":[ ... 9 cells ... ]}` where each cell is **null or an object**
  like `{"item":"minecraft:oak_log","count":1}` (a bare string is a shape error and answers
  `-32602` naming `grid[i]`); `craft do <item>` is the shortcut that builds the grid for you.
- Slot numbers of a block are its own: find input/output slots with `gui screen` (GUI open) or
  `inv get-size` + `inv get-slot`, never by guessing. `inv extract` needs `--item` (it is a
  storage-style extract); for a whole slot use `inv set-slot` with an empty item.
- **Ticks are never driven**: wait with `wait.until` (predicate DSL: `be[..].<path>`,
  `inv[..].<slot>.count`, `block[..].id`, `block[..].prop.lit == true`, `AND/OR/NOT`, `sum(inv[..].*.count)`; see `wait --help`).
  **Prefer short waits** (a few seconds of ticks) and wait again if the condition is merely slow:
  a timeout costs nothing and the next wait resumes, but a long wait on a wrong predicate (typo
  in a path, wrong slot, machine without power) burns its full timeout for nothing. Before a
  real wait, read the values the predicate uses (`be.getNbt`, `inv.getSlot`) or run it once with
  `timeoutTicks` 1-20 and look at `lastValue`: a `null`/unexpected value means the predicate,
  not the game, is wrong.
  The one exception, `be.tick` (`ticks` 0..12000), runs a single block entity's ticker
  synchronously: it is fast, but world time does not advance and neighbours do not tick, so
  anything another block does to this one (pushing energy/items, redstone) does not happen. It
  is the real ticker, so it consumes what a real tick consumes (energy, fuel, inputs).
  `wait.until` answers with `ranTicks`: how long the condition actually took.
- `trace start` records snapshots every `--interval-ticks` (`--max-ticks` auto-stops) and prints a
  `traceId` for `trace get`/`trace stop`; it shares snapshot's `--include` (`block`, `inventory`,
  `entity`, ...): machine slots need `inventory`. `trace get` returns every frame: pipe it through
  a filter rather than reading it whole.
- `snapshot capture` + `snapshot diff` compare state (metadata `/tick` `/time` ignored by default);
  diff paths are JSON pointers into the capture (`/blocks/13/...` is an array index, not a
  coordinate), and one stack can show up once per storage handle that exposes it; entities and
  their bookkeeping NBT change on their own, so narrow `--include` to what the test is about;
  captures hold absolute positions, so the same build at another origin is all "changed".
- `fixture capture` saves a block region as JSON; block-entity contents (chest items, machine
  NBT) only with `--include-nbt`. `fixture load --origin` places it anywhere.
- `redstone`: `set-lever --powered|--unpowered`, `pulse`, `get-power` (`received`, per-side
  `inputs`/`outputs`), `notify-neighbors` after edits that skipped updates.
- `reflect` reaches anything else. Start from `--root server|level|be|entity|player` (`--pos`,
  `--uuid`, `--name`, `--dim`), then `--path a.b[0].c`: a segment is a field, else a no-arg
  method, else `getX`/`isX`; there are no arguments or expressions (use `reflect call`). Mojang
  and intermediary names both work; `reflect resolve --root ... --path ... --members` lists what
  is there. Objects come back as `{"$ref":N}`, usable by later commands (bounded, gone when the
  server stops); prefer one `--path` over hopping ref to ref. `reflect set` writes a non-final
  field (numbers take the field's type) and prints before/after; an ambiguous overload in
  `reflect call` asks for `--arg-types int,long`. It bypasses the block's own logic: no sync,
  no `setChanged`; prefer the normal APIs when they exist.
  Client-side state (the open Screen, widgets, client caches) is the same command with
  `--client`, rooted at `minecraft|screen|player|level|server`; e.g.
  `reflect get --client --root screen --path 'children[0].message.string'`. Its refs live in
  the client process, so `--client` must be on every call that uses one.
  Client block entities carry **no items**: a client-side chest/machine BE always reads empty
  even when the GUI shows contents (vanilla syncs inventories into the open menu, not the BE).
  For what the client shows use `--root screen --path 'menu.slots[N].item'`; for truth use the
  server plane. Server → client state is a packet behind: `gui settle` before reading.
  `reflect set` on the client also skips `options.txt`: `OptionInstance.set` changes the live
  value (visible in `gui status`/Video Settings) but nothing is written to disk.
- `screen.*` is a **server-side fake-player menu** (slot JSON, no pixels). Pixels only come from `gui.*`.

## 3. Client plane: the GUI loop

> **Tooltips need the lite render mode.** On a headless/CI box (no window manager) the game window
> is never focused, and Minecraft ignores real cursor movement while unfocused — so in the default
> `continuous` mode `gui hover` moves the pointer but the game keeps computing the hover from its
> stale position (symptom: `tooltips: []`, `hoveredSlot` pointing at the wrong inventory slot).
> Start the client with `clientRenderMode=demand` (or `gui-only`) — in lite mode the mod feeds a
> **virtual cursor** into the frame it renders, so hover works unfocused. Clicks, screenshots,
> `gui screen`, `scroll`, `key`, `type` and `drag` work in either mode.


```bash
mcdebug gui open-block --x 100 --y 64 --z 100            # or: gui open-screen pkg.Fixtures#myScreen
mcdebug gui screenshot --mode gui --annotate             # *.annotated.png: cyan = slot ids, magenta = widget ids
mcdebug gui screen --brief                               # slots + widgets as JSON (GUI units)
mcdebug gui click --slot 0 --shot gui                    # click -> settle -> screenshot, one round trip
mcdebug gui hover --slot 0                               # tooltips really drawn that frame
mcdebug gui screenshot --mode gui --draw-log             # every blit/fill/text with GUI rect + texture UV
mcdebug gui problems --brief                             # WARN/ERROR log + caught render faults
```

- **`--slot N` is the index in the open menu** (`gui screen` `slots[].index`, the cyan numbers),
  not a player-inventory number: the menu lists the block's own slots first, so the player's
  `Inventory` Slot N sits at a different, menu-specific index. To find an item, filter
  `gui screen` slots by `item` and use that index.
- Click targets: `--slot N`, `--widget N` (the `[N]` in `gui screen --brief`), `--text "Label"`
  (substring, case-insensitive; also matches text drawn in renderBg or list rows that are not
  widgets, e.g. world-list entries), `--gui X,Y`, `--png X,Y` (pixels of the previous screenshot).
- Scroll lists: an entry scrolled out of view is `[clipped]` in `gui screen`; clicking it fails with
  `WIDGET_CLIPPED`. `gui scroll --dy -N --gui X,Y` (over the list) first. Only rendered rows have
  current coordinates, so mdb renders a frame before `gui screen`/targets on such Screens.
- `open-block` walks the real client use path: the player must be in reach, the hand empty (the
  default) and the block present **on the client**. `reason:NO_BLOCK` + `block` show what the
  client saw (e.g. a gravity block like an anvil fell off the air you placed it on);
  `reason:OUT_OF_REACH` + `distance`/`reach`: teleport the player next to it first.
- Slot clicks obey the menu's rules: a slot whose `mayPlace` rejects the item leaves it on the
  cursor (`changed:false`, `carried` still set). Check `slotAfter`/`carried`, not `ok`.
- `gui close` = Esc: a sub-screen returns to its parent (Video Settings -> Options), so closing to
  the world may take several. Closing a container with a carried stack hands it back to vanilla.
- `gui key`/`type` go to the current Screen only (no Screen: `-32020`). `type` appends to the
  focused EditBox, and boxes often start pre-filled (an anvil shows the item's name): use
  `gui type --text X --clear` to replace. `handled:false` means nothing took the characters (no
  focus, or a disabled box, e.g. an anvil with an empty input slot). Typing can also succeed
  (`handled:true`, the value sets) while the container still refuses the result — an anvil
  needs XP for a rename: `click --slot 2` then reports `changed:false`. Judge by the click
  report, not by `handled`.
- Inside `gui act`, a follow-up view that fails (e.g. `--shot gui` on a screen that just
  navigated to options) no longer discards the action: the result is kept and the failure
  arrives as `screenshotError`/`screenError` with `ok:false`.
- `--text` matches substrings across the whole Screen: a label also inside a button ("New World"
  in "Create New World") hits the first widget. Narrow the screen first (a search box), pass
  `--index`, or click the row's `--gui X,Y` from `gui screen`/a screenshot.
  A key that navigates (Esc, Enter) returns `ok:true reason:CHANGED_BY_INPUT`.
- `gui drag` with a carried stack over slots is vanilla drag-splitting (QUICK_CRAFT).
- Errors: a plain `gui click/hover/...` that cannot act exits 2 with `error [-320xx]` on stderr
  (e.g. `-32021 WIDGET_CLIPPED`); inside `gui act` the same failure is JSON `ok:false` + `error`
  with exit 0, so check `ok` there.
- `gui hover` returns `tooltips:[]` with `ok:true` when nothing was drawn; `reason:EMPTY_SLOT`
  (plus `stack`) means the slot holds nothing: you hovered the wrong index, do not retry.
- `gui scroll --dy` is in wheel notches (an options list moves about one row per notch, negative
  = down); overshooting is harmless, so `--dy 30` returns to the top.
- `gui open-screen` fills constructors from Screen/Player/Minecraft/Options only; anything needing
  other arguments (ChatScreen(String), creative inventory) needs a `pkg.Class#factory`.
- Options/Video Settings clicks change the real `options.txt` of that gameDir.
- `gui disconnect` keeps the client on the title screen and removes the server-plane socket; get
  back in with `gui click --text Singleplayer`, `gui type --text <save>` (search box),
  `gui click --text <save>`, `gui click --text "Play Selected World"`.
- After server-plane writes run `mcdebug gui settle` before looking; clicks settle themselves.
- Read results from data (`gui screen`, the click report, `be.getNbt`), not from pixels alone.
- A Screen that throws while rendering no longer crashes the client: it stops drawing and
  `gui problems` shows the trace.

### 3b. Flags vs raw parameters

The CLI flags are the friendly face of the RPC parameters; when you use `raw` you must spell the
parameters the way the handler reads them:

| CLI flag | raw parameter |
|---|---|
| `gui screenshot --mode window\|gui` | `mode: "window" \| "gui"` (there is **no** `crop` — `mode:"gui"` crops to the container rectangle; use `--out` + a local crop otherwise) |
| `gui screenshot --hash-only` | `format: "none"` (no PNG, no file) |
| `gui screenshot --hide-cursor` | `cursor: "hide"` (also `"keep"`, default) |
| `gui screenshot --annotate` / `--draw-log` | `annotate: true` / `drawLog: true` (+ `delayTicks`, `inline`, `name`) |
| `gui click --slot/--widget/--text/--png/--gui` | `slot` / `widget` / `text` / `png:[x,y]` / `gui:[x,y]` |

Unknown raw parameters are **silently ignored** (a typo in `crop` returns the full window instead of
an error) — check `transform.crop`/`pngSize` in the response when the result matters.
`gui click --gui`/`--png` report only the landing point (`guiX/guiY`), not `via`/`before`, unlike
`--slot`/`--widget`/`--text`. `gui drag` echoes the **end** point only; keep your `--from` yourself.
When no screen is open, `gui key`/`gui type` fail with the same `-32020 CLIENT_NOT_READY` code used
for "client not ready" — distinguish by `data.reason` ("no Screen is open").

- `gui open-screen` takes a **runtime class name** (or the `inventory` alias); there is no command
  that lists Screen classes and an unknown one answers `CLASS_NOT_FOUND` with only the name you
  guessed. To discover names, read them off a live screen: `gui screen` / `gui status` print the
  current screen's class, and `reflect resolve --client --root screen` gives the exact runtime name
  to reuse. Screens from your own project are addressed by their own factory (`pkg.Fixtures#make`,
  dev classpath only).

## 4. Change code without restarting

```bash
./gradlew classes                                                # split source sets: clientClasses for client code
mcdebug gui hotswap --classes build/classes/kotlin/client        # method bodies only; all or nothing; re-inits the Screen
mcdebug gui reload-screen --classes build/classes/kotlin/client  # open Screen's own class: any change, same menu
mcdebug gui reload-resources                                     # textures / lang / models
```

Use the output dirs your build really writes (`build/classes/java/main`, `.../kotlin/client`, ...).
Hotswap reports `failed[].changes` when a class gained or lost members: then use reload-screen (for
the Screen class) or restart the session. Neither one reruns constructors of existing objects.
Cost: the build dominates (the first incremental compile after an edit is much slower than the
next ones); the swap itself is well under a second, versus a full client restart. `attached:true` = this call loaded the hotswap agent
into the JVM, `false` = it was already loaded (normal, not a failure).

- `changed:0` with a stderr note = no newer class files in that dir: wrong dir or wrong compile task.
  Relative `--classes` resolve against the CLI's current directory, not the game's: pass absolute
  paths when the shell is elsewhere.
- A Screen shown by reload-screen is a copy in its own class loader. A later hotswap redefines the
  real class (`skippedReloadCopies` lists the copies) but not that open copy: the `hint` says so;
  run reload-screen again, or close and reopen the GUI to get the real (hotswapped) class.
- Failure exits 2 and redefines nothing (all or nothing).
- `reload-screen`'s `reloads` counts reloads of this Screen so far; `classes` lists what was loaded.

## 4b. Two planes, one CLI

`world.*`/`be.*`/`inv.*`/`storage.*`/`scan.*`/`wait.*`/`reflect.*` go to the **server** plane,
`gui.*` to the **client** plane. `--session NAME` picks the plane per method; with explicit
transports you must pick it yourself (`--socket`/`--tcp` = server, `--client-socket`/`--client-tcp`
= client). Calling a client method with only a server transport is refused by the CLI (exit 1)
*before* the request is sent — you will never see the server's `-32601 method not found` for this
case, and `--socket` passed to a `gui.*` method is silently ignored (that call uses client
discovery, which resolves relative to the current directory).

## 5. Regression

- `gui compare` exits 1 on a mismatch and prints a JSON line **plus** a human `error: ...` line on
  stdout — parse the exit code, or the JSON line, not the whole stream. Same-view frames still
  differ where the player model animates (the cursor-hide interpolation does too): pass
  `--ignore x,y,w,h` for that region or the comparison is noise. Size mismatch fails immediately and
  writes no diff image.

`mcdebug gui screenshot --mode gui --hide-cursor --out new.png`, then
`mcdebug gui compare --expected golden.png --actual new.png --ignore x,y,w,h --report r.html`
(exit 1 on mismatch; `--ignore` is in PNG pixels, w and h > 0; read `$?` without a pipe or with
`set -o pipefail`, `| head` hides it). Only an explicit `--accept` writes the golden.
`--mode gui` crops to the **container** GUI rectangle, so it needs an open container Screen
(chest/furnace/inventory); on options-style Screens it fails (`NO_CONTAINER_SCREEN`) — use
`--mode window` there. Even then translucent texture edges still blend with the world
behind it: if two identical shots differ by 1 per channel, use `--channel-tolerance 2`. `--annotate`
with `--out x.png` also writes `x.annotated.png` (copies: `path` stays under
`<gameDir>/mcdebug/screenshots/`, `copiedTo` is yours). `--hash-only` is the cheap "did it change?" probe.

`--draw-log` adds `drawLog: {count, truncated, coordinateSpace, entries[]}`; each entry has `op`
(`blit`/`fill`/`text`/`item`/`tooltip`...), `rect` `[x,y,w,h]` in GUI units, and per op `text`,
`color` (`#AARRGGBB`), `texture`, `uv`. The same text can be drawn more than once (a Screen may park
the vanilla label off-screen and draw its own): keep entries whose `rect` lies inside the GUI.
`gui problems` covers the whole JVM, startup noise from other mods included: compare `--since`
cursors around your action rather than reading the full list.

## Costs

With `java -jar`, JVM start-up dominates a single call; the RPC itself is usually a fraction of
it (screenshots and settles cost a few frames/ticks more). Many steps in a row: one JVM with
`mcdebug --session dev --batch -c "..." -c "..."`, one round trip with `gui act` (action + settle +
screenshot + screen), or the native `mdb`. Inside `-c` a JSON argument may be written bare
(`-c 'raw be.getNbt {"pos":[0,64,0]}'`); large params go in a file: `raw method @params.json`.
`reload-resources` reloads every pack: seconds, not ms.

## Exit codes and errors

0 ok (including business `ok:false`), 1 usage error, 2 RPC error, 3 method not found (older mod).
`-32602` = bad/missing param (the message names it), `-32603` = server exception (`data.exception`,
`data.at` stack frames), `-32020` = client not ready / not in a world, `-32021` = GUI element not
found (data lists candidates).

---
name: mcdebug
description: Drive a live Minecraft (Fabric 1.21.1) game through the mcdebug CLI (`mdb` / `java -jar mcdebug-cli.jar`) - set blocks, NBT, inventories, storages, entities on the server plane; screenshot, click, hover and hot-reload GUI Screens on the client plane; assert with wait.until. Use when testing or debugging a mod's blocks, machines or GUIs in a running game, or when operating a server through its mcdebug RPC socket.
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
`./gradlew :cli:copyCliJar` writes `./mcdebug-cli.jar`. The CLI carries the mod jar and this
skill of its own version: `mcdebug jar --output <gameDir>/mods` installs a matching mod (and
removes old ones), `mcdebug skill` refreshes this skill to match the CLI.

Below, `mcdebug` means whichever of these you found.

## 1. Get a target

- **Own dev client (preferred for mod work)**: a managed session starts the project's `runClient`
  and finds both planes for you:
  ```bash
  mcdebug jar --output run/mods                     # same-version mod jar into the consumer's mods/ (removes old ones)
  mcdebug session start dev --project . --world "New World" --render demand   # world = saves/ dir name
  mcdebug --session dev status                      # every later call: --session dev
  mcdebug session stop dev                          # owned sessions only; saves the world
  ```
  Submodule projects need `--task :core:runClient --game-dir core/run`. One client per gameDir:
  a second one falls back to the title screen ("session.lock: already locked").
  Startup to `world_ready` takes ~30 s. If MC or the build dies first, `start` returns `failed`
  at once with the crash line in `lastError`; `session logs dev` has the full output.
  `--width/--height` lose to `programArgs '--width' ...` in the project's `loom.runs.client`
  (the session record still shows the requested size; `gui status` shows the real window).
  After `session stop` the discovery files (`mcdebug/port`, `clientPort`, locks) stay; only the
  sockets are removed, so an existing `port` file does not mean a live endpoint.
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

- Positions are `[x,y,z]`; boxes `{"from":[..],"to":[..]}`, both corners inclusive, passed under
  a `box` key: `raw scan.findEntities '{"box":{"from":[0,60,0],"to":[10,80,10]}}'`.
- `inv.*` only reaches block-entity containers. The **player inventory**: `scan.findEntities`
  (box around the player) for the uuid, then `raw entity.getNbt '{"uuid":"..."}'` -> `Inventory[]`
  (its `Slot` is the player's own numbering), or `gui screen` while a container GUI is open.
- `storage.*` is the generic item/fluid/energy API (handles `vanilla:inventory`, `fabric:item`,
  `fabric:fluid`, `teamreborn:energy`); fluids are in droplets (81000 = 1 bucket).
- **Ticks are never driven**: wait with `wait.until` (predicate DSL: `be[..].<path>`,
  `inv[..].<slot>.count`, `block[..].id`, `AND/OR/NOT`, `sum(inv[..].*.count)`; see `wait --help`).
  The one exception, `be.tick` (`ticks` 0..12000), runs a single block entity's ticker
  synchronously: it is fast, but world time does not advance and neighbours do not tick.
- `snapshot capture` + `snapshot diff` compare state (metadata `/tick` `/time` ignored by default);
  `fixture` saves/loads block regions as JSON; `reflect.*` reaches anything else (Mojang or
  intermediary names both work).
- `screen.*` is a **server-side fake-player menu** (slot JSON, no pixels). Pixels only come from `gui.*`.

## 3. Client plane: the GUI loop

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
  not a player-inventory number: a 7-slot machine puts the player's `Inventory` Slot 21 at menu
  index 19. To find an item, filter `gui screen` slots by `item` and use that index.
- Click targets: `--slot N`, `--widget N` (the `[N]` in `gui screen --brief`), `--text "Label"`
  (substring, case-insensitive; also matches text drawn in renderBg or list rows that are not
  widgets, e.g. world-list entries), `--gui X,Y`, `--png X,Y` (pixels of the previous screenshot).
- Scroll lists: an entry scrolled out of view is `[clipped]` in `gui screen`; clicking it fails with
  `WIDGET_CLIPPED`. `gui scroll --dy -N --gui X,Y` (over the list) first. Only rendered rows have
  current coordinates, so mdb renders a frame before `gui screen`/targets on such Screens.
- `open-block` walks the real client use path: the player must be in reach, the hand empty (the
  default) and the block present **on the client**. `reason:NO_BLOCK` + `block` show what the
  client saw (e.g. a gravity block like an anvil fell off the air you placed it on).
- Slot clicks obey the menu's rules: a slot whose `mayPlace` rejects the item leaves it on the
  cursor (`changed:false`, `carried` still set). Check `slotAfter`/`carried`, not `ok`.
- `gui close` = Esc: a sub-screen returns to its parent (Video Settings -> Options), so closing to
  the world may take several. Closing a container with a carried stack hands it back to vanilla.
- `gui key`/`type` go to the current Screen only (no Screen: `-32020`). `type` appends to the
  focused EditBox; `handled:false` means nothing took the characters (no focus, or a disabled box,
  e.g. an anvil with an empty input slot). Ctrl+A first (`gui key --key A --ctrl`) to replace.
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
  `gui click --text <save>`, `gui click --text "Play Selected World"` (~5 s).
- After server-plane writes run `mcdebug gui settle` before looking; clicks settle themselves.
- Read results from data (`gui screen`, the click report, `be.getNbt`), not from pixels alone.
- A Screen that throws while rendering no longer crashes the client: it stops drawing and
  `gui problems` shows the trace.

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
Measured on ic2 (method body): the first compile after an edit ~14 s, repeats 2.5-3.5 s;
hotswap 0.5-0.7 s; a restart costs ~35 s. `attached:true` = this call loaded the hotswap agent
into the JVM, `false` = it was already loaded (normal, not a failure).

- `changed:0` with a stderr note = no newer class files in that dir: wrong dir or wrong compile task.
- A Screen shown by reload-screen is a copy in its own class loader. A later hotswap redefines the
  real class (`skippedReloadCopies` lists the copies) but not that open copy: the `hint` says so;
  run reload-screen again, or close and reopen the GUI to get the real (hotswapped) class.
- Failure exits 2 and redefines nothing (all or nothing).

## 5. Regression

`mcdebug gui screenshot --mode gui --hide-cursor --out new.png`, then
`mcdebug gui compare --expected golden.png --actual new.png --ignore x,y,w,h --report r.html`
(exit 1 on mismatch; `--ignore` is in PNG pixels). Only an explicit `--accept` writes the golden.
`--mode gui` crops to the GUI rectangle, but translucent texture edges still blend with the world
behind it: if two identical shots differ by 1 per channel, use `--channel-tolerance 2`. `--annotate`
with `--out x.png` also writes `x.annotated.png` (copies: `path` stays under
`<gameDir>/mcdebug/screenshots/`, `copiedTo` is yours). `--hash-only` is the cheap "did it change?" probe.

`--draw-log` adds `drawLog: {count, truncated, coordinateSpace, entries[]}`; each entry has `op`
(`blit`/`fill`/`text`/`item`/`tooltip`...), `rect` `[x,y,w,h]` in GUI units, and per op `text`,
`color` (`#AARRGGBB`), `texture`, `uv`. The same text can be drawn more than once (mods often park
the vanilla label off-screen and draw their own): keep entries whose `rect` lies inside the GUI.
`gui problems` covers the whole JVM, startup noise from other mods included: compare `--since`
cursors around your action rather than reading the full list.

## Costs

Each `java -jar` call pays ~330 ms of JVM start: most gui commands take 0.4-0.7 s in total, of
which 40-300 ms is the RPC. Many steps in a row: `mcdebug --session dev --batch -c "..." -c "..."`
(one JVM, ~75 ms per command), `gui act` (action + settle + screenshot + screen in one call), or the
native `mdb`. `reload-resources` takes ~4 s.

## Exit codes and errors

0 ok (including business `ok:false`), 1 usage error, 2 RPC error, 3 method not found (older mod).
`-32602` = bad/missing param (the message names it), `-32603` = server exception (`data.exception`,
`data.at` stack frames), `-32020` = client not ready / not in a world, `-32021` = GUI element not
found (data lists candidates).

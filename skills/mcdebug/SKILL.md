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

Use the first one that exists: `mcdebug` on `PATH`, `mdb` on `PATH` or the server repo's
`tools/mdb` (native build, ~11 ms start), or a cached jar under
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
`./gradlew :cli:copyCliJar` writes `./mcdebug-cli.jar`. The CLI carries the mod jar of its own
version, so `mcdebug jar --output <gameDir>/mods` installs a matching mod (and removes old ones).

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

- Positions are `[x,y,z]`; boxes `{"from":[..],"to":[..]}`, both corners inclusive.
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

- Click targets: `--slot N`, `--widget N`, `--text "Label"` (also matches text drawn in renderBg),
  `--gui X,Y`, `--png X,Y` (pixels of the previous screenshot).
- After server-plane writes run `mcdebug gui settle` before looking; clicks settle themselves.
- Read results from data (`gui screen`, the click report, `be.getNbt`), not from pixels alone.
- A Screen that throws while rendering no longer crashes the client: it stops drawing and
  `gui problems` shows the trace.

## 4. Change code without restarting

```bash
./gradlew classes
mcdebug gui hotswap --classes build/classes/kotlin/client        # method bodies only; all or nothing; re-inits the Screen
mcdebug gui reload-screen --classes build/classes/kotlin/client  # open Screen's own class: any change, same menu
mcdebug gui reload-resources                                     # textures / lang / models
```

Use the output dirs your build really writes (`build/classes/java/main`, `.../kotlin/client`, ...).
Hotswap reports `failed[].changes` when a class gained or lost members: then use reload-screen (for
the Screen class) or restart the session. Neither one reruns constructors of existing objects.

## 5. Regression

`mcdebug gui screenshot --mode gui --hide-cursor --out new.png`, then
`mcdebug gui compare --expected golden.png --actual new.png --ignore x,y,w,h --report r.html`
(exit 1 on mismatch; `--ignore` is in PNG pixels). Only an explicit `--accept` writes the golden.

## Exit codes and errors

0 ok (including business `ok:false`), 1 usage error, 2 RPC error, 3 method not found (older mod).
`-32602` = bad/missing param (the message names it), `-32603` = server exception (`data.exception`,
`data.at` stack frames), `-32020` = client not ready / not in a world, `-32021` = GUI element not
found (data lists candidates).

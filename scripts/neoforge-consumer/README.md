# Isolated NeoForge release-jar consumer

Template only: **copy this project to a new temporary directory before running it** (same rule as
`scripts/perf-consumer/`). It contains no mcdebug source sets or resources, so anything it loads in
`run/mods` is the *published* jar — this is the black-box target used by
`docs/plan-neoforge-verification.md` and by the port's own consumer check.

From the mcdebug repository root:

```bash
REPO="$PWD"
F="$(mktemp -d /tmp/mcneo-XXXXXXXX)"
mkdir -p "$F/consumer"
cp -r scripts/neoforge-consumer/. "$F/consumer/"
cp gradlew gradlew.bat "$F/consumer/"
mkdir -p "$F/consumer/gradle/wrapper"
cp gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties "$F/consumer/gradle/wrapper/"

# 被测 jar 已内嵌 Kotlin 标准库，无需安装 KotlinForForge。
java -jar "$REPO/mcdebug-cli.jar" jar --loader neoforge --output "$F/consumer/run/mods"

printf 'eula=true\nonline-mode=false\nserver-port=0\nspawn-protection=0\nmax-players=1\n' > "$F/consumer/run/eula.txt"
printf 'eula=true\nonline-mode=false\nserver-port=0\nspawn-protection=0\nmax-players=1\n' > "$F/consumer/run/server.properties"

cd "$F/consumer"
./gradlew runServer --console=plain                 # 服务端平面
DISPLAY=:0 ./gradlew runClient --console=plain      # 客户端平面（gui.*，需要 display）
```

Notes:

- Good for a **server** instance (no display, cheap) and for a **client** instance that also hosts the
  integrated server plane (single-player, `--quickPlaySingleplayer <save>` via the project's
  `programArguments`, or driven by `mcdebug session start`).
- Both loader jars bundle Kotlin stdlib; no Kotlin language mod is required.
  Two mcdebug jars in one `mods/` directory abort mod loading —
  `mcdebug jar --loader …` deletes the other loader's jar for you.
- Do not commit a copy of this directory with `run/` in it; the point is a fresh temporary instance.

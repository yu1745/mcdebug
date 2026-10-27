#!/usr/bin/env python3
"""Serial, isolated Linux client benchmark. Standard library only; never builds.

Requires a prebuilt CLI and mod with gui.status gameLoadFinished/overlay/graphics.
Fresh JVM/gameDir does NOT mean cold OS/Gradle/Mesa caches. All artifacts are kept.
CPU 100% means one logical core. RPC screenshot latency includes PNG/file/hash IO.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import traceback

MODES = ("continuous", "demand", "gui-only")
IDENTITY_KEYS = ("pid", "startTime", "instanceId", "sessionToken", "gameDir")
HZ = os.sysconf("SC_CLK_TCK")
PAGE_SIZE = os.sysconf("SC_PAGE_SIZE")
MAX_LINE = 64 * 1024 * 1024


def utc_now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def percentile(values, percent):
    """Linear interpolation, including endpoints; None for empty populations."""
    if not 0 <= percent <= 100:
        raise ValueError("percentile must be in [0,100]")
    if not values:
        return None
    ordered = sorted(values)
    x = (len(ordered) - 1) * percent / 100
    lo, hi = math.floor(x), math.ceil(x)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (x - lo)


def distribution(values):
    return {"n": len(values), "median": percentile(values, 50),
            "p95": percentile(values, 95), "max": max(values) if values else None}


def mode_order(round_index):
    offset = round_index % len(MODES)
    return MODES[offset:] + MODES[:offset]


def parse_proc_stat(text):
    # comm may contain spaces and parentheses, including a closing parenthesis.
    close = text.rfind(")")
    opening = text.find("(")
    if opening < 0 or close < opening:
        raise ValueError("malformed /proc stat")
    pid = int(text[:opening].strip())
    fields = text[close + 1:].split()
    if len(fields) < 22:
        raise ValueError("short /proc stat")
    return {"pid": pid, "comm": text[opening + 1:close], "state": fields[0],
            "cpu_ticks": int(fields[11]) + int(fields[12]),
            "threads": int(fields[17]), "start_ticks": int(fields[19]),
            "rss_pages": int(fields[21])}


def cpu_percent(before, after):
    if before["start_ticks"] != after["start_ticks"] or before["pid"] != after["pid"]:
        raise ValueError("process identity changed")
    seconds = after["monotonic_s"] - before["monotonic_s"]
    ticks = after["cpu_ticks"] - before["cpu_ticks"]
    if seconds <= 0 or ticks < 0:
        raise ValueError("non-monotonic process sample")
    return 100 * ticks / HZ / seconds


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    # Only controller-owned output files; all trials are created exclusively.
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2,
                                     allow_nan=False) + "\n", encoding="utf-8")


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_identity(value, expected=None):
    if not isinstance(value, dict) or any(key not in value for key in IDENTITY_KEYS):
        raise ValueError("incomplete RPC process identity")
    if type(value["pid"]) is not int or value["pid"] <= 0:
        raise ValueError("invalid process PID")
    for key in IDENTITY_KEYS[1:]:
        if not isinstance(value[key], str) or not value[key]:
            raise ValueError("invalid process identity field: " + key)
    dt.datetime.fromisoformat(value["startTime"].replace("Z", "+00:00"))
    if not Path(value["gameDir"]).is_absolute():
        raise ValueError("gameDir must be absolute")
    if expected is not None and any(value[key] != expected[key] for key in IDENTITY_KEYS):
        raise ValueError("RPC process identity changed")
    return {key: value[key] for key in IDENTITY_KEYS}


def validate_socket_path(path):
    path = os.fspath(path)
    if not os.path.isabs(path) or len(os.fsencode(path)) >= 108:
        raise ValueError("Unix socket path must be absolute and <108 bytes: " + path)
    return path


def visually_ready(status):
    return (status.get("inWorld") is True and status.get("singleplayer") is True
            and status.get("gameLoadFinished") is True and status.get("overlay") == ""
            and status.get("screenType") == "none")


class Rpc:
    """One bounded persistent NDJSON connection, used by only one worker at a time."""
    def __init__(self, path, plane, identity, timeout=5):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.buffer = bytearray()
        self.next_id = 0
        try:
            self.sock.settimeout(timeout)
            self.sock.connect(validate_socket_path(path))
            notification = self._read(time.monotonic() + timeout)
            if notification.get("method") != "server.ready":
                raise ValueError("first RPC message is not server.ready")
            params = notification.get("params", {})
            if params.get("plane") != plane or params.get("protocolVersion") != 1:
                raise ValueError("wrong RPC plane/protocol")
            validate_identity(params.get("process"), identity)
            self.ready = notification
        except BaseException:
            self.close()
            raise

    def _read(self, deadline):
        while True:
            newline = self.buffer.find(b"\n")
            if newline >= 0:
                line = bytes(self.buffer[:newline])
                del self.buffer[:newline + 1]
                if line.strip():
                    return json.loads(line.decode("utf-8"))
                continue
            if len(self.buffer) > MAX_LINE:
                raise ValueError("oversize RPC frame")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("RPC read deadline")
            self.sock.settimeout(remaining)
            block = self.sock.recv(65536)
            if not block:
                raise ConnectionError("RPC endpoint closed")
            self.buffer.extend(block)

    def call(self, method, params=None, timeout=30):
        self.next_id += 1
        ident = self.next_id
        deadline = time.monotonic() + timeout
        request = {"jsonrpc": "2.0", "id": ident, "method": method,
                   "params": params or {}}
        try:
            self.sock.settimeout(timeout)
            self.sock.sendall(json.dumps(request).encode("utf-8") + b"\n")
            while True:
                message = self._read(deadline)
                if "method" in message and "id" not in message:
                    continue
                if message.get("id") != ident:
                    raise ValueError("unexpected RPC response id")
                if "error" in message:
                    raise RuntimeError(f"{method}: {message['error']}")
                if "result" not in message:
                    raise ValueError("RPC response lacks result")
                return message["result"]
        except BaseException:
            # A timed-out handler may still run remotely. Never retry blindly.
            self.close()
            raise

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()


def verify_os_start(start_ticks, expected_iso, boot_epoch=None):
    """Cross-check ProcessHandle's ISO epoch against kernel PID creation time."""
    if boot_epoch is None:
        for line in Path("/proc/stat").read_text().splitlines():
            if line.startswith("btime "):
                boot_epoch = int(line.split()[1])
                break
    if boot_epoch is None:
        raise ValueError("kernel boot epoch unavailable")
    expected = dt.datetime.fromisoformat(expected_iso.replace("Z", "+00:00")).timestamp()
    # ProcessHandle/kernel epoch conversion is quantized; never compare PID alone.
    if abs(expected - (boot_epoch + start_ticks / HZ)) > 1.1:
        raise ValueError("PID/startTime does not match kernel process birth")


def proc_sample(pid, expected_start=None):
    root = Path("/proc") / str(pid)
    result = parse_proc_stat((root / "stat").read_text())
    if expected_start is not None and result["start_ticks"] != expected_start:
        raise ValueError("PID was reused")
    if result["state"] == "Z":
        raise ProcessLookupError("process is a zombie")
    status = {}
    for line in (root / "status").read_text().splitlines():
        key, _, val = line.partition(":")
        status[key] = val.strip()
    result.update(monotonic_s=time.perf_counter(),
                  cpu_s=result["cpu_ticks"] / HZ,
                  rss_mib=int(status["VmRSS"].split()[0]) / 1024,
                  hwm_mib=int(status["VmHWM"].split()[0]) / 1024,
                  threads=int(status["Threads"]), fd=len(list((root / "fd").iterdir())))
    # Re-read identity after the non-atomic status/fd reads.
    if parse_proc_stat((root / "stat").read_text())["start_ticks"] != result["start_ticks"]:
        raise ValueError("PID changed during sample")
    return result


def sanitized_environment(display, idle_fps):
    removed = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS",
               "GRADLE_OPTS", "CLASSPATH"}
    env = {key: value for key, value in os.environ.items()
           if key not in removed and not key.startswith("MCDEBUG_")}
    env.update(DISPLAY=display, LIBGL_ALWAYS_SOFTWARE="true", GALLIUM_DRIVER="llvmpipe",
               LP_NUM_THREADS="4", MCDEBUG_CLIENT_LOOP_FPS="20",
               MCDEBUG_CLIENT_IDLE_FPS=str(idle_fps))
    return env


def positive_float(text):
    value = float(text)
    if not math.isfinite(value) or value <= 0:
        raise argparse.ArgumentTypeError("must be finite and >0")
    return value


def nonnegative_float(text):
    value = float(text)
    if not math.isfinite(value) or value < 0:
        raise argparse.ArgumentTypeError("must be finite and >=0")
    return value


def positive_int(text):
    value = int(text)
    if value <= 0:
        raise argparse.ArgumentTypeError("must be >0")
    return value


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("project", "cli", "mod-jar", "source-world", "out"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--display", required=True)
    p.add_argument("--rounds", type=positive_int, default=3)
    p.add_argument("--warmup-seconds", type=nonnegative_float, default=30)
    p.add_argument("--idle-seconds", type=positive_float, default=20)
    p.add_argument("--shots", type=positive_int, default=20)
    p.add_argument("--shot-interval", type=positive_float, default=1)
    p.add_argument("--idle-fps", type=int, choices=range(0, 21), default=1)
    p.add_argument("--startup-timeout", type=positive_float, default=180)
    return p


def validate_args(args):
    if platform.system() != "Linux" or not hasattr(time, "CLOCK_BOOTTIME"):
        raise ValueError("Linux /proc and CLOCK_BOOTTIME are required")
    for name in ("project", "cli", "mod_jar", "source_world", "out"):
        setattr(args, name, getattr(args, name).expanduser().resolve())
    if not args.project.is_dir() or not (args.project / "gradlew").is_file():
        raise ValueError("--project must contain gradlew")
    if not args.cli.is_file() or not args.mod_jar.is_file():
        raise ValueError("--cli and --mod-jar must be existing jar files")
    if not args.source_world.is_dir() or not (args.source_world / "level.dat").is_file():
        raise ValueError("--source-world must be an existing save containing level.dat")
    if args.out.exists():
        raise ValueError("--out already exists; refusing to overwrite trials")
    if not args.display.strip():
        raise ValueError("--display must not be empty")
    if args.out == args.source_world or args.source_world in args.out.parents:
        raise ValueError("--out must not be inside the source world")
    if shutil.which("java") is None:
        raise ValueError("java is not on PATH")
    return args


class Trial:
    def __init__(self, args, directory, round_index, mode):
        self.args, self.directory, self.mode = args, directory, mode
        self.name = directory.name
        self.registry = args.out / "registry"
        self.game = None
        self.client = self.server = self.launcher = None
        self.launch_stdout = None
        self.identities = {}
        self.starts = {}
        self.last_samples = {}
        self.next_sample = 0.0
        self.origin = None
        self.cancel = threading.Event()
        self.worker_threads = []
        self.owns_directory = False
        self.data = {"schemaVersion": 1, "trial": self.name, "round": round_index + 1,
                     "mode": mode, "utc": utc_now(), "timings_s": {}, "events": [],
                     "proc_samples": {"mc": [], "wrapper": []}, "stages": {},
                     "shots": [], "errors": [], "cleanup": {}, "status": "preparing"}

    def elapsed(self):
        return time.perf_counter() - self.origin if self.origin is not None else None

    def event(self, message):
        event = {"elapsed_s": self.elapsed(), "utc": utc_now(), "message": message}
        self.data["events"].append(event)
        print(f"[{self.name}] {message}", flush=True)

    def mark(self, name):
        self.data["timings_s"].setdefault(name, self.elapsed())

    def persist(self):
        write_json(self.directory / "result.json", self.data)

    def prepare(self):
        self.directory.mkdir(exist_ok=False)
        self.owns_directory = True
        # Short path independently of --out; kept even on failure for diagnosis.
        self.game = Path(tempfile.mkdtemp(prefix="mdbp-", dir="/tmp")).resolve()
        self.data["gameDir"] = str(self.game)
        for filename in ("clientSocket", "socket"):
            validate_socket_path(self.game / "mcdebug" / filename)
        shutil.copytree(self.args.source_world, self.game / "saves" / "PerfWorld",
                        ignore=shutil.ignore_patterns("session.lock"))
        (self.game / "mods").mkdir()
        shutil.copy2(self.args.mod_jar, self.game / "mods" / self.args.mod_jar.name)
        (self.game / "config").mkdir()
        write_json(self.game / "config" / "mcdebug.json", {
            "clientRenderMode": self.mode, "clientLoopFps": 20,
            "clientIdleFps": self.args.idle_fps, "tcpEnabled": False,
            "clientTcpEnabled": False})
        (self.game / "options.txt").write_text(
            "overrideWidth:854\noverrideHeight:480\nguiScale:2\nrenderDistance:2\n"
            "simulationDistance:5\nmaxFps:60\nenableVsync:false\npauseOnLostFocus:false\n"
            "tutorialStep:none\nsoundCategory_master:0.0\n", encoding="utf-8")
        self.env = sanitized_environment(self.args.display, self.args.idle_fps)
        self.data["profile_environment"] = {k: self.env[k] for k in
            ("DISPLAY", "LIBGL_ALWAYS_SOFTWARE", "GALLIUM_DRIVER", "LP_NUM_THREADS",
             "MCDEBUG_CLIENT_LOOP_FPS", "MCDEBUG_CLIENT_IDLE_FPS")}
        self.persist()

    def cli_command(self, *command):
        return [shutil.which("java"), "-jar", str(self.args.cli),
                "--sessions-dir", str(self.registry), *command]

    def metadata(self):
        record_path = self.registry / (self.name + ".json")
        if record_path.exists():
            record = read_json(record_path)
            if record.get("name") != self.name or record.get("gameDir") != str(self.game) or record.get("owned") is not True:
                raise ValueError("registry identity/configuration mismatch")
            self.data["session_record"] = record
            if record.get("wrapper") and "wrapper" not in self.identities:
                self.identities["wrapper"] = record["wrapper"]
                self.mark("wrapper_discovered")
        discovered = self.game / "mcdebug" / "client.json"
        if discovered.exists():
            value = read_json(discovered)
            expected = self.data.get("session_record", {})
            process = validate_identity(value.get("process"))
            if process["sessionToken"] != expected.get("sessionToken") or process["gameDir"] != str(self.game):
                raise ValueError("client discovery is not this owned session")
            validate_socket_path(value.get("clientSocket", ""))
            if "mc" not in self.identities:
                self.identities["mc"] = process
                self.data["process"] = process
                self.data["client_discovery"] = value
                self.mark("mc_discovered")
            else:
                validate_identity(process, self.identities["mc"])
        if self.launcher is not None and "session_cli_return" not in self.data["timings_s"]:
            code = self.launcher.poll()
            if code is not None:
                self.mark("session_cli_return")
                self.data["session_cli_exit_code"] = code
                self.event(f"session start returned {code}")
                if code != 0:
                    raise RuntimeError(f"session start failed ({code}); inspect start-cli.log")

    def sample(self, force=False):
        now = time.perf_counter()
        if not force and now < self.next_sample:
            return
        self.next_sample = now + 1
        for label, identity in list(self.identities.items()):
            try:
                value = proc_sample(identity["pid"], self.starts.get(label))
                if label not in self.starts:
                    verify_os_start(value["start_ticks"], identity["startTime"])
                    self.starts[label] = value["start_ticks"]
                    boot_now = time.clock_gettime(time.CLOCK_BOOTTIME)
                    mono_now = time.perf_counter()
                    created = mono_now - (boot_now - value["start_ticks"] / HZ)
                    self.data["timings_s"][label + "_process_created"] = created - self.origin
                    self.data.setdefault("process_clock", {})[label] = {
                        "pid": identity["pid"], "start_ticks": value["start_ticks"],
                        "hz": HZ, "resolution_s": 1 / HZ,
                        "note": "process birth, not Java main; current BOOTTIME/monotonic mapping"}
                previous = self.last_samples.get(label)
                value["elapsed_s"] = value["monotonic_s"] - self.origin
                if previous:
                    value["cpu_percent_one_core_100"] = cpu_percent(previous, value)
                self.last_samples[label] = value
                self.data["proc_samples"][label].append(value)
            except (FileNotFoundError, ProcessLookupError):
                self.data.setdefault("process_unavailable", []).append({"label": label, "elapsed_s": self.elapsed()})
                if label == "mc" and self.data["status"] == "running":
                    raise RuntimeError("MC process exited during measurement")

    def pump(self):
        self.metadata()
        self.sample()

    def worker(self, function, timeout):
        box = {}
        def run():
            try:
                box["value"] = function()
            except BaseException as error:
                box["error"] = error
        thread = threading.Thread(target=run, name="benchmark-rpc", daemon=True)
        self.worker_threads = [old for old in self.worker_threads if old.is_alive()]
        self.worker_threads.append(thread)
        thread.start()
        deadline = time.monotonic() + timeout
        while thread.is_alive():
            self.pump()
            if time.monotonic() >= deadline:
                self.cancel.set()
                for rpc in (self.client, self.server):
                    if rpc:
                        rpc.close()
                raise TimeoutError("controller worker deadline")
            thread.join(0.05)
        if "error" in box:
            raise box["error"]
        return box.get("value")

    def pause(self, seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            self.pump()
            time.sleep(min(0.1, max(0, deadline - time.monotonic())))

    def rpc(self, plane, method, params=None, timeout=30):
        connection = self.client if plane == "client" else self.server
        return self.worker(lambda: connection.call(method, params, timeout), timeout + 2)

    def startup(self):
        command = self.cli_command("session", "start", self.name, "--project", str(self.args.project),
            "--game-dir", str(self.game), "--world", "PerfWorld", "--render", self.mode,
            "--display", self.args.display, "--width", "854", "--height", "480",
            "--wait-seconds", str(math.ceil(self.args.startup_timeout)))
        self.data["launch_command"] = command
        self.launch_stdout = (self.directory / "start-cli.log").open("wb")
        self.origin = time.perf_counter()
        self.data["start_utc"] = utc_now()
        self.data["timings_s"]["cli_launch"] = 0.0
        self.launcher = subprocess.Popen(command, cwd=self.args.project, env=self.env,
            stdin=subprocess.DEVNULL, stdout=self.launch_stdout, stderr=subprocess.STDOUT)
        self.data["controller_cli_pid"] = self.launcher.pid
        self.data["status"] = "running"
        self.event("startup")
        deadline = time.monotonic() + self.args.startup_timeout

        def probe():
            while "mc" not in self.identities:
                if self.cancel.wait(0.05) or time.monotonic() >= deadline:
                    raise TimeoutError("no owned client discovery")
            if self.cancel.is_set():
                raise InterruptedError("startup cancelled")
            expected = self.identities["mc"]
            socket_path = self.data["client_discovery"]["clientSocket"]
            self.client = Rpc(socket_path, "client", expected,
                              timeout=min(5, max(0.01, deadline - time.monotonic())))
            self.mark("client_rpc_ready")
            while time.monotonic() < deadline and not self.cancel.is_set():
                status = self.client.call("gui.status", timeout=min(30, max(0.01, deadline - time.monotonic())))
                validate_identity(status.get("process"), expected)
                self.mark("first_gui_status")
                self.data.setdefault("startup_status_samples", []).append({"elapsed_s": self.elapsed(), "status": status})
                self.data.setdefault("first_gui_status", status)
                if status.get("inWorld") is True and status.get("singleplayer") is True:
                    self.mark("world_ready")
                    self.data.setdefault("first_world_status", status)
                if visually_ready(status):
                    self.mark("visually_ready")
                    self.data["visually_ready_status"] = status
                    if not isinstance(status.get("graphics"), dict):
                        raise ValueError("new gui.status.graphics field required")
                    self.server = Rpc(self.game / "mcdebug" / "socket", "server", expected,
                                      timeout=min(5, max(0.01, deadline - time.monotonic())))
                    self.mark("server_rpc_ready")
                    return
                self.cancel.wait(min(0.2, max(0, deadline - time.monotonic())))
            raise TimeoutError("visual world readiness deadline")
        self.worker(probe, self.args.startup_timeout + 1)
        self.event("world visually ready; requesting first window PNG")
        self.data["first_window_shot"] = self.screenshot("window", "first-window")
        self.data["timings_s"].update(first_window_request=self.data["first_window_shot"]["request_s"],
                                      first_window_response=self.data["first_window_shot"]["response_s"])
        # Stop SessionManager's own probes before stable performance windows.
        self.worker(lambda: self.launcher.wait(timeout=max(1, deadline - time.monotonic())),
                    max(2, deadline - time.monotonic() + 1))
        self.metadata()
        self.persist()

    def screenshot(self, mode, name):
        sample = {"mode": mode, "name": name, "request_s": self.elapsed()}
        try:
            response = self.rpc("client", "gui.screenshot", {
                "mode": mode, "inline": False, "delayTicks": 0, "name": name})
            sample["response_s"] = self.elapsed()
            sample["latency_ms"] = (sample["response_s"] - sample["request_s"]) * 1000
            sample["result"] = response
            path = Path(response["path"]).resolve()
            if not path.is_relative_to(self.game / "mcdebug" / "screenshots"):
                raise ValueError("screenshot path escapes owned gameDir")
            if path.stat().st_size != response["bytes"] or sha256(path) != response["sha256"]:
                raise ValueError("screenshot byte count/hash mismatch")
            with path.open("rb") as png:
                if png.read(8) != b"\x89PNG\r\n\x1a\n":
                    raise ValueError("not a PNG")
            sample["verified"] = True
            return sample
        except BaseException as error:
            sample["error"] = str(error)
            self.data.setdefault("failed_shots", []).append(sample)
            raise

    def boundary(self):
        client = self.rpc("client", "gui.status")
        client_received = time.perf_counter()
        validate_identity(client.get("process"), self.identities["mc"])
        if client.get("paused") is not False:
            raise ValueError("benchmark client is paused")
        render = client.get("render", {})
        if (render.get("mode") != self.mode or render.get("loopFps") != 20
                or render.get("idleFps") != self.args.idle_fps):
            raise ValueError("effective render configuration does not match trial")
        window = client.get("window", {})
        if (window.get("width"), window.get("height"), window.get("guiScale")) != (854, 480, 2):
            raise ValueError("effective window/guiScale does not match fixed profile")
        server = self.rpc("server", "server.status")
        received = time.perf_counter()
        health = self.rpc("server", "server.health")
        self.sample(force=True)
        return {"elapsed_s": received - self.origin,
                "client_received_s": client_received - self.origin, "client": client,
                "server": server, "health": health}

    def measure(self, name, body):
        self.event(name)
        before = self.boundary()
        expected_screen = "none" if name == "world_idle" else "container"
        if before["client"].get("screenType") != expected_screen:
            raise ValueError("unexpected screen at stage start: " + name)
        self.data["stages"][name] = {"before": before, "complete": False}
        self.persist()
        body()
        after = self.boundary()
        seconds = after["elapsed_s"] - before["elapsed_s"]
        client_seconds = after["client_received_s"] - before["client_received_s"]
        if after["client"].get("screenType") != expected_screen:
            raise ValueError("screen changed during measured stage: " + name)
        stage = self.data["stages"][name]
        stage.update(after=after, complete=True, duration_s=seconds,
                     natural_tps=(after["server"]["tick"] - before["server"]["tick"]) / seconds,
                     client_interval_s=client_seconds,
                     client_ticks_per_s=(after["client"]["clientTick"] - before["client"]["clientTick"]) / client_seconds,
                     render_frames_per_s=(after["client"]["render"]["frames"] - before["client"]["render"]["frames"]) / client_seconds,
                     skipped_frames_per_s=(after["client"]["render"]["skippedFrames"] - before["client"]["render"]["skippedFrames"]) / client_seconds)
        # /proc endpoints bracket the window; no request-driven idle frames.
        stage["process"] = {}
        for label, values in self.data["proc_samples"].items():
            samples = [v for v in values if before["elapsed_s"] <= v["elapsed_s"] <= after["elapsed_s"]]
            if len(samples) < 2:
                stage["process"][label] = {"n": len(samples), "note": "insufficient process samples"}
                continue
            cpu_s = samples[-1]["cpu_s"] - samples[0]["cpu_s"]
            elapsed = samples[-1]["monotonic_s"] - samples[0]["monotonic_s"]
            stage["process"][label] = {"n": len(samples), "sample_duration_s": elapsed,
                "cpu_s": cpu_s, "cpu_percent_one_core_100": 100 * cpu_s / elapsed,
                **{key: distribution([v[key] for v in samples]) for key in
                   ("rss_mib", "hwm_mib", "threads", "fd")}}
        self.persist()

    def measurements(self):
        self.event(f"fixed warmup {self.args.warmup_seconds}s")
        self.pause(self.args.warmup_seconds)
        self.measure("world_idle", lambda: self.pause(self.args.idle_seconds))
        status = self.rpc("client", "gui.status")
        if status.get("screenType") != "none":
            raise ValueError("world-view idle did not remain on no-screen view")
        pos = status["pos"]
        chest = [math.floor(pos[0]) + 1, math.floor(pos[1]), math.floor(pos[2])]
        dimension = status["world"]
        result = self.rpc("server", "server.runCommand", {
            "command": "/setblock " + " ".join(map(str, chest)) + " minecraft:chest", "dim": dimension})
        self.data["chest"] = {"pos": chest, "dimension": dimension, "command_result": result}
        if result.get("success") is not True:
            raise RuntimeError("setblock failed: " + str(result))
        opened = self.rpc("client", "gui.openBlock", {
            "pos": chest, "dimension": dimension, "clearHand": True, "waitTicks": 100})
        self.data["open_block"] = opened
        state = self.rpc("client", "gui.status")
        if state.get("screenType") != "container" or state.get("paused") is not False:
            raise ValueError("chest GUI did not open unpaused")
        self.data["first_gui_shot"] = self.screenshot("gui", "first-gui")
        self.event("GUI stabilization 5s")
        self.pause(5)
        self.measure("gui_idle", lambda: self.pause(self.args.idle_seconds))

        def shots():
            next_due = time.monotonic()
            last_frame = self.data["first_gui_shot"]["result"]["frameId"]
            for index in range(self.args.shots):
                self.pause(max(0, next_due - time.monotonic()))
                lateness = max(0, time.monotonic() - next_due)
                shot = self.screenshot("gui", f"shot-{index:04d}")
                shot["schedule_lateness_s"] = lateness
                shot["index"] = index
                shot["encoding_warmup"] = index < 5
                self.data["shots"].append(shot)
                if shot["result"]["frameId"] <= last_frame:
                    raise ValueError("sequential screenshot frameId did not increase")
                last_frame = shot["result"]["frameId"]
                next_due += self.args.shot_interval
                self.persist()
        self.measure("gui_screenshots", shots)
        all_shots = self.data["shots"]
        self.data["shot_latency_ms"] = {
            "all": distribution([s["latency_ms"] for s in all_shots]),
            "first_five": distribution([s["latency_ms"] for s in all_shots[:5]]),
            "after_first_five": distribution([s["latency_ms"] for s in all_shots[5:]]),
            "errors": len(self.data.get("failed_shots", []))}

    def cleanup(self):
        self.cancel.set()
        for connection in (self.client, self.server):
            if connection:
                connection.close()
        for thread in self.worker_threads:
            thread.join(timeout=1)
        # A constructor may have finished connecting while cancellation was set.
        for connection in (self.client, self.server):
            if connection:
                connection.close()
        if self.launcher is None:
            self.data["cleanup"] = {"ok": True, "note": "no launch attempted"}
            return
        self.event("normal owned session stop")
        command = self.cli_command("session", "stop", self.name, "--wait-seconds", "30")
        result = {"command": command, "ok": False}
        try:
            with (self.directory / "stop-cli.log").open("wb") as output:
                completed = subprocess.run(command, cwd=self.args.project, env=self.env,
                    stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT, timeout=60)
            result["exit_code"] = completed.returncode
            deadline = time.monotonic() + 5
            alive = None
            sockets = []
            while True:
                discovery = self.game / "mcdebug" / "client.json"
                identity = self.identities.get("mc")
                if identity is None and discovery.exists():
                    found = validate_identity(read_json(discovery).get("process"))
                    record = read_json(self.registry / (self.name + ".json"))
                    if found["sessionToken"] != record["sessionToken"] or found["gameDir"] != str(self.game):
                        raise ValueError("late cleanup identity mismatch")
                    identity = found
                    self.identities["mc"] = found
                alive = False
                if identity:
                    try:
                        sample = proc_sample(identity["pid"], self.starts.get("mc"))
                        verify_os_start(sample["start_ticks"], identity["startTime"])
                        alive = True
                        result["live_start_ticks"] = sample["start_ticks"]
                    except (FileNotFoundError, ProcessLookupError):
                        alive = False
                sockets = [str(self.game / "mcdebug" / name) for name in ("socket", "clientSocket")
                           if (self.game / "mcdebug" / name).exists()]
                if not alive and not sockets or time.monotonic() >= deadline:
                    break
                time.sleep(0.1)
            result.update(mc_alive=alive, remaining_sockets=sockets)
            # Unknown late bootstrap is not safe confirmation of termination.
            final_record = read_json(self.registry / (self.name + ".json"))
            result["session_state"] = final_record.get("state")
            result["ok"] = (completed.returncode == 0 and not alive and not sockets
                            and final_record.get("state") == "stopped")
        except BaseException as error:
            result["error"] = str(error)
        finally:
            # Only our direct Java CLI process is bounded here, never its children/daemon.
            if self.launcher.poll() is None:
                self.launcher.terminate()
                try:
                    self.launcher.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    self.launcher.kill()
                    self.launcher.wait(timeout=3)
            if self.launch_stdout:
                self.launch_stdout.close()
        self.data["cleanup"] = result

    def run(self):
        try:
            self.prepare()
            self.startup()
            self.measurements()
            self.data["status"] = "success"
        except BaseException as error:
            self.data["status"] = "failed"
            self.data["errors"].append({"type": type(error).__name__, "message": str(error),
                                        "traceback": traceback.format_exc(), "elapsed_s": self.elapsed()})
            self.event(f"FAILED: {error}")
        finally:
            try:
                self.cleanup()
            except BaseException as error:
                self.data["cleanup"] = {"ok": False, "error": str(error)}
            if not self.data["cleanup"].get("ok"):
                self.data["status"] = "failed"
            self.data["end_utc"] = utc_now()
            if self.owns_directory:
                self.persist()
        return self.data


def main(argv=None):
    p = parser()
    try:
        args = validate_args(p.parse_args(argv))
    except ValueError as error:
        p.error(str(error))
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / "registry").mkdir()
    manifest = {"schemaVersion": 1, "utc": utc_now(), "argv": sys.argv,
        "arguments": {key: str(value) if isinstance(value, Path) else value
                      for key, value in vars(args).items()},
        "system": {"platform": platform.platform(), "python": platform.python_version(),
                   "logical_cpus": os.cpu_count(), "clock_ticks_per_s": HZ,
                   "page_size": PAGE_SIZE},
        "inputs": {"cli_sha256": sha256(args.cli), "mod_sha256": sha256(args.mod_jar),
                   "source_level_dat_sha256": sha256(args.source_world / "level.dat")},
        "methodology": ["fresh JVM + fresh isolated gameDir; shared caches warm/uncontrolled, not cold page cache",
            "CPU 100% = one logical core; MC includes integrated server, JIT, IO and software GL",
            "wrapper only, no shared Gradle daemon discovery or attribution",
            "screenshot RPC latency includes new frame, readback, PNG encoding, file write, hash and response",
            "natural TPS from server.status.tick / monotonic reception interval, not server.health.tps",
            "no screenshots during idle; state/health at boundaries only; /proc sampled about 1Hz",
            "all gameDirs and logs retained; first five repeated screenshots labelled, never deleted",
            "continuous maxFps=60 vs lite outer-loop cap20: product policy comparison, not matched-cap renderer isolation",
            "startup peak threads/FD before process discovery unavailable; VmHWM is OS memory high-water mark"],
        "trials": []}
    write_json(args.out / "manifest.json", manifest)
    previous_handler = signal.getsignal(signal.SIGTERM)
    def interrupted(signum, frame):
        raise KeyboardInterrupt("SIGTERM")
    signal.signal(signal.SIGTERM, interrupted)
    try:
        for round_index in range(args.rounds):
            for index, mode in enumerate(mode_order(round_index)):
                name = f"r{round_index + 1:02d}-{index + 1}-{mode}"
                trial = Trial(args, args.out / name, round_index, mode)
                data = trial.run()
                manifest["trials"].append({"name": name, "mode": mode, "status": data["status"],
                    "result": str(trial.directory / "result.json"), "gameDir": data.get("gameDir"),
                    "timings_s": data["timings_s"], "shot_latency_ms": data.get("shot_latency_ms"),
                    "cleanup_ok": data["cleanup"].get("ok")})
                write_json(args.out / "manifest.json", manifest)
                if data["status"] != "success":
                    print("Stopping after failed trial; preserve artifacts and inspect cleanup before retry.", file=sys.stderr)
                    return 1
        return 0
    finally:
        signal.signal(signal.SIGTERM, previous_handler)


if __name__ == "__main__":
    sys.exit(main())

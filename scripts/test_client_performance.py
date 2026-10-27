"""No Gradle, Minecraft, display or third-party packages required."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import socket
import tempfile
import threading
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("client_performance", Path(__file__).with_name("client-performance.py"))
bench = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bench)


def identity(game="/tmp/owned"):
    return {"pid": 123, "startTime": "2026-01-01T00:00:00Z", "instanceId": "uuid",
            "sessionToken": "token", "gameDir": game}


class MetricsTest(unittest.TestCase):
    def test_proc_stat_comm_with_spaces_parentheses(self):
        # Fields 3..24; the final ')' belongs to comm, not an embedded pair.
        fields = ["S"] + ["0"] * 21
        fields[11], fields[12] = "250", "150"
        fields[17], fields[19], fields[21] = "13", "999", "4096"
        parsed = bench.parse_proc_stat("42 (name (nested) odd)) " + " ".join(fields))
        self.assertEqual(parsed["pid"], 42)
        self.assertEqual(parsed["comm"], "name (nested) odd)")
        self.assertEqual(parsed["cpu_ticks"], 400)
        self.assertEqual(parsed["threads"], 13)
        self.assertEqual(parsed["start_ticks"], 999)
        self.assertEqual(parsed["rss_pages"], 4096)
        for invalid in ("junk", "1 (comm) S 0"):
            with self.assertRaises(ValueError):
                bench.parse_proc_stat(invalid)

    def test_cpu_one_core_is_one_hundred_and_multicore_exceeds_it(self):
        before = dict(pid=1, start_ticks=2, cpu_ticks=0, monotonic_s=1)
        after = dict(pid=1, start_ticks=2, cpu_ticks=bench.HZ * 4, monotonic_s=3)
        self.assertEqual(bench.cpu_percent(before, after), 200)
        after["start_ticks"] = 3
        with self.assertRaises(ValueError):
            bench.cpu_percent(before, after)

    def test_kernel_start_time_cross_check(self):
        ticks = 5 * bench.HZ
        bench.verify_os_start(ticks, "1970-01-01T00:16:45Z", boot_epoch=1000)
        with self.assertRaises(ValueError):
            bench.verify_os_start(ticks, "1970-01-01T00:17:45Z", boot_epoch=1000)

    def test_trial_refuses_existing_directory_without_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            existing = root / "trial"
            existing.mkdir()
            sentinel = existing / "result.json"
            sentinel.write_text("original")
            args = bench.argparse.Namespace(out=root)
            trial = bench.Trial(args, existing, 0, "demand")
            with contextlib.redirect_stdout(io.StringIO()):
                result = trial.run()
            self.assertEqual(result["status"], "failed")
            self.assertEqual(sentinel.read_text(), "original")

    def test_percentile_interpolation_and_empty(self):
        self.assertIsNone(bench.percentile([], 95))
        self.assertEqual(bench.percentile([10], 95), 10)
        self.assertEqual(bench.percentile([0, 100], 95), 95)
        self.assertEqual(bench.percentile([3, 1, 2], 50), 2)
        with self.assertRaises(ValueError):
            bench.percentile([1], 101)

    def test_latin_rotations(self):
        self.assertEqual(bench.mode_order(0), ("continuous", "demand", "gui-only"))
        self.assertEqual(bench.mode_order(1), ("demand", "gui-only", "continuous"))
        self.assertEqual(bench.mode_order(2), ("gui-only", "continuous", "demand"))
        self.assertEqual(bench.mode_order(3), bench.mode_order(0))

    def test_visual_readiness_is_not_inworld_only(self):
        status = dict(inWorld=True, singleplayer=True, gameLoadFinished=True,
                      overlay="", screenType="none")
        self.assertTrue(bench.visually_ready(status))
        for key, value in (("overlay", "LoadingOverlay"), ("gameLoadFinished", False),
                           ("screenType", "ReceivingLevelScreen"), ("singleplayer", False)):
            self.assertFalse(bench.visually_ready(dict(status, **{key: value})))
        self.assertFalse(bench.visually_ready(dict(inWorld=True, singleplayer=True)))

    def test_identity_requires_every_field_and_exact_match(self):
        good = identity()
        self.assertEqual(bench.validate_identity(good, good), good)
        for key in bench.IDENTITY_KEYS:
            bad = dict(good)
            del bad[key]
            with self.assertRaises(ValueError):
                bench.validate_identity(bad)
        for key, value in (("pid", 124), ("instanceId", "new"), ("sessionToken", "new"),
                           ("gameDir", "/tmp/other"), ("startTime", "2026-01-02T00:00:00Z")):
            with self.assertRaises(ValueError):
                bench.validate_identity(dict(good, **{key: value}), good)

    def test_socket_path_bytes_not_characters(self):
        self.assertEqual(bench.validate_socket_path("/tmp/test.sock"), "/tmp/test.sock")
        for path in ("relative.sock", "/" + "x" * 107, "/tmp/" + "中" * 40):
            with self.assertRaises(ValueError):
                bench.validate_socket_path(path)

    def test_environment_removes_inherited_overrides(self):
        with patch.dict(bench.os.environ, {"MCDEBUG_SOCKET": "/wrong", "JAVA_TOOL_OPTIONS": "wrong",
                                           "GRADLE_OPTS": "wrong", "KEEP_ME": "yes"}, clear=True):
            env = bench.sanitized_environment(":99", 0)
        self.assertNotIn("MCDEBUG_SOCKET", env)
        self.assertNotIn("JAVA_TOOL_OPTIONS", env)
        self.assertNotIn("GRADLE_OPTS", env)
        self.assertEqual(env["KEEP_ME"], "yes")
        self.assertEqual(env["MCDEBUG_CLIENT_IDLE_FPS"], "0")
        self.assertEqual(env["LP_NUM_THREADS"], "4")


class ArgumentTest(unittest.TestCase):
    def arguments(self, root):
        return ["--project", str(root / "project"), "--cli", str(root / "cli.jar"),
                "--mod-jar", str(root / "mod.jar"), "--source-world", str(root / "world"),
                "--out", str(root / "output"), "--display", ":99"]

    def test_defaults_and_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "project").mkdir()
            (root / "project" / "gradlew").write_text("placeholder")
            (root / "world").mkdir()
            (root / "world" / "level.dat").write_bytes(b"placeholder")
            for jar in ("cli.jar", "mod.jar"):
                (root / jar).write_bytes(b"placeholder")
            with patch.object(bench.shutil, "which", return_value="/usr/bin/java"):
                args = bench.validate_args(bench.parser().parse_args(self.arguments(root)))
                self.assertEqual(args.rounds, 3)
                self.assertEqual(args.shots, 20)
                self.assertEqual(args.warmup_seconds, 30)
                (root / "output").mkdir()
                with self.assertRaises(ValueError):
                    bench.validate_args(args)

    def test_invalid_numeric_flags(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stderr(io.StringIO()):
            base = self.arguments(Path(tmp))
            for flag, value in (("--rounds", "0"), ("--shots", "-1"),
                                ("--warmup-seconds", "nan"), ("--idle-seconds", "0"),
                                ("--shot-interval", "inf"), ("--idle-fps", "21"),
                                ("--startup-timeout", "-1")):
                with self.assertRaises(SystemExit):
                    bench.parser().parse_args(base + [flag, value])


class UnixRpcTest(unittest.TestCase):
    def exchange(self, plane="client", corrupt_identity=False):
        directory = tempfile.TemporaryDirectory(prefix="mdbp-test-")
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "sock"
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(listener.close)
        listener.bind(str(path))
        listener.listen(1)
        errors = []
        def server():
            try:
                connection, _ = listener.accept()
                with connection:
                    connection.settimeout(2)
                    process = identity()
                    if corrupt_identity:
                        process["instanceId"] = "replaced"
                    message = {"jsonrpc": "2.0", "method": "server.ready", "params": {
                        "plane": plane, "protocolVersion": 1, "process": process}}
                    connection.sendall(json.dumps(message).encode() + b"\n")
                    if plane != "client" or corrupt_identity:
                        return
                    line = bytearray()
                    while b"\n" not in line:
                        line.extend(connection.recv(4096))
                    request = json.loads(line)
                    connection.sendall(b'{"jsonrpc":"2.0","method":"notice","params":{}}\n')
                    response = {"jsonrpc": "2.0", "id": request["id"], "result": {"text": "中文"}}
                    raw = (json.dumps(response, ensure_ascii=False) + "\n").encode()
                    connection.sendall(raw[:12])
                    connection.sendall(raw[12:])
            except BaseException as error:
                errors.append(error)
        thread = threading.Thread(target=server, daemon=True)
        thread.start()
        return path, thread, errors

    def test_handshake_notification_fragmentation_and_unicode(self):
        path, thread, errors = self.exchange()
        rpc = bench.Rpc(path, "client", identity(), timeout=2)
        try:
            self.assertEqual(rpc.call("gui.status", timeout=2), {"text": "中文"})
        finally:
            rpc.close()
        thread.join(2)
        self.assertFalse(thread.is_alive())
        self.assertEqual(errors, [])

    def test_reject_plane_and_identity_mismatch(self):
        for kwargs in ({"plane": "server"}, {"corrupt_identity": True}):
            path, thread, errors = self.exchange(**kwargs)
            with self.assertRaises(ValueError):
                bench.Rpc(path, "client", identity(), timeout=2)
            thread.join(2)
            self.assertFalse(thread.is_alive())
            self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()

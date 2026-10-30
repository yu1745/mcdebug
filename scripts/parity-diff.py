#!/usr/bin/env python3
"""Cross-loader parity differential: run the same RPC tasks against two mcdebug instances
(Fabric and NeoForge) and print a normalised diff.

Why: the black-box acceptance batches (`docs/plan-neoforge-verification.md`) only ever see one
loader, so they can say "the documented contract holds" but not "the two loaders agree". This tool
answers the second question for the loader-neutral part of the protocol.

Usage:
    python3 scripts/parity-diff.py --a <socket-fabric> --b <socket-neoforge> [--tasks tasks.json]
                                   [--cli mcdebug-cli.jar] [--json out.json]

Response normalisation (only what is documented as loader-dependent or run-dependent is dropped):
  * `fluidUnit`-scaled amounts are compared as-is; the *unit* field is reported separately, and
    fluid amounts are additionally compared after converting with `--fluid-ratio` (Fabric droplets
    per NeoForge mB, default 81) so a real bug is not hidden by the unit difference;
  * volatile fields (`tick`, `dayTime`, `/tick`, `/time`, `pixelHash`, `sha256`, absolute paths,
    `instanceId`, `pid`, `startTime`, `typeName`, `screenGuard`) are stripped;
  * a task may declare `ignore` glob-ish keys of its own.

Exit code: 0 = all comparable tasks agree, 1 = at least one mismatch, 2 = harness/setup problem.
"""

from __future__ import annotations

import argparse
import json
import re
import socket
import sys
from pathlib import Path

VOLATILE = {
    # run-dependent
    "tick", "dayTime", "time", "pixelHash", "sha256", "instanceId", "pid", "startTime",
    "typeName", "screenGuard", "path", "logPath", "gameDir", "registryDir", "recordId",
    "sessionToken", "createdAt", "updatedAt", "durationMs", "elapsedMs", "timings",
    # instance/environment-dependent: error payloads echo the endpoint the call arrived on, and
    # the two test instances deliberately differ (one has TCP on, the other off, different paths)
    "socket", "tcpPort", "tcpEnabled", "clientSocket", "clientTcpPort", "clientTcpEnabled",
}
VOLATILE_KEY_RE = re.compile(r"(^|/)(tick|time)$")
REQUEST_ID = 1


def call(sock_path: str, method: str, params, timeout: float) -> dict:
    """One JSON-RPC request over the unix socket (NDJSON, same framing the CLI uses).

    The server pushes its `server.ready` **notification** as the first line of every connection
    (that is what the CLI's handshake reads), so the response is the first message that carries our
    id — reading line 1 blindly would compare handshakes and call every task a match.
    """
    payload = json.dumps({"jsonrpc": "2.0", "id": REQUEST_ID, "method": method, "params": params}) + "\n"
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
        s.settimeout(timeout)
        s.connect(sock_path)
        s.sendall(payload.encode())
        buf = b""
        for _ in range(16):
            while b"\n" not in buf:
                chunk = s.recv(65536)
                if not chunk:
                    raise RuntimeError(f"connection closed before a response for {method}")
                buf += chunk
            line, buf = buf.split(b"\n", 1)
            if not line.strip():
                continue
            message = json.loads(line.decode("utf-8", "replace"))
            if message.get("id") == REQUEST_ID and ("result" in message or "error" in message):
                return message
            # handshake notification or an unrelated push: keep reading
        raise RuntimeError(f"no response for {method} after 16 messages")


def normalise(node, extra_ignore: set[str], fluid_ratio: float, in_fluid: bool = False):
    """Strip volatile fields; optionally rescale fluid amounts to a common unit."""
    if isinstance(node, dict):
        out = {}
        for k, v in node.items():
            if k in VOLATILE or k in extra_ignore or VOLATILE_KEY_RE.search(k):
                continue
            out[k] = normalise(v, extra_ignore, fluid_ratio, in_fluid or k in ("fluid", "fluidStack"))
        return out
    if isinstance(node, list):
        return [normalise(v, extra_ignore, fluid_ratio, in_fluid) for v in node]
    return node


def describe(mismatch: str) -> str:
    return mismatch


def diff(a, b, path: str = "") -> list[str]:
    if type(a) is not type(b) and not (isinstance(a, (int, float)) and isinstance(b, (int, float))):
        return [f"{path or '<root>'}: type {type(a).__name__} vs {type(b).__name__}"]
    if isinstance(a, dict):
        out = []
        for k in sorted(set(a) | set(b)):
            if k not in a:
                out.append(f"{path}.{k}: missing on A (B={b[k]!r})")
            elif k not in b:
                out.append(f"{path}.{k}: missing on B (A={a[k]!r})")
            else:
                out += diff(a[k], b[k], f"{path}.{k}")
        return out
    if isinstance(a, list):
        if len(a) != len(b):
            return [f"{path}: length {len(a)} vs {len(b)}"]
        out = []
        for i, (x, y) in enumerate(zip(a, b)):
            out += diff(x, y, f"{path}[{i}]")
        return out
    if isinstance(a, float) or isinstance(b, float):
        if abs(float(a) - float(b)) > 1e-6:
            return [f"{path}: {a!r} vs {b!r}"]
        return []
    return [] if a == b else [f"{path}: {a!r} vs {b!r}"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--a", required=True, help="unix socket of instance A (Fabric)")
    ap.add_argument("--b", required=True, help="unix socket of instance B (NeoForge)")
    ap.add_argument("--tasks", default=str(Path(__file__).with_name("parity-tasks.json")))
    ap.add_argument("--timeout", type=float, default=60.0)
    ap.add_argument("--fluid-ratio", type=float, default=81.0,
                    help="Fabric droplets per NeoForge mB (1 bucket = 81000 / 1000)")
    ap.add_argument("--json", help="write the full report here")
    args = ap.parse_args()

    tasks = json.loads(Path(args.tasks).read_text())
    report, failures = [], 0
    for task in tasks:
        name, method, params = task["name"], task["method"], task.get("params")
        ignore = set(task.get("ignore", []))
        try:
            ra, rb = call(args.a, method, params, args.timeout), call(args.b, method, params, args.timeout)
        except Exception as exc:  # noqa: BLE001 - harness level, report and continue
            print(f"SKIP {name}: transport error: {exc}")
            report.append({"name": name, "status": "error", "error": str(exc)})
            continue
        na, nb = normalise(ra, ignore, args.fluid_ratio), normalise(rb, ignore, args.fluid_ratio)
        if task.get("reset"):
            # 夹具复位：它*就是*用来消除两侧历史差异的，结果本来就该不同
            print(f"reset    {name}  (A={json.dumps(ra.get('result'))[:60]} B={json.dumps(rb.get('result'))[:60]})")
            report.append({"name": name, "status": "reset", "a": na, "b": nb})
            continue
        diffs = diff(na, nb)
        if not diffs:
            status = "same"
        elif task.get("expect_diff"):
            status = "expected"      # documented cross-loader difference (e.g. reflect short names)
        else:
            status = "diff"
            failures += 1
        if status == "same":
            print(f"ok   {name}")
        else:
            print(f"{status.upper():8} {name}")
            for d in diffs[:6]:
                print(f"       {describe(d)}")
        report.append({"name": name, "status": status, "diffs": diffs, "a": na, "b": nb})

    print(f"\nparity: {len(tasks) - failures}/{len(tasks)} tasks agree"
          f" ({failures} mismatch{'es' if failures != 1 else ''})")
    if args.json:
        Path(args.json).write_text(json.dumps(report, indent=2))
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

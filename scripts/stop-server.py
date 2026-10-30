#!/usr/bin/env python3
"""Stop the Minecraft server/client a smoke script started, without guessing by process name.

`(cd dir && java ...) &` gives you the subshell's PID, and `setsid` creates a *new* session, so
killing `$!` (or its process group) leaves the game JVM running — the Fabric smoke had leaked
seven 1 GB dedicated servers before this existed. Resolve the real process instead:

  1. `--socket PATH`: read the socket's inode from /proc/net/unix and find the process holding an
     fd on it (that is the game JVM, since the mod created the socket);
  2. `--dir PATH`: any java process whose cwd is that directory (the dev runs, and the fallback
     when the socket is already gone).

Only the game JVM is killed (SIGTERM, then SIGKILL after --timeout). The Gradle daemon is never
touched: it is shared with other builds and is the game's parent under ModDevGradle.

Exit code 0 when nothing is left running, 1 otherwise.
"""

import argparse
import glob
import os
import signal
import sys
import time


def socket_inode(path):
    try:
        with open("/proc/net/unix") as fh:
            for line in fh:
                cols = line.split()
                if len(cols) >= 8 and cols[7] == path:
                    return cols[6]
    except OSError:
        pass
    return None


def pids_owning_inode(inode):
    hits = []
    for fd in glob.glob("/proc/[0-9]*/fd/*"):
        try:
            if os.readlink(fd) == f"socket:[{inode}]":
                hits.append(int(fd.split("/")[2]))
        except OSError:
            continue
    return hits


def cmdline(pid):
    try:
        with open(f"/proc/{pid}/cmdline", "rb") as fh:
            return fh.read()
    except OSError:
        return b""


def is_game_java(pid):
    """A forked game JVM — never the shared Gradle daemon or the wrapper that waits for it."""
    cmd = cmdline(pid)
    return b"java" in cmd and b"GradleDaemon" not in cmd and b"GradleWrapperMain" not in cmd


def pids_with_cwd(path):
    """Java processes whose cwd is `path` or below it. Deleted cwds are matched too: a smoke
    script's temp dir is usually already removed while its server is still shutting down."""
    want = os.path.realpath(path)
    hits = []
    for proc in glob.glob("/proc/[0-9]*"):
        pid = int(proc.split("/")[-1])
        try:
            cwd = os.readlink(f"{proc}/cwd")
        except OSError:
            continue
        if cwd.endswith(" (deleted)"):
            cwd = cwd[: -len(" (deleted)")]
        if (cwd == want or cwd.startswith(want + os.sep)) and is_game_java(pid):
            hits.append(pid)
    return hits


def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--socket")
    ap.add_argument("--dir", action="append", default=[])
    ap.add_argument("--timeout", type=float, default=20.0)
    args = ap.parse_args()

    targets = set()
    if args.socket:
        inode = socket_inode(args.socket)
        if inode:
            targets.update(pids_owning_inode(inode))
    for directory in args.dir:
        targets.update(pids_with_cwd(os.path.realpath(directory)))

    if not targets:
        print("stop-server: nothing to stop")
        return 0
    # Never touch the shared Gradle daemon, even when a caller points --dir at the project root.
    targets = {p for p in targets if is_game_java(p)}
    print(f"stop-server: SIGTERM {sorted(targets)}")
    for pid in targets:
        try:
            os.kill(pid, signal.SIGTERM)
        except OSError:
            pass

    deadline = time.time() + args.timeout
    while time.time() < deadline and any(alive(p) for p in targets):
        time.sleep(0.5)
    for pid in sorted(targets):
        if alive(pid):
            print(f"stop-server: SIGKILL {pid}")
            try:
                os.kill(pid, signal.SIGKILL)
            except OSError:
                pass
    time.sleep(1)
    left = [p for p in targets if alive(p)]
    if left:
        print(f"stop-server: still alive {left}", file=sys.stderr)
        return 1
    print("stop-server: all stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())

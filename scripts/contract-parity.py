#!/usr/bin/env python3
"""契约一致性检查（各 Minecraft 版本的 fabric-smoke.sh / neoforge-smoke.sh 使用）。

用法: contract-parity.py <socket 路径> <Methods.kt 路径> [--mc <1.20.1|1.21.1|26.3>]

断言三件事：
  1. Methods.kt 里 SERVER_METHODS 引用到的每个方法名，服务端都已注册（不能是 -32601）；
  2. 客户端平面的 gui.* 在服务端**不存在**（必须 -32601），保证两个平面不串味；
  3. 解析器本身没坏（解析出的服务端方法数 > 0），否则"全部通过"可能是假阳性。

`Methods.kt` 的清单是三个 Minecraft 版本的并集。`--mc` 给出被测服务端的版本时，按
`MISSING_BY_MC_VERSION` 扣掉该版本尚未提供的方法；不传则按完整并集检查。

必须按 id 匹配响应、跳过通知行：服务端在连接建立时会推一条 server.ready（无 id），
"一行一响应"的写法会整体错位，把 -32601 归错方法上（曾经因此报出假的 inv.extract 缺失）。
"""

import json
import re
import socket
import sys

READ_TIMEOUT = 15.0


def parse_missing_by_version(path):
    """Methods.MISSING_BY_MC_VERSION：{"1.20.1": {"gui.toasts"}, ...}（按文本解析，不执行 Kotlin）。"""
    block = re.search(r"val MISSING_BY_MC_VERSION[^\n]*=\s*mapOf\((.*?)\n\s*\)", open(path).read(), re.S)
    if block is None:
        sys.exit("contract parity: cannot parse MISSING_BY_MC_VERSION out of Methods.kt")
    missing = {}
    for match in re.finditer(r'"([\d.]+)"\s+to\s+(?:setOf\(([^)]*)\)|emptySet\(\))', block.group(1)):
        missing[match.group(1)] = set(re.findall(r'"([^"]+)"', match.group(2) or ""))
    return missing


def parse_server_methods(path):
    text = open(path).read()
    groups = {
        m.group(1): re.findall(r'"([^"]+)"', m.group(2))
        for m in re.finditer(r'val\s+(\w+)\s*=\s*setOf\(([^)]*)\)', text, re.S)
    }
    block = re.search(r'val SERVER_METHODS[^{]*buildSet\s*\{(.*?)\n    \}', text, re.S)
    if block is None:
        sys.exit("contract parity: cannot parse SERVER_METHODS out of Methods.kt")
    names = []
    for group in re.findall(r'addAll\((\w+)\)', block.group(1)):
        if group not in groups:
            sys.exit(f"contract parity: unknown group {group} in SERVER_METHODS")
        names += groups[group]
    names = sorted(set(names))
    if not names:
        sys.exit("contract parity: parsed 0 server methods — parser is broken")
    return names


def main():
    args = sys.argv[1:]
    mc_version = None
    if "--mc" in args:
        index = args.index("--mc")
        if index + 1 >= len(args):
            sys.exit("contract parity: --mc needs a Minecraft version")
        mc_version = args[index + 1]
        del args[index:index + 2]
    if len(args) != 2:
        sys.exit(__doc__)
    sock_path, methods_kt = args
    names = parse_server_methods(methods_kt)
    if mc_version is not None:
        missing_by_version = parse_missing_by_version(methods_kt)
        if mc_version not in missing_by_version:
            sys.exit(f"contract parity: unknown --mc {mc_version} (known: {sorted(missing_by_version)})")
        before = len(names)
        names = [name for name in names if name not in missing_by_version[mc_version]]
        print(f"contract parity: Minecraft {mc_version} subset, {len(names)} server methods "
              f"({before - len(names)} not provided on this line)")

    conn = socket.socket(socket.AF_UNIX)
    conn.settimeout(READ_TIMEOUT)
    conn.connect(sock_path)
    stream = conn.makefile("rwb")

    def request(rid, method):
        stream.write((json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": {}}) + "\n").encode())
        stream.flush()
        for _ in range(50):  # 跳过通知；有界，避免解析写错时永久阻塞
            line = stream.readline()
            if not line:
                sys.exit(f"contract parity: connection closed while waiting for {method}")
            response = json.loads(line)
            if response.get("id") == rid:
                return response
        sys.exit(f"contract parity: no response with id={rid} for {method} (notifications only?)")

    missing = [m for i, m in enumerate(names) if request(i, m).get("error", {}).get("code") == -32601]
    print(f"contract parity: {len(names) - len(missing)}/{len(names)} server methods registered"
          + (f", MISSING {missing}" if missing else ""))

    # 平面隔离：gui.* 属客户端平面，服务端必须报 -32601。
    code = request(9999, "gui.status").get("error", {}).get("code")
    if code != -32601:
        print(f"contract parity: gui.status must NOT exist on a server plane (got error code {code})", file=sys.stderr)
        return 1
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())

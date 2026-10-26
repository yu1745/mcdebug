#!/usr/bin/env python3
"""契约一致性检查（fabric-smoke.sh / forge-smoke.sh 共用）。

用法: contract-parity.py <socket 路径> <Methods.kt 路径>

断言三件事：
  1. Methods.kt 里 SERVER_METHODS 引用到的每个方法名，服务端都已注册（不能是 -32601）；
  2. 客户端平面的 gui.* 在服务端**不存在**（必须 -32601），保证两个平面不串味；
  3. 解析器本身没坏（解析出的服务端方法数 > 0），否则"全部通过"可能是假阳性。

必须按 id 匹配响应、跳过通知行：服务端在连接建立时会推一条 server.ready（无 id），
"一行一响应"的写法会整体错位，把 -32601 归错方法上（曾经因此报出假的 inv.extract 缺失）。
"""

import json
import re
import socket
import sys

READ_TIMEOUT = 15.0


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
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sock_path, methods_kt = sys.argv[1:3]
    names = parse_server_methods(methods_kt)

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

"""隔离进程启动与持久 RPC 的基准；默认使用确定性回环端点，不测游戏 tick。"""
import resource, hashlib, platform, tempfile, re, argparse, json, socketserver, threading, subprocess, time, statistics, random, pathlib, os

class Peer(socketserver.StreamRequestHandler):

    def handle(self):
        started_cpu = time.thread_time_ns()
        try:
            self.server.connections += 1
            ready = {'jsonrpc': '2.0', 'method': 'server.ready', 'params': {'plane': 'server', 'protocolVersion': 1, 'modVersion': '0.8.0', 'process': {'pid': os.getpid(), 'startTime': '2026-01-01T00:00:00Z', 'instanceId': 'benchmark', 'sessionToken': 'benchmark', 'gameDir': '/tmp/mdb-benchmark'}}}
            self.wfile.write((json.dumps(ready) + '\n').encode())
            self.wfile.flush()
            for line in self.rfile:
                req = json.loads(line)
                self.server.requests += 1
                assert req['method'] == 'server.status'
                result = {'jsonrpc': '2.0', 'id': req['id'], 'result': {'benchmark': True, 'players': 0}}
                self.wfile.write((json.dumps(result) + '\n').encode())
                self.wfile.flush()
        finally:
            used_cpu = time.thread_time_ns() - started_cpu
            with self.server.finished:
                self.server.handler_cpu_ns += used_cpu
                self.server.completed += 1
                self.server.finished.notify_all()

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
p = argparse.ArgumentParser()
p.add_argument('--image', required=True)
p.add_argument('--probe', required=True)
p.add_argument('--minimal', required=True)
p.add_argument('--out', required=True)
p.add_argument('--rounds', type=int, default=60)
p.add_argument('--batch', type=int, default=100)
a = p.parse_args()
if a.rounds < 1 or a.batch < 1:
    p.error('rounds and batch must be positive')
srv = Server(('127.0.0.1', 0), Peer)
srv.finished = threading.Condition()
srv.handler_cpu_ns = 0
srv.completed = 0
threading.Thread(target=srv.serve_forever, daemon=True).start()
port = srv.server_address[1]
srv.connections = 0
srv.requests = 0
base = [a.image, '--tcp', f'127.0.0.1:{port}']
cases = {'native_image_version': [a.image, '--version'], 'konan_version': [a.probe, '--version'], 'native_image_rpc': base + ['raw', 'server.status', '{}'], 'konan_rpc': [a.probe, str(port), '1'], 'native_image_batch': base + ['--batch'] + sum((['-c', 'raw server.status {}'] for _ in range(a.batch)), []), 'konan_batch': [a.probe, str(port), str(a.batch)], 'process_floor': ['/usr/bin/true']}
cases.update({'minimal_image_version': [a.minimal, '--version'], 'minimal_image_rpc': [a.minimal, str(port), '1'], 'minimal_image_batch': [a.minimal, str(port), str(a.batch)]})
history = tempfile.TemporaryDirectory(prefix='mdb-bench-history-')
cases['native_image_repl'] = [a.image, '-Duser.home=' + history.name, '--tcp', f'127.0.0.1:{port}', 'repl']
samples = {k: [] for k in cases}
cpu_samples = {k: [] for k in cases}
server_cpu_samples = {k: [] for k in cases}
rng = random.Random(20261115)
try:
    for iteration in range(a.rounds + 5):
        order = list(cases)
        rng.shuffle(order)
        for name in order:
            connections_before = srv.connections
            requests_before = srv.requests
            server_cpu_before = srv.handler_cpu_ns
            usage_before = resource.getrusage(resource.RUSAGE_CHILDREN)
            start = time.perf_counter_ns()
            r = subprocess.run(cases[name], capture_output=True, timeout=30, input=('raw server.status {}\n' * a.batch + 'exit\n').encode() if name == 'native_image_repl' else None)
            elapsed = (time.perf_counter_ns() - start) / 1000000.0
            usage_after = resource.getrusage(resource.RUSAGE_CHILDREN)
            user_ms = (usage_after.ru_utime - usage_before.ru_utime) * 1000
            system_ms = (usage_after.ru_stime - usage_before.ru_stime) * 1000
            # 子进程退出后等待处理线程收尾；此等待不计入子进程墙钟耗时。
            with srv.finished:
                if not srv.finished.wait_for(lambda: srv.completed == srv.connections, timeout=5):
                    raise RuntimeError('mock handler did not finish')
            server_cpu_ms = (srv.handler_cpu_ns - server_cpu_before) / 1000000
            if r.returncode:
                raise RuntimeError((name, r.returncode, r.stderr.decode(), r.stdout.decode()))
            if 'rpc' in name or 'batch' in name or 'repl' in name:
                decoder = json.JSONDecoder()
                stdout = r.stdout.decode()
                if name == 'native_image_repl':
                    stdout = stdout.replace("mcdebug repl — type 'help' for commands, 'exit' to quit\n", '').replace('mcdebug> ', '')
                remaining = re.sub('(?m)^\\[\\d+/\\d+\\].*\\n?', '', stdout)
                values = []
                while remaining.strip():
                    value, end = decoder.raw_decode(remaining.lstrip())
                    values.append(value)
                    remaining = remaining.lstrip()[end:]
                expected = a.batch if 'batch' in name or 'repl' in name else 1
                assert len(values) == expected, (name, len(values))
                assert srv.connections - connections_before == 1, (name, 'connection reuse failed')
                assert srv.requests - requests_before == expected, (name, 'request count')
                assert all((value.get('benchmark') is True if name.startswith('native_image') else value['result']['benchmark'] is True for value in values)), name
            if iteration >= 5:
                samples[name].append(elapsed)
                cpu_samples[name].append({'user': user_ms, 'system': system_ms, 'total': user_ms + system_ms})
                server_cpu_samples[name].append(server_cpu_ms)
    result = {'platform': platform.platform(), 'binaries': {name: {'path': str(pathlib.Path(path).resolve()), 'sha256': hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest(), 'bytes': pathlib.Path(path).stat().st_size} for name, path in [('image', a.image), ('minimal', a.minimal), ('probe', a.probe)]}, 'connection_reuse_verified': True, 'unit': 'ms', 'rounds': a.rounds, 'batch_count': a.batch, 'warmup_rounds': 5, 'endpoint': 'deterministic loopback TCP; no game', 'samples': samples, 'summary': {k: {'median': statistics.median(v), 'p95': sorted(v)[int(0.95 * (len(v) - 1))]} for k, v in samples.items()}}
    result['cpu_method'] = 'Sequential subprocesses: delta getrusage(RUSAGE_CHILDREN); all child threads, excludes parent and mock server'
    result['server_cpu_method'] = 'thread_time_ns around Peer.handle; excludes acceptor and harness; completion barrier outside wall timer'
    result['cpu_samples'] = cpu_samples
    result['server_handler_cpu_samples'] = server_cpu_samples
    result['cpu_summary'] = {name: {metric: {'median': statistics.median([v[metric] for v in values]), 'p95': sorted(v[metric] for v in values)[int(.95 * (len(values)-1))]} for metric in ('user', 'system', 'total')} for name, values in cpu_samples.items()}
    result['server_handler_cpu_summary'] = {name: {'median': statistics.median(values), 'p95': sorted(values)[int(.95*(len(values)-1))]} for name, values in server_cpu_samples.items()}
    pathlib.Path(a.out).write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'wall': result['summary'], 'child_cpu': result['cpu_summary'], 'server_handler_cpu': result['server_handler_cpu_summary']}, indent=2))
finally:
    srv.shutdown()
    srv.server_close()
    history.cleanup()

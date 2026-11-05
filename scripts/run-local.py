#!/usr/bin/env python3
"""加载根目录 .env，启动 Docker 依赖与三个本地 JVM；只停止本脚本记录且核对过的进程。"""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / '.local' / 'run'
MODULES = ('flowgate-demo', 'flowgate-control', 'flowgate-gateway')
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def environment():
    if not (ROOT / '.env').is_file():
        raise RuntimeError('先复制 .env.example 为 .env 并填写本地配置')
    # 1. 通过用户现有的 shell 配置格式读取环境；只在内存解析，不打印秘密。
    result = subprocess.run(['zsh', '-c', 'set -a; source "$1"; env -0', 'flowgate', str(ROOT / '.env')],
                            capture_output=True, check=True)
    return dict(entry.decode().split('=', 1) for entry in result.stdout.split(b'\0') if b'=' in entry)


def pid_file(module):
    return RUN / (module + '.pid')


def owned(module):
    file = pid_file(module)
    if not file.exists():
        return None
    pid = int(file.read_text())
    actual = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True).stdout
    jar = str(ROOT / module / 'target' / (module + '-0.0.1-SNAPSHOT.jar'))
    return pid if jar in actual and 'java' in actual else None


def stop():
    # 2. 只清理匹配本项目 Jar 的自有 PID，不按端口杀进程，也不删除数据卷。
    for module in reversed(MODULES):
        pid = owned(module)
        if pid:
            os.kill(pid, 15)
            deadline = time.monotonic() + 15
            while owned(module) and time.monotonic() < deadline:
                time.sleep(.2)
            if owned(module):
                raise RuntimeError(module + ' 未在15秒内退出，请检查日志')
        pid_file(module).unlink(missing_ok=True)
    print('本脚本启动的 JVM 已停止；Docker 数据库和 Redis 保持运行。')


def endpoints(env):
    return {
        'flowgate-demo': (int(env.get('DEMO_PORT', '8085')), '/demo/fast/ready'),
        'flowgate-control': (int(env.get('CONTROL_PORT', '8081')), '/status'),
        'flowgate-gateway': (int(env.get('GATEWAY_PORT', '8080')), '/actuator/health'),
    }


def probe(port, path):
    with HTTP.open(f'http://127.0.0.1:{port}{path}', timeout=5) as response:
        return json.loads(response.read())


def start(env):
    RUN.mkdir(parents=True, exist_ok=True)
    os.chmod(ROOT / '.local', 0o700)
    checks = endpoints(env)
    # 3. 先检查端口，再等待依赖健康，避免把其他人的服务误当成本项目。
    for module, (port, _) in checks.items():
        if owned(module):
            continue
        with socket.socket() as test:
            test.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            test.bind(('127.0.0.1', port))
    subprocess.run(['docker', 'compose', '-f', 'compose.local.yml', 'up', '-d', '--wait', '--wait-timeout', '180'],
                   cwd=ROOT, env=env, check=True)
    java = str(Path(env['JAVA_HOME']) / 'bin' / 'java')
    for module in MODULES:
        jar = ROOT / module / 'target' / (module + '-0.0.1-SNAPSHOT.jar')
        if not jar.is_file():
            raise RuntimeError('缺少 Jar，请先按 README 执行 Maven package')
        if not owned(module):
            with (RUN / (module + '.log')).open('a') as log:
                process = subprocess.Popen([java, '-jar', str(jar)], cwd=ROOT, env=env,
                                           stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            pid_file(module).write_text(str(process.pid))
        port, path = checks[module]
        deadline = time.monotonic() + 40
        while True:
            try:
                state = probe(port, path)
                if module == 'flowgate-control' and any(state.get(key) != 'UP' for key in ('mysql', 'redis')):
                    raise RuntimeError('Control 依赖尚未就绪')
                break
            except Exception:
                if time.monotonic() >= deadline or not owned(module):
                    raise RuntimeError(module + ' 启动未就绪，请查看 .local/run/ 下日志')
                time.sleep(.3)
        print(module + ' 已就绪：127.0.0.1:' + str(port), flush=True)
    print('测试台：http://127.0.0.1:' + env.get('CONTROL_PORT', '8081') + '/test-console/')


if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else 'start'
    env = environment()
    if mode == 'start':
        start(env)
    elif mode == 'stop':
        stop()
    elif mode == 'restart':
        stop(); start(env)
    elif mode == 'status':
        for module, (port, path) in endpoints(env).items():
            try:
                print(module, json.dumps(probe(port, path), ensure_ascii=False))
            except Exception:
                print(module, '无法访问')
    else:
        raise SystemExit('用法：python3 scripts/run-local.py [start|stop|restart|status]')

"""E隔离运行器：只接管自身可验证的Compose项目/PID；没有删除卷或接管用户进程的入口。"""
import json
import contextlib
import fcntl
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import time
import uuid
import v11_local_core as core

DEFAULT_PORTS = {'control': 8081, 'gateway': 8080, 'demo': 8085, 'mysql': 13407, 'redis': 16480, 'kafka': 19192}


def instance_home(instance='default'):
    if not re.fullmatch(r'[a-z0-9-]{1,40}', instance):
        raise core.LocalFailure('实例名只允许小写字母数字横线，最多40位')
    return core.ROOT / '.local' / ('v11-demo' if instance == 'default' else 'v11-demo-' + instance)


def private_directory(path):
    if path.is_symlink() or path.parent.is_symlink():
        raise core.LocalFailure('运行目录不能为符号链接')
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path, 0o700)


@contextlib.contextmanager
def mutation_lock(home):
    private_directory(home.parent); private_directory(home)
    file = home / '.lock'
    if file.is_symlink(): raise core.LocalFailure('锁文件不能为符号链接')
    fd = os.open(file, os.O_RDWR | os.O_CREAT, 0o600)
    try:
        try: fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: raise core.LocalFailure('此实例已有启动/停止操作，请等待完成') from None
        yield
    finally:
        os.close(fd)


def save(path, value):
    if path.is_symlink():
        raise core.LocalFailure('拒绝覆盖符号链接')
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.new')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(value, stream)
    temporary.replace(path)


def load(home):
    if home.is_symlink() or home.parent.is_symlink():
        raise core.LocalFailure('隔离目录身份不合法')
    try:
        if any((home / name).is_symlink() for name in ('state.json', 'env.json')): raise core.LocalFailure('身份文件不能为符号链接')
        if (home / 'state.json').stat().st_mode & 0o077 or (home / 'env.json').stat().st_mode & 0o077:
            raise core.LocalFailure('身份/密钥文件权限须0600')
        state = json.loads((home / 'state.json').read_text())
        env = json.loads((home / 'env.json').read_text())
    except (OSError, ValueError):
        raise core.LocalFailure('尚无有效隔离实例，请先up --isolated') from None
    if not re.fullmatch(r'[a-f0-9]{32}', state.get('owner', '')) or state.get('project') != 'flowgate-v11-demo-' + state['owner'][:12] or env.get('FG_V11_DEMO_OWNER') != state['owner']:
        raise core.LocalFailure('隔离清单身份不一致，拒绝接管')
    return state, env


def compose(state, env, *args):
    return core.command(['docker', 'compose', '-p', state['project'], '-f', str(core.ROOT / 'compose.v11-demo.yml'), *args], env=dict(os.environ, **env), timeout=210)


def identity(pid):
    value = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True).stdout.strip()
    if not value or '<defunct>' in value:
        return None
    start = subprocess.run(['ps', '-p', str(pid), '-o', 'lstart='], capture_output=True, text=True).stdout.strip()
    return value, start


def owned_pid(home, state, module):
    row = state.get('processes', {}).get(module)
    if not row: return None
    if not isinstance(row.get('pid'), int) or row['pid'] <= 1: raise core.LocalFailure('PID记录非法，拒绝发送信号')
    actual = identity(row['pid'])
    if actual is None: return None
    jar = str(home / 'artifacts' / (module + '.jar'))
    if jar not in actual[0] or '-Dflowgate.v11-demo.owner=' + state['owner'] not in actual[0] or actual[1] != row.get('start'):
        raise core.LocalFailure('PID已复用或不属于此实例，拒绝停止/接管')
    return row['pid']


def volume_identities(state, allow_missing=False):
    result = {}
    for kind in ('mysql', 'redis', 'kafka'):
        name = state['project'] + '_' + kind + '-data'
        proc = subprocess.run(['docker', 'volume', 'inspect', '--format', '{{json .Labels}}', name], capture_output=True, text=True)
        if proc.returncode and allow_missing: continue
        if proc.returncode: raise core.LocalFailure('隔离卷缺失，拒绝自动改动已有数据库')
        labels = json.loads(proc.stdout) or {}
        if labels.get('io.flowgate.v11-demo.owner') != state['owner'] or labels.get('com.docker.compose.project') != state['project']:
            raise core.LocalFailure('卷身份不一致，拒绝挂载或停止')
        result[kind] = name
    return result


def containers(state, env):
    result = []
    ids = core.command(['docker', 'ps', '-a', '--filter', 'label=com.docker.compose.project=' + state['project'], '--format', '{{.ID}}']).splitlines()
    for cid in ids:
        labels = json.loads(core.command(['docker', 'inspect', '--format', '{{json .Config.Labels}}', cid])) or {}
        if labels.get('io.flowgate.v11-demo.owner') != state['owner'] or labels.get('com.docker.compose.service') not in ('mysql', 'redis', 'kafka'):
            raise core.LocalFailure('项目内存在身份不符的容器，拒绝操作')
        result.append(cid)
    return result


def port_free(port):
    try:
        with socket.socket() as test:
            # 已关闭自有服务的TIME_WAIT可重绑；仍在LISTEN的其他进程仍会拒绝绑定。
            test.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            test.bind(('127.0.0.1', port))
        return True
    except OSError: return False


def listener_owned(port, pid):
    if shutil.which('lsof'):
        value = subprocess.run(['lsof', '-nP', '-t', '-iTCP:' + str(port), '-sTCP:LISTEN'], capture_output=True, text=True).stdout.split()
        return bool(value) and set(value) == {str(pid)}
    # Linux只读/proc回退，不通过端口杀进程，也不要求psutil。
    try:
        inodes = {os.readlink(file) for file in Path('/proc', str(pid), 'fd').iterdir() if file.is_symlink()}
        sockets = []
        for file in ('tcp', 'tcp6'):
            for row in Path('/proc/net', file).read_text().splitlines()[1:]:
                fields = row.split()
                if int(fields[1].split(':')[1], 16) == port and fields[3] == '0A': sockets.append('socket:[' + fields[9] + ']')
        return bool(sockets) and all(value in inodes for value in sockets)
    except OSError: return False


def validate_ports(ports):
    if len(set(ports.values())) != 6 or any(not 1024 <= int(value) <= 65535 for value in ports.values()):
        raise core.LocalFailure('六个端口须互不相同且在1024..65535内')


def check_ports(home, state, env):
    for kind, port in state['ports'].items():
        if port_free(port): continue
        if kind in ('mysql', 'redis', 'kafka'):
            core.docker_target(port, kind, state['project'], state['owner'])
        else:
            pid = owned_pid(home, state, 'flowgate-' + kind)
            if not pid or not listener_owned(port, pid):
                raise core.LocalFailure('端口' + str(port) + '已被非本实例进程占用；请手工处理或显式更换全链路端口')


def wait_for(fn, label, seconds=60):
    deadline = time.monotonic() + seconds
    while True:
        try:
            if fn(): return
        except (core.LocalFailure, ValueError): pass
        if time.monotonic() >= deadline: raise core.LocalFailure(label + '未在期限内就绪；保留卷供诊断')
        time.sleep(.5)


def fresh(home, source, ports, config):
    # 1. 创建不可猜测的独立身份；不复用flowgate-local、原库、卷或PID。
    validate_ports(ports)
    for port in ports.values():
        if not port_free(port): raise core.LocalFailure('端口' + str(port) + '已占用；未创建资源，请手工停止服务或覆盖端口')
    for key in ('JAVA_HOME', 'FG_BOOTSTRAP_ADMIN_USERNAME', 'FG_BOOTSTRAP_ADMIN_BCRYPT', 'FLOWGATE_INTERNAL_KEY'):
        if not source.get(key): raise core.LocalFailure('缺少必填配置：' + key + '；请参考手册，不生成默认管理员密码')
    if not re.fullmatch(r'\$2[aby]\$\d\d\$[./A-Za-z0-9]{53}', source['FG_BOOTSTRAP_ADMIN_BCRYPT']) or len(source['FLOWGATE_INTERNAL_KEY']) < 32:
        raise core.LocalFailure('管理员BCrypt或内部密钥格式不合法')
    owner = uuid.uuid4().hex
    state = {'schemaVersion': 1, 'owner': owner, 'project': 'flowgate-v11-demo-' + owner[:12], 'ports': ports, 'sourceHash': core.file_hash(config), 'initialized': False, 'processes': {}}
    env = {key: source[key] for key in ('JAVA_HOME', 'FG_BOOTSTRAP_ADMIN_USERNAME', 'FG_BOOTSTRAP_ADMIN_BCRYPT', 'FLOWGATE_INTERNAL_KEY')}
    env.update(FG_V11_DEMO_OWNER=owner, FG_MYSQL_USERNAME='flowgate', FG_MYSQL_PASSWORD=secrets.token_urlsafe(30), FG_MYSQL_ROOT_PASSWORD=secrets.token_urlsafe(30), FG_REDIS_PASSWORD=secrets.token_urlsafe(30), FG_REDIS_HOST='127.0.0.1', FG_REDIS_DATABASE='0', FG_MYSQL_PORT=str(ports['mysql']), FG_REDIS_PORT=str(ports['redis']), FLOWGATE_KAFKA_PORT=str(ports['kafka']), CONTROL_PORT=str(ports['control']), GATEWAY_PORT=str(ports['gateway']), DEMO_PORT=str(ports['demo']))
    env.update(FG_MYSQL_URL=f"jdbc:mysql://127.0.0.1:{ports['mysql']}/flowgate_v11_demo?connectionTimeZone=UTC", FLOWGATE_CONTROL_URI=f"http://127.0.0.1:{ports['control']}", FLOWGATE_CONSOLE_ORIGIN=f"http://127.0.0.1:{ports['control']}", FLOWGATE_ALLOWED_UPSTREAMS=f"http://127.0.0.1:{ports['demo']}", FLOWGATE_DEMO_STATUS_URI=f"http://127.0.0.1:{ports['demo']}", FLOWGATE_NODE_ID='e-demo-' + owner[:12], FLOWGATE_QUOTA_TRAFFIC_NODES='e-demo-' + owner[:12], FLOWGATE_QUOTA_ACTIVATION_ENABLED='true', FLOWGATE_ASYNC_ENABLED='true', FLOWGATE_KAFKA_BOOTSTRAP_SERVERS=f"127.0.0.1:{ports['kafka']}", FLOWGATE_USAGE_TOPIC='flowgate.usage.v1', FLOWGATE_USAGE_GROUP='flowgate-usage-aggregate-v1')
    private_directory(home.parent); private_directory(home)
    save(home / 'env.json', env); save(home / 'state.json', state)
    return state, env


def start(home, config, ports):
    source = core.read_environment(config)
    if (home / 'state.json').exists():
        state, env = load(home)
        if core.file_hash(config) != state['sourceHash']: raise core.LocalFailure('源配置已改变，保留旧实例；请使用原配置或新实例，不重置密码')
        if ports and ports != state['ports']: raise core.LocalFailure('已有实例端口不可隐式改写，请使用新实例')
    else: state, env = fresh(home, source, ports or DEFAULT_PORTS.copy(), config)
    # 2. 所有检查在启动前完成；只对首次建立且身份连续的演示库做初始化。
    validate_ports(state['ports']); check_ports(home, state, env); containers(state, env); volume_identities(state, True)
    version = subprocess.run([str(Path(env['JAVA_HOME']) / 'bin/java'), '-version'], capture_output=True, text=True, timeout=5)
    if 'version \"17.' not in version.stdout + version.stderr: raise core.LocalFailure('隔离演示必须使用Java17')
    for module in core.MODULES:
        if not core.jar_path(module).is_file(): raise core.LocalFailure('缺少Jar，先使用Java17构建')
        if owned_pid(home, state, module) and state.get('artifactHashes', {}).get(module) != core.file_hash(core.jar_path(module)):
            raise core.LocalFailure('运行Jar与新构建不同，请先stop --isolated再up，不能改写正在运行的Jar')
    started = []; running_before = set(core.command(['docker', 'ps', '--filter', 'label=com.docker.compose.project=' + state['project'], '--format', '{{.ID}}']).splitlines())
    try:
        compose(state, env, 'up', '-d', '--wait', '--wait-timeout', '150')
        volume_identities(state)
        tables = set(core.mysql_query(env, 'SHOW TABLES;', state['project'], state['owner']).splitlines())
        expected = set.union(*core.schema_groups().values())
        if not state['initialized']:
            if tables and not state.get('migrationStarted'): raise core.LocalFailure('库已非空且无首次迁移凭据，拒绝初始化')
            if tables - expected: raise core.LocalFailure('隔离库出现非预期表，拒绝初始化')
            state['migrationStarted'] = True; save(home / 'state.json', state)
            for script in ('flowgate-init.sql', 'flowgate-v11.sql', 'flowgate-v11-c.sql'):
                core.mysql_query(env, (core.ROOT / 'sql' / script).read_text(), state['project'], state['owner'], read_only=False)
            state['initialized'] = True; save(home / 'state.json', state)
        elif expected - tables: raise core.LocalFailure('已初始化实例缺表，拒绝自动补迁移；请先doctor诊断')
        if any(core.preflight(env, state['project'], state['owner']).values()): raise core.LocalFailure('四项历史风险非零，停止启用，不修改账本')
        wait_for(lambda: core.kafka_probe(env, state['project'], state['owner']), 'Kafka协议', 40)
        artifacts = home / 'artifacts'; private_directory(artifacts)
        overlay = home / 'static' / 'console'; private_directory(overlay.parent); private_directory(overlay)
        runtime = overlay / 'runtime-config.js'
        runtime.write_text("export const gatewayOrigin = 'http://127.0.0.1:" + str(state['ports']['gateway']) + "';\n"); os.chmod(runtime, 0o600)
        for module in core.MODULES:
            if owned_pid(home, state, module): continue
            target = artifacts / (module + '.jar')
            if target.is_symlink(): raise core.LocalFailure('Jar快照不能为符号链接')
            shutil.copy2(core.jar_path(module), target)
            state.setdefault('artifactHashes', {})[module] = core.file_hash(target)
            proc_env = dict(os.environ, **env)
            if module != 'flowgate-control':
                for key in list(proc_env):
                    if key.startswith(('FG_MYSQL_', 'FG_BOOTSTRAP_', 'FLOWGATE_ASYNC_', 'FLOWGATE_KAFKA_', 'FLOWGATE_USAGE_', 'FLOWGATE_QUOTA_')): proc_env.pop(key)
            else: proc_env['SPRING_WEB_RESOURCES_STATIC_LOCATIONS'] = overlay.parent.as_uri() + '/,classpath:/static/'
            log = home / (module + '.log'); fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, 'w') as stream:
                process = subprocess.Popen([str(Path(env['JAVA_HOME']) / 'bin/java'), '-Dflowgate.v11-demo.owner=' + state['owner'], '-jar', str(target)], cwd=core.ROOT, env=proc_env, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
            started.append(process.pid)
            wait_for(lambda: identity(process.pid), 'JVM身份', 3)
            state['processes'][module] = {'pid': process.pid, 'start': identity(process.pid)[1]}; save(home / 'state.json', state)
            kind = module.removeprefix('flowgate-'); port = state['ports'][kind]
            path = {'demo': '/demo/fast/ready', 'control': '/status', 'gateway': '/actuator/health'}[kind]
            def ready(port=port, path=path, module=module):
                if not owned_pid(home, state, module): raise core.LocalFailure('JVM提前退出')
                code, result = core.http_json('http://127.0.0.1:' + str(port), path)
                return code == 200 and (module != 'flowgate-control' or all(result.get(k) == 'UP' for k in ('mysql', 'redis', 'demo')))
            wait_for(ready, kind + '启动', 45)
            if not listener_owned(port, process.pid): raise core.LocalFailure('实际监听PID不符，拒绝标就绪')
        wait_for(lambda: core.kafka_probe(env, state['project'], state['owner'], consumer=True), 'Kafka消费组', 45)
        state['running'] = True; save(home / 'state.json', state)
    except Exception:
        # 3. 失败只退出本次刚启动的自有JVM；依赖仍保留，任何路径都不删卷或账本。
        for module in reversed(core.MODULES):
            pid = owned_pid(home, state, module)
            if pid and pid in started: os.kill(pid, signal.SIGTERM)
        newly_running = [cid for cid in containers(state, env) if cid not in running_before]
        if newly_running: core.command(['docker', 'stop', *newly_running], timeout=40)
        state['running'] = False; save(home / 'state.json', state)
        raise
    return status(home), 0


def status(home, profile='v11'):
    state, env = load(home)
    trusted = True
    for module in core.MODULES:
        pid = owned_pid(home, state, module)
        if not pid or not listener_owned(state['ports'][module.removeprefix('flowgate-')], pid) or state.get('artifactHashes', {}).get(module) != core.file_hash(core.jar_path(module)): trusted = False
    result = core.doctor(env, profile, state['project'], state['owner'], trusted_runtime=trusted)
    result['project'] = state['project']; result['ports'] = state['ports']; result['volumes'] = volume_identities(state, True)
    result['console'] = 'http://127.0.0.1:' + str(state['ports']['control']) + '/console/'
    return result


def stop(home):
    state, env = load(home)
    # 所有身份先核对，再发送任何信号；PID重用或Docker归属不明时全部拒绝。
    pids = [owned_pid(home, state, module) for module in core.MODULES]
    containers(state, env); volumes = volume_identities(state, True)
    for pid in reversed(pids):
        if pid: os.kill(pid, signal.SIGTERM)
    wait_for(lambda: all(owned_pid(home, state, module) is None for module in core.MODULES), '自有JVM停止', 20)
    compose(state, env, 'stop', '--timeout', '20')
    state['running'] = False; save(home / 'state.json', state)
    return {'schemaVersion': 1, 'state': 'STOPPED', 'project': state['project'], 'volumesRetained': volumes, 'reason': '只停止本实例，保留所有数据卷与账本'}

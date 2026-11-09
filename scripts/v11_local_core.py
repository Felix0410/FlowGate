"""V11本地交付共用安全边界：只读配置/探测、脱敏诊断和严格三态。仅Python标准库。"""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import socket
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
HOME = ROOT / '.local' / 'v11-demo'
MODULES = ('flowgate-demo', 'flowgate-control', 'flowgate-gateway')
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class LocalFailure(Exception):
    """只携带可展示的静态原因，不包含stderr/SQL/URL密码等原始内容。"""


def command(args, env=None, data=None, timeout=12):
    try:
        result = subprocess.run(args, env=env, input=data, text=True, capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise LocalFailure('命令不可执行或超时，请检查工具和服务') from None
    if result.returncode:
        raise LocalFailure('命令未成功，未输出可能含敏感信息的原始错误')
    return result.stdout.strip()


def read_environment(path):
    """解析现有export KEY='value'格式，不source，不执行命令或变量替换。"""
    result = {}
    try:
        lines = Path(path).read_text().splitlines()
    except OSError:
        raise LocalFailure('配置文件不可读，请参考.env.example；本工具不会修改配置') from None
    for line in lines:
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line = line[7:]
        match = re.fullmatch(r'([A-Z_][A-Z0-9_]*)=(.*)', line)
        if not match:
            raise LocalFailure('配置含不支持的Shell语法，请使用静态KEY=值，不执行配置')
        key, value = match.groups()
        try:
            values = shlex.split(value, comments=True)
        except ValueError:
            raise LocalFailure('配置引号不完整') from None
        if len(values) > 1:
            raise LocalFailure('配置值含未引用空格')
        result[key] = values[0] if values else ''
    return result


def jar_path(module):
    return ROOT / module / 'target' / (module + '-0.0.1-SNAPSHOT.jar')


def file_hash(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def http_json(origin, path, token=None, body=None, method=None):
    # 1. 所有本地请求固定回环来源，Token只在进程内存与Header，不进入命令参数/URL。
    parsed = urllib.parse.urlsplit(origin)
    if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path:
        raise LocalFailure('仅允许明确的127.0.0.1本地服务来源')
    headers = {}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if body is not None:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(origin + path, headers=headers, data=None if body is None else json.dumps(body).encode(), method=method)
    try:
        response = HTTP.open(request, timeout=6)
    except urllib.error.HTTPError as error:
        response = error
    except (OSError, urllib.error.URLError):
        raise LocalFailure('本地HTTP不可达或超时') from None
    with response:
        try:
            value = json.loads(response.read())
        except (ValueError, UnicodeError):
            value = None
        return response.status, value


def jdbc_target(env):
    try:
        url = urllib.parse.urlsplit(env.get('FG_MYSQL_URL', 'jdbc:mysql://127.0.0.1:3306/flowgate').removeprefix('jdbc:'))
        db = url.path.strip('/')
        if url.scheme != 'mysql' or url.hostname != '127.0.0.1' or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*', db) or url.username or url.password:
            raise ValueError()
        return url.port or 3306, db
    except ValueError:
        raise LocalFailure('MySQL来源不受支持或含URL凭证；仅诊断明确的本机数据库') from None


def docker_target(port, service, project=None, owner=None):
    # 2. 核对端口、镜像与Compose身份再exec；不会连到端口恰好相同的其他服务。
    for cid in command(['docker', 'ps', '--format', '{{.ID}}']).splitlines():
        ports = json.loads(command(['docker', 'inspect', '--format', '{{json .NetworkSettings.Ports}}', cid])) or {}
        if not any(p.get('HostIp') == '127.0.0.1' and p.get('HostPort') == str(port) for bindings in ports.values() for p in bindings or []):
            continue
        labels = json.loads(command(['docker', 'inspect', '--format', '{{json .Config.Labels}}', cid])) or {}
        image = command(['docker', 'inspect', '--format', '{{.Config.Image}}', cid])
        if labels.get('com.docker.compose.project') != (project or 'flowgate-local') or labels.get('com.docker.compose.service') != service:
            raise LocalFailure('端口属于非预期Compose服务，拒绝接管或exec')
        if owner and labels.get('io.flowgate.v11-demo.owner') != owner:
            raise LocalFailure('隔离容器owner不一致，拒绝操作')
        if not image.startswith({'mysql': 'mysql:8.4', 'redis': 'redis:7', 'kafka': 'apache/kafka:4.1.1'}[service]):
            raise LocalFailure('依赖镜像版本或身份不符')
        return cid
    raise LocalFailure('未找到与配置端口和Compose身份一致的运行容器')


def mysql_query(env, sql, project=None, owner=None, read_only=True):
    port, database = jdbc_target(env)
    user = env.get('FG_MYSQL_USERNAME', 'flowgate')
    if not re.fullmatch(r'[A-Za-z0-9_-]+', user):
        raise LocalFailure('MySQL用户名格式不受支持')
    cid = docker_target(port, 'mysql', project, owner)
    proc_env = dict(os.environ, MYSQL_PWD=env.get('FG_MYSQL_PASSWORD', ''))
    statement = "SET time_zone = '+00:00';\n" + ('START TRANSACTION READ ONLY;\n' if read_only else '') + sql + ('\nROLLBACK;' if read_only else '')
    return command(['docker', 'exec', '-i', '-e', 'MYSQL_PWD', cid, 'mysql', '-u', user, '-D', database, '-N', '-B'], env=proc_env, data=statement)


def schema_groups():
    extract = lambda file: set(re.findall(r'CREATE TABLE IF NOT EXISTS (fg_[a-z_]+)', (ROOT / 'sql' / file).read_text()))
    return {'v1': extract('flowgate-init.sql'), 'b': extract('flowgate-v11.sql'), 'c': extract('flowgate-v11-c.sql')}


def preflight(env, project=None, owner=None):
    text = re.sub(r'--[^\n]*', '', (ROOT / 'sql/v11-b-upgrade-preflight.sql').read_text())
    if any(not part.strip().upper().startswith(('SELECT ', 'SET TIME_ZONE ')) for part in text.split(';') if part.strip()):
        raise LocalFailure('preflight包含非只读语句，拒绝运行')
    rows = mysql_query(env, text, project, owner)
    risks = {}
    for row in rows.splitlines():
        key, count = row.split('\t'); risks[key] = int(count)
    if len(risks) != 4:
        raise LocalFailure('preflight未返回完整四项风险，不能标就绪')
    return risks


def kafka_probe(env, project=None, owner=None, consumer=False):
    port = int(env.get('FLOWGATE_KAFKA_PORT', '19092'))
    cid = docker_target(port, 'kafka', project, owner)
    config = 'request.timeout.ms=1000\ndefault.api.timeout.ms=3000\n'
    args = ['docker', 'exec', '-i', cid, '/opt/kafka/bin/' + ('kafka-consumer-groups.sh' if consumer else 'kafka-topics.sh'), '--bootstrap-server', 'localhost:9092', '--command-config', '/dev/stdin']
    if consumer:
        group = env.get('FLOWGATE_USAGE_GROUP', 'flowgate-usage-aggregate-v1')
        if not re.fullmatch(r'[A-Za-z0-9._-]+', group):
            raise LocalFailure('Kafka消费组格式不受支持')
        value = command(args + ['--describe', '--group', group, '--state'], data=config)
        return bool(re.search(r'\bStable\s+[1-9][0-9]*\b', value))
    command(args + ['--list'], data=config)
    return True


def check(ident, state, reason, action=''):
    return {'id': ident, 'state': state, 'reason': reason, 'action': action}


def report(checks, profile, isolated=False):
    # 3. UNKNOWN永不提升READY，缺权限/响应不是成功，输出没有原始错误和配置秘密。
    states = [row['state'] for row in checks]
    state = 'BLOCKED' if 'BLOCKED' in states else 'UNKNOWN' if 'UNKNOWN' in states else 'READY'
    return {'schemaVersion': 1, 'profile': profile, 'isolated': isolated, 'state': state, 'checks': checks}


def doctor(env, profile='v11', project=None, owner=None, trusted_runtime=False):
    checks = []
    def probe(ident, fn, action):
        try:
            ok, reason = fn(); checks.append(check(ident, 'READY' if ok else 'BLOCKED', reason, '' if ok else action))
        except (LocalFailure, OSError, ValueError, KeyError, zipfile.BadZipFile, json.JSONDecodeError):
            checks.append(check(ident, 'UNKNOWN', '无法只读确认，可能权限不足或依赖不可达', action))
    java = str(Path(env.get('JAVA_HOME', '/missing')) / 'bin/java')
    probe('java17', lambda: ('version "17.' in command([java, '-version']), '需要Java17'), '设置静态JAVA_HOME指向JDK17')
    # java -version写stderr，单独处理而不把其诊断原文加入报告。
    try:
        version = subprocess.run([java, '-version'], capture_output=True, text=True, timeout=5)
        checks[-1] = check('java17', 'READY' if 'version "17.' in version.stderr + version.stdout else 'BLOCKED', 'Java17版本检查', '使用JDK17')
    except (OSError, subprocess.TimeoutExpired):
        pass
    probe('docker', lambda: (bool(command(['docker', 'info', '--format', '{{.ServerVersion}}'])), 'Docker可访问'), '启动Docker并授予当前用户访问权限')
    probe('compose', lambda: (bool(command(['docker', 'compose', 'version', '--short'])), 'Compose可用'), '安装Docker Compose v2')
    def jars():
        for module in MODULES:
            with zipfile.ZipFile(jar_path(module)) as jar:
                if module == 'flowgate-control':
                    for asset in (ROOT / module / 'src/main/resources/static/console').iterdir():
                        if asset.is_file() and jar.read('BOOT-INF/classes/static/console/' + asset.name) != asset.read_bytes():
                            return False, 'Jar静态资源与当前源码不一致'
        return True, '三模块Jar与工作台源码可用'
    probe('jars', jars, '使用Java17按README执行Maven test package')
    tables = None
    try:
        tables = set(mysql_query(env, 'SHOW TABLES;', project, owner).splitlines())
        checks.append(check('mysql', 'READY', '已用当前配置执行只读查询'))
    except LocalFailure:
        checks.append(check('mysql', 'UNKNOWN', '无法验证当前配置的数据库连接或容器身份', '检查MySQL端口、配置、权限；不重置卷'))
    try:
        cid = docker_target(int(env.get('FG_REDIS_PORT', '6379')), 'redis', project, owner)
        value = command(['docker', 'exec', '-e', 'REDISCLI_AUTH', cid, 'redis-cli', '-n', env.get('FG_REDIS_DATABASE', '0'), 'ping'], env=dict(os.environ, REDISCLI_AUTH=env.get('FG_REDIS_PASSWORD', '')))
        checks.append(check('redis', 'READY' if value == 'PONG' else 'BLOCKED', '会话服务只读PING', '核对Redis认证/数据库配置'))
    except LocalFailure:
        checks.append(check('redis', 'UNKNOWN', 'Redis身份或认证不可确认', '检查Redis容器/端口/配置'))
    for name, port, path in [('control', env.get('CONTROL_PORT', '8081'), '/status'), ('gateway', env.get('GATEWAY_PORT', '8080'), '/actuator/health'), ('demo', env.get('DEMO_PORT', '8085'), '/demo/fast/ready')]:
        def endpoint(name=name, port=port, path=path):
            code, body = http_json('http://127.0.0.1:' + str(int(port)), path)
            ok = code == 200 and isinstance(body, dict)
            if name == 'control': ok = ok and all(body.get(key) == 'UP' for key in ('control', 'mysql', 'redis', 'demo'))
            if name == 'gateway': ok = ok and body.get('status') == 'UP'
            return ok, name + '实际HTTP与依赖检查'
        probe(name, endpoint, '确认端口属于本项目后手动启动；不要杀占用端口的进程')
    checks.append(check('processOwnership', 'READY' if trusted_runtime else 'UNKNOWN', '已核对隔离进程身份' if trusted_runtime else '外部或IDE进程不接管，PID归属未验证', '外部进程由负责人管理；可使用up --isolated'))
    groups = schema_groups()
    for key in (['v1'] if profile == 'v1' else ['v1', 'b', 'c']):
        missing = groups[key] - tables if tables is not None else None
        checks.append(check('schema.' + key, 'UNKNOWN' if missing is None else 'BLOCKED' if missing else 'READY', '表结构未验证' if missing is None else '缺少表：' + '、'.join(sorted(missing)) if missing else '所需表存在', '已有库先备份并由负责人决定迁移；本工具不会迁移原库' if missing is None or missing else ''))
    if profile == 'v11':
        probe('preflight', lambda: (all(count == 0 for count in preflight(env, project, owner).values()), '四项历史风险必须全零'), '风险非零停止激活并人工审核，禁止删除账本')
        configured = env.get('FLOWGATE_QUOTA_ACTIVATION_ENABLED', 'false') == 'true'
        checks.append(check('quotaActivation', 'READY' if configured and trusted_runtime else 'BLOCKED' if not configured else 'UNKNOWN', '隔离受管进程已配置计量' if configured and trusted_runtime else 'B关闭为安全默认' if not configured else '配置为开启，外部进程实际值未验证', '登录工作台读取实际就绪原因；不要全局修改.env开关'))
        def node_gate():
            names = [n.strip() for n in env.get('FLOWGATE_QUOTA_TRAFFIC_NODES', '').split(',') if n.strip()]
            if not names: return False, '缺完整接流节点名单'
            current = mysql_query(env, 'SELECT current_version FROM fg_config_state WHERE id=1;', project, owner)
            if int(current) < 1: return False, '先发布非计量初版并等待节点能力'
            checksum = mysql_query(env, 'SELECT sha256 FROM fg_config_release WHERE version=' + str(int(current)) + ';', project, owner)
            raw = mysql_query(env, 'SELECT r.node_id,r.applied_version,r.checksum,TIMESTAMPDIFF(SECOND,r.last_seen_at,UTC_TIMESTAMP()),c.version,c.checksum,c.features_json,TIMESTAMPDIFF(SECOND,c.last_seen_at,UTC_TIMESTAMP()) FROM fg_release_node r LEFT JOIN fg_node_capability c ON r.node_id=c.node_id;', project, owner)
            rows = {parts[0]: parts for parts in (line.split('\t') for line in raw.splitlines())}
            for name in names:
                row = rows.get(name)
                if not row or row[1] != current or row[4] != current or row[2] != checksum or row[5] != checksum or not all(0 <= int(row[i]) <= 15 for i in (3, 7)) or 'USAGE_QUOTA_V1' not in json.loads(row[6]): return False, '完整名单的版本/checksum/心跳/可信能力未就绪'
            return True, '完整名单通过当前数据库只读门禁核对'
        probe('nodes', node_gate, '先在发布中心发布非计量版本，等待所有接流节点确认')
        probe('kafkaProtocol', lambda: (kafka_probe(env, project, owner), 'Kafka协议元数据响应'), '检查Kafka，TCP端口开放不代表协议可用')
        enabled = env.get('FLOWGATE_ASYNC_ENABLED', 'false') == 'true'
        checks.append(check('asyncActivation', 'READY' if enabled and trusted_runtime else 'BLOCKED' if not enabled else 'UNKNOWN', '隔离Control配置异步开启' if enabled and trusted_runtime else '异步关闭或外部Control实际开关未验证', '登录工作台读取C状态；关闭时余额可用、日报暂不聚合'))
        probe('consumer', lambda: (enabled and kafka_probe(env, project, owner, True), '实际消费组须Stable且有成员，不代表所有SENT已统计'), '等待Topic与Consumer真实启动；查询异步事件诊断')
    return report(checks, profile, bool(owner))

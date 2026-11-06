#!/usr/bin/env python3
"""在既有隔离Lab补齐Block1边界、真实取消/网络失败、20次CB修订容量记录。"""
import concurrent.futures
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('block3', ROOT / 'scripts/block3-smoke.py')
b3 = importlib.util.module_from_spec(spec); spec.loader.exec_module(b3)


class Lab(b3.Lab):
    def app(self, module, extra=None):
        # 普通代理边界需要允许2000ms合法慢请求；Block3脚本自己的1500ms实验配置不适用。
        overrides = dict(extra or {})
        if module == 'flowgate-demo': overrides['FLOWGATE_DEMO_FAULT_ENABLED'] = 'true'
        if module == 'flowgate-gateway': overrides['SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSE_TIMEOUT'] = '3000ms'
        process = b3.b2.Lab.app(self, module, overrides)
        if module == 'flowgate-demo': self.demo_process = process
        if module == 'flowgate-control': self.control_process = process
        if module == 'flowgate-gateway' and overrides.get('FLOWGATE_NODE_ID') == 'block2-0': self.gateway_process = process
        return process

    def raw(self, path, body=None, token=None, base=None, method=None, headers=None):
        headers = dict(headers or {})
        if token: headers['Authorization'] = 'Bearer ' + token
        if body is not None: headers['Content-Type'] = 'application/json'
        request = urllib.request.Request((base or self.control) + path, data=body, headers=headers, method=method)
        try: response = b3.b2.HTTP.open(request, timeout=8)
        except urllib.error.HTTPError as error: response = error
        with response:
            raw = response.read().decode()
            try: data = json.loads(raw)
            except ValueError: data = raw
            self.evidence.append({'path': path, 'status': response.status})
            return response.status, data, dict(response.headers)

    def histogram(self):
        # 1. 只诊断自建Gateway；保留数量与内存摘要，绝不输出堆内容或凭证。
        pid = self.gateway_process.pid
        raw = b3.b2.command([str(Path(os.environ['JAVA_HOME']) / 'bin/jcmd'), str(pid), 'GC.class_histogram'])
        match = re.search(r'^\s*\d+:\s+(\d+)\s+(\d+)\s+io\.github\.resilience4j\.circuitbreaker\.internal\.CircuitBreakerStateMachine\s', raw, re.M)
        rss = int(b3.b2.command(['ps', '-p', str(pid), '-o', 'rss=']))
        demo_rss = int(b3.b2.command(['ps', '-p', str(self.demo_process.pid), '-o', 'rss=']))
        total = re.search(r'^Total\s+(\d+)\s+(\d+)', raw, re.M)
        return {'breakerInstances': int(match[1]) if match else 0,
                'breakerShallowBytes': int(match[2]) if match else 0, 'gatewayRssKiB': rss, 'demoRssKiB': demo_rss,
                'liveHeapBytesAfterFullGc': int(total[2]) if total else None}


def identity_and_proxy(lab):
    gw = lab.gateways[0]
    # 1. 在临时库补角色夹具，复用本Lab随机密码哈希；不依赖用户固定端口/socket。
    lab.sql("INSERT INTO fg_principal(principal_type,username,password_hash,role,scopes_json,enabled) SELECT 'ADMIN',LOWER(r.role),p.password_hash,r.role,'[]',1 FROM fg_principal p JOIN (SELECT 'VIEWER' role UNION ALL SELECT 'OPERATOR') r WHERE p.username='block2-admin'")
    tokens = {role: lab.expect(200, '/admin/auth/login', body={'username': role, 'password': lab.password})['token']
              for role in ('viewer', 'operator')}
    lab.expect(401, '/internal/snapshots/current')
    lab.expect(401, '/admin/auth/login', body={'username': 'missing', 'password': lab.password})
    lab.expect(401, '/admin/auth/login', body={'username': 'block2-admin', 'password': 'incorrect'})
    lab.expect(200, '/admin/services', token=tokens['viewer'])
    lab.expect(403, '/admin/services', token=tokens['viewer'], body={})
    lab.expect(403, '/admin/callers', token=tokens['operator'], body={'username': 'forbidden', 'scopes': []})
    for uri in (lab.control, 'http://169.254.169.254:80', f'http://localhost:{lab.demo_port}', f'http://127.0.0.1:{lab.demo_port}/demo'):
        lab.admin_call(400, '/admin/services', body={'code': 'evil', 'name': 'bad', 'upstreamUri': uri, 'enabled': True})
    for public in ('/api/**', '/admin/test'):
        lab.admin_call(400, '/admin/apis', body={'serviceId': lab.service['id'], 'method': 'GET', 'publicPath': public, 'upstreamPath': '/demo/fast/{id}', 'isPublic': True, 'enabled': True})
    status, data, headers = lab.raw('/api/demo/fast/123?tag=abc', base=gw, headers={'X-Request-Id': 'forged', 'X-User-Id': 'admin'})
    assert status == 200 and data['requestId'] == headers['X-Request-Id'] != 'forged' and data['tag'] == 'abc'
    tag = 'a&中+%'
    data = lab.expect(200, '/api/demo/fast/a%20b?tag=' + urllib.parse.quote(tag), base=gw)
    assert data['id'] == 'a b' and data['tag'] == tag
    assert lab.request('/api/demo/fast/a%2Fb', base=gw)[0] in (400, 404)
    body = {'name': '中文', 'nested': [1, None, True]}
    status, data, headers = lab.raw('/api/demo/echo', body=json.dumps(body).encode(), base=gw, headers={'X-Demo-Header': 'hello'})
    assert status == 201 and data['body'] == body and data['demoHeader'] == 'hello' and data['requestId'] == headers['X-Request-Id']
    assert lab.raw('/api/demo/echo', body=b'[]', base=gw)[0] == 400
    for value, status in (('0', 200), ('100', 200), ('2000', 200), ('-1', 400), ('2001', 400), ('abc', 400)):
        lab.expect(status, '/api/demo/slow?delayMs=' + value, base=gw)
    for path in ('/unknown', '/admin/services', '/internal/snapshots/current', '/actuator/env', '/actuator/gateway/routes'):
        lab.expect(404, path, base=gw)
    for asset in ('', 'guide.html', 'acceptance.html', 'style.css', 'app.js'):
        lab.expect(200, '/test-console/' + asset)
    origin = 'http://127.0.0.1:8081'
    status, _, headers = lab.raw('/api/demo/echo', base=gw, method='OPTIONS', headers={'Origin': origin, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,content-type'})
    assert status == 200 and headers.get('Access-Control-Allow-Origin') == origin
    _, _, headers = lab.raw('/api/demo/fast/1', base=gw, headers={'Origin': 'http://evil.invalid'})
    assert headers.get('Access-Control-Allow-Origin') is None
    private = '/api/private/fast/1'
    lab.expect(401, private, base=gw, token=lab.admin)
    lab.expect(401, private, base=gw)
    good = lab.admin_call(201, '/admin/callers', body={'username': 'b4-caller', 'scopes': ['demo:read']})
    lab.expect(200, private, base=gw, token=good['token'])
    lab.expect(401, '/admin/services', token=good['token'])
    digest = hashlib.sha256(good['token'].encode()).hexdigest()
    assert 0 < int(lab.redis('TTL', 'fg:auth:caller:' + digest)) <= 3600
    lab.admin_call(204, f"/admin/callers/{good['id']}/revoke", body={})
    assert lab.redis('EXISTS', 'fg:auth:caller:' + digest) == '0'
    assert lab.redis('EXISTS', 'fg:auth:caller-index:' + str(good['id'])) == '0'
    lab.expect(401, private, base=gw, token=good['token'])
    expiring = lab.admin_call(201, '/admin/callers', body={'username': 'b4-expiring', 'scopes': ['demo:read']})
    digest = hashlib.sha256(expiring['token'].encode()).hexdigest()
    lab.redis('EXPIRE', 'fg:auth:caller:' + digest, 1)
    lab.redis('EXPIRE', 'fg:auth:caller-index:' + str(expiring['id']), 1)
    b3.b2.wait(lambda: lab.request(private, base=gw, token=expiring['token'])[0] == 401, 'Caller真实过期')
    lab.expect(204, '/admin/auth/logout', token=tokens['viewer'], body={})
    lab.expect(401, '/admin/services', token=tokens['viewer'])
    digest = hashlib.sha256(tokens['operator'].encode()).hexdigest()
    lab.redis('EXPIRE', 'fg:auth:admin:' + digest, 1)
    b3.b2.wait(lambda: lab.request('/admin/services', token=tokens['operator'])[0] == 401, '管理会话真实过期')
    # 2. 两个同时CAS写入只有一个成功，历史记录数量和内容保持一致。
    old = lab.sql('SELECT snapshot_json FROM fg_config_release WHERE version=1')
    version = lab.version
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        statuses = list(pool.map(lambda _: lab.request('/admin/releases/publish', token=lab.admin, body={'expectedVersion': version})[0], range(2)))
    assert sorted(statuses) == [200, 409], statuses
    lab.version += 1; lab.applied()
    assert lab.sql('SELECT COUNT(*) FROM fg_config_release') == str(lab.version)
    assert lab.sql('SELECT snapshot_json FROM fg_config_release WHERE version=1') == old
    print('PASS 隔离Block0/1补充：角色/SSRF/编码/Header/Body/状态/TTL/撤销/CORS/并发CAS/历史不变', flush=True)


def dimensional_order(lab):
    """在本Lab专属Redis设置明确桶余额，实证每维原子扣减且前层尝试量不退还。"""
    caller = lab.admin_call(201, '/admin/callers', body={'username': 'b4-dimensions', 'scopes': ['demo:read']})
    scopes = [('GLOBAL', 0), ('SERVICE', lab.service['id']), ('API', lab.apis['private']['id']),
              ('ROUTE', lab.routes['private']['id']), ('CALLER', caller['id'])]
    policies = [lab.policy('b4-' + scope.lower(), 'RATE_LIMIT', scope, target,
                          {'capacity': 10, 'refillPerSecond': 1}) for scope, target in scopes]
    lab.publish(); path = '/api/private/fast/1'; gw = lab.gateways[0]
    lab.expect(200, path, base=gw, token=caller['token'])
    keys = [lab.redis('KEYS', f"fg:rl:{p['id']}:*") for p in policies]
    assert all(key and '\n' not in key for key in keys)
    for rejected in range(5):
        # 1. 冻结补充时间只用于独占实验桶；请求仍经过真实鉴权、规则及Lua。
        future = int(lab.redis('TIME').splitlines()[0]) * 1000 + 60000
        for index, key in enumerate(keys):
            lab.redis('HSET', key, 'tokensMilli', 0 if index == rejected else 10000, 'lastMs', future)
        status, body, _ = lab.request(path, base=gw, token=caller['token'])
        assert status == 429 and body['policyCode'] == policies[rejected]['code'], (status, body)
        balances = [int(lab.redis('HGET', key, 'tokensMilli')) for key in keys]
        # 2. 前层扣一次，命中层拒绝，后层完全不访问；不是跨Key事务回滚。
        assert balances == [9000 if index < rejected else 0 if index == rejected else 10000
                            for index in range(5)], balances
    for policy in policies: lab.update(policy, enabled=False)
    lab.publish()
    print('PASS 真实五维GLOBAL→SERVICE→API→ROUTE→CALLER顺序/Lua扣减/后层不访问/前层不退款', flush=True)


def capacity_and_cancel(lab):
    gw = lab.gateways[0]
    direct = f'http://127.0.0.1:{lab.demo_port}'
    # 3. 真实TCP断开时网关许可释放；下游Servlet仍可能运行，分开观测。
    rule = lab.policy('b4-inflight', 'CONCURRENCY', 'ROUTE', lab.routes['slow']['id'], {'maxConcurrent': 1})
    lab.publish()
    with socket.create_connection(('127.0.0.1', lab.gateway_ports[0])) as client:
        client.sendall(b'GET /api/demo/slow?delayMs=2000 HTTP/1.1\r\nHost: localhost\r\n\r\n')
        b3.b2.wait(lambda: lab.expect(200, '/demo/observation', base=direct)['inFlight'] > 0, '取消前下游已进入')
        assert lab.expect(503, '/api/demo/slow?delayMs=0', base=gw)['code'] == 'CONCURRENCY_LIMITED'
    b3.b2.wait(lambda: lab.request('/api/demo/slow?delayMs=0', base=gw)[0] == 200, '断开客户端后许可可再入')
    servlet_active = lab.expect(200, '/demo/observation', base=direct)['inFlight']
    assert servlet_active > 0, '应在原Servlet尚未完成时确认网关许可已释放'
    b3.b2.wait(lambda: lab.expect(200, '/demo/observation', base=direct)['inFlight'] == 0, 'Servlet自己结束')
    lab.stop(lab.processes[0])
    network_status = lab.request('/api/demo/slow?delayMs=0', base=gw)[0]
    assert network_status in (500, 502, 503), network_status
    lab.app('flowgate-demo')
    b3.b2.wait(lambda: lab.request('/api/demo/slow?delayMs=0', base=gw)[0] == 200, '下游网络失败后许可归还')
    lab.update(rule, enabled=False); lab.add_fault()
    params = {'failureRateThreshold': 50, 'slidingWindowSize': 4, 'minimumNumberOfCalls': 2, 'waitDurationMs': 3000}
    cb = lab.policy('b4-revisions', 'CIRCUIT_BREAKER', 'ROUTE', lab.routes['fault']['id'], params)
    lab.publish(); lab.expect(200, '/api/demo/fault?mode=ok', base=gw)
    before = lab.histogram(); versions = []
    for number in range(20):
        lab.update(cb, {**params, 'waitDurationMs': 3100 + number * 100})
        lab.publish(); lab.expect(200, '/api/demo/fault?mode=ok', base=gw)
        versions.append({'version': lab.version, 'revision': lab.state()['revision']})
    after = lab.histogram()
    assert after['breakerInstances'] - before['breakerInstances'] == 20, (before, after)
    lab.capacity = {'before': before, 'after': after, 'revisions': versions}
    lab.expect(500, '/api/demo/fault?mode=500', base=gw)
    lab.expect(503, '/api/demo/fault?mode=500', base=gw)
    b3.b2.wait(lambda: lab.state()['state'] == 'OPEN', '容量测试后真实熔断事件')
    metric = lab.expect(200, '/actuator/metrics/flowgate.protection.events', base=gw)
    assert {t['tag'] for t in metric['availableTags']} == {'routeId', 'kind', 'outcome'}
    # 4. 只停止自己第二个节点，等待真实15秒新鲜度边界，存活节点继续代理。
    lab.stop(lab.processes[3])
    b3.b2.wait(lambda: next(n for n in lab.admin_call(200, '/admin/nodes') if n['nodeId'] == 'block2-1')['freshness'] == 'STALE', '真实离线节点STALE', seconds=25)
    lab.expect(200, '/api/demo/fast/1', base=gw)
    lab.app('flowgate-gateway', {'GATEWAY_PORT': str(lab.gateway_ports[1]), 'FLOWGATE_NODE_ID': 'block2-1'})
    b3.b2.wait(lambda: next(n for n in lab.admin_call(200, '/admin/nodes') if n['nodeId'] == 'block2-1')['freshness'] == 'ONLINE', '第二节点恢复ONLINE')
    lab.expect(200, '/api/demo/fast/1', base=lab.gateways[1])
    print('PASS TCP取消/网络失败许可归还/20修订真实实例累计/低基数指标/第二节点STALE恢复', flush=True)
    return {'before': before, 'after': after, 'revisions': versions, 'servletActiveAfterClientCancel': servlet_active,
            'networkFailureStatus': network_status, 'limitation': '旧CB实例完整GC后仍保留；V1适用于低频配置本机演示，不承诺无限修订稳定'}


def main():
    target = ROOT / ('.local/acceptance/block4-boundaries-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%f') + '.json')
    if target.exists(): raise SystemExit('证据已存在，请先归档并指定新路径，不覆盖旧结果')
    lab = Lab(); passed = False; capacity = None
    try:
        lab.start(); identity_and_proxy(lab); dimensional_order(lab); capacity = capacity_and_cancel(lab); passed = True
    finally:
        lab.close()
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('x') as stream:
            os.chmod(target, 0o600)
            json.dump({'passed': passed, 'recordedAt': datetime.now(timezone.utc).isoformat(), 'capacity': capacity or getattr(lab, 'capacity', None),
                       'requests': lab.evidence}, stream, ensure_ascii=False, indent=2)
        print('本机脱敏证据：', target, 'passed=', passed, flush=True)


if __name__ == '__main__': main()

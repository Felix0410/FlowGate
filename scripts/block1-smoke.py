#!/usr/bin/env python3
"""P1 真实联调：专用 MySQL socket、新建临时库、自有 Redis 和三个 JVM，不触碰既有服务。"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JAVA = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'
BASE = 'http://127.0.0.1:8081'
GW = 'http://127.0.0.1:8080'
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(path, body=None, token=None, base=BASE, method=None, headers=None):
    headers = dict(headers or {})
    if token: headers['Authorization'] = 'Bearer ' + token
    if body is not None: headers['Content-Type'] = 'application/json'
    raw = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=raw, headers=headers, method=method)
    try: response = HTTP.open(req, timeout=10)
    except urllib.error.HTTPError as error: response = error
    with response:
        text = response.read().decode()
        try: data = json.loads(text) if text else None
        except ValueError: data = text
        return response.status, data, response.headers


def expect(status, path, **kwargs):
    actual, body, headers = call(path, **kwargs)
    assert actual == status, (path, actual, status, body if '/auth/login' not in path and '/callers' not in path else '(凭证响应省略)')
    return body


def stop(process):
    if process and process.poll() is None:
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill(); process.wait()


def wait(fn, label):
    end = time.monotonic() + 35
    while time.monotonic() < end:
        try:
            if fn(): return
        except (OSError, urllib.error.URLError, subprocess.CalledProcessError): pass
        time.sleep(.2)
    raise AssertionError('等待超时: ' + label)


def main():
    # 1. 使用显式专用 socket，唯一数据库；不清空用户 Redis 或覆盖已有进程。
    sock = os.environ['FG_SMOKE_MYSQL_SOCKET']
    assert Path(sock).exists(), '需要专用可访问 MySQL socket'
    for port in (8080,8081,8085):
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(('127.0.0.1', port))
    temp = Path(tempfile.mkdtemp(prefix='flowgate-block1-'))
    schema = 'fg_block1_smoke_' + secrets.token_hex(6)
    mysql = ['mysql','--no-defaults','--socket=' + sock,'-u',os.environ.get('FG_SMOKE_MYSQL_USER','root'),'-N','-B']
    def sql(statement, database=True):
        return subprocess.run(mysql + ([schema] if database else []), input=statement, text=True, check=True, capture_output=True).stdout.strip()
    sql('CREATE DATABASE ' + schema + ' CHARACTER SET utf8mb4', False)
    sql((ROOT/'sql/flowgate-init.sql').read_text())
    # 验证 DDL 可重复执行且不会覆盖 state。
    sql((ROOT/'sql/flowgate-init.sql').read_text())
    processes, handles = [], []
    def spawn(command, log, env=None):
        handle = open(temp/log,'a'); handles.append(handle)
        process = subprocess.Popen(command, stdout=handle, stderr=subprocess.STDOUT, env=env, start_new_session=True)
        processes.append(process); return process
    with socket.socket() as probe:
        probe.bind(('127.0.0.1',0)); redis_port = probe.getsockname()[1]
    def start_redis():
        return spawn(['redis-server','--bind','127.0.0.1','--port',str(redis_port),'--save','','--appendonly','no'], 'redis.log')
    def redis(*args):
        return subprocess.run(['redis-cli','-p',str(redis_port),'--raw',*args], capture_output=True,text=True,check=True).stdout.strip()
    redis_process = start_redis()
    keep = False
    try:
        wait(lambda: redis('PING') == 'PONG','Redis')
        # 2. 随机密码从 stdin 交给本地 BCrypt 工具，不进入命令行参数或仓库。
        import zipfile
        with zipfile.ZipFile(ROOT/'flowgate-control/target/flowgate-control-0.0.1-SNAPSHOT.jar') as jar:
            libs = []
            for name in jar.namelist():
                if name.startswith('BOOT-INF/lib/') and ('spring-security-crypto-' in name or 'commons-logging-' in name):
                    target = temp/Path(name).name; target.write_bytes(jar.read(name)); libs.append(str(target))
        password = secrets.token_urlsafe(18)
        hashed = subprocess.run([JAVA,'-cp',os.pathsep.join(libs),str(ROOT/'scripts/PasswordHashTool.java')],input=password+'\n',text=True,capture_output=True,check=True).stdout.strip()
        assert hashed.startswith('$2')
        sql("INSERT INTO fg_principal(principal_type,username,password_hash,role,scopes_json,enabled) VALUES " + ','.join("('ADMIN','"+role.lower()+"','"+hashed+"','"+role+"','[]',1)" for role in ('VIEWER','OPERATOR')))
        key = secrets.token_urlsafe(32)
        env = dict(os.environ,FG_MYSQL_URL='jdbc:mysql://127.0.0.1:13306/'+schema+'?connectionTimeZone=UTC',FG_MYSQL_USERNAME=os.environ.get('FG_SMOKE_MYSQL_USER','root'),FG_MYSQL_PASSWORD='',FG_REDIS_PORT=str(redis_port),FG_REDIS_PASSWORD='',FG_REDIS_DATABASE='0',FG_BOOTSTRAP_ADMIN_USERNAME='smoke-admin',FG_BOOTSTRAP_ADMIN_BCRYPT=hashed,FLOWGATE_INTERNAL_KEY=key,FLOWGATE_POLL_MS='500',FLOWGATE_NODE_ID='block1-smoke-node')
        def app(module): return spawn([JAVA,'-jar',str(ROOT/module/'target'/f'{module}-0.0.1-SNAPSHOT.jar')],module+'.log',env)
        demo, control, gateway = app('flowgate-demo'), app('flowgate-control'), app('flowgate-gateway')
        wait(lambda: call('/status')[0] == 200,'Control')
        wait(lambda: call('/actuator/health',base=GW)[0] == 200,'Gateway')
        expect(404,'/api/demo/fast/123',base=GW)
        expect(401,'/admin/services')
        expect(401,'/internal/snapshots/current')
        expect(401,'/admin/auth/login',body={'username':'missing','password':password})
        expect(401,'/admin/auth/login',body={'username':'smoke-admin','password':'incorrect'})
        def login(username): return expect(200,'/admin/auth/login',body={'username':username,'password':password})['token']
        admin, viewer, operator = login('smoke-admin'), login('viewer'), login('operator')
        expect(200,'/admin/services',token=viewer)
        expect(403,'/admin/services',token=viewer,body={})
        expect(403,'/admin/callers',token=operator,body={'username':'bad','scopes':[]})
        expect(200,'/admin/releases/current',token=viewer)
        assert expect(200,'/admin/releases/current',token=admin)['version']==0
        assert expect(200,'/admin/nodes',token=admin)==[]
        print('PASS 01 三 JVM / MySQL / Redis，V0 无静态旁路，管理员正确/错误登录与角色边界',flush=True)

        # 3. 保存草稿并验证边界；草稿不会自动进入 Gateway。
        service = expect(201,'/admin/services',token=operator,body={'code':'demo','name':'本地 Demo','upstreamUri':'http://127.0.0.1:8085','enabled':True})
        for uri in ('http://127.0.0.1:8081','http://localhost:8085','http://127.0.0.1:8085/demo','http://169.254.169.254:80'):
            expect(400,'/admin/services',token=admin,body={'code':'evil','name':'bad','upstreamUri':uri,'enabled':True})
        expect(400,'/admin/apis',token=admin,body={'serviceId':99999,'method':'GET','publicPath':'/api/a','upstreamPath':'/demo/a','isPublic':True,'enabled':True})
        expect(400,'/admin/apis',token=admin,body={'serviceId':service['id'],'method':'GET','publicPath':'/api/**','upstreamPath':'/demo/fast/{id}','isPublic':True,'enabled':True})
        apis={}
        def add_api(name, public_path, upstream, public=True, method='GET'):
            api=expect(201,'/admin/apis',token=operator,body={'serviceId':service['id'],'method':method,'publicPath':public_path,'upstreamPath':upstream,'isPublic':public,'requiredScope':'' if public else 'demo:read','enabled':True})
            expect(201,'/admin/routes',token=operator,body={'apiId':api['id'],'routeId':name,'priority':0,'enabled':True}); apis[name]=api; return api
        add_api('demo-fast','/api/demo/fast/{id}','/demo/fast/{id}')
        add_api('demo-echo','/api/demo/echo','/demo/echo',method='POST')
        add_api('demo-slow','/api/demo/slow','/demo/slow')
        policy=expect(201,'/admin/policies',token=admin,body={'code':'demo-rate','type':'RATE_LIMIT','scope':'GLOBAL','parametersJson':'{"capacity":100,"refillPerSecond":10}','enabled':True})
        expect(400,'/admin/policies',token=admin,body={'code':'bad','type':'RATE_LIMIT','scope':'GLOBAL','parametersJson':'{"capacity":"100"}','enabled':True})
        expect(400,'/admin/policies',token=admin,body={'code':'broken-json','type':'RATE_LIMIT','scope':'GLOBAL','parametersJson':'{','enabled':True})
        expect(400,f"/admin/policies/{policy['id']}/bindings",token=admin,body={'targetType':'SERVICE','targetId':service['id']})
        expect(201,f"/admin/policies/{policy['id']}/bindings",token=admin,body={'targetType':'GLOBAL','targetId':0})
        expect(404,'/api/demo/fast/123',base=GW)
        v1=expect(200,'/admin/releases/publish',token=operator,body={'expectedVersion':0})
        assert v1['version']==1 and v1['status']=='PERSISTED'
        def applied(version):
            rows=expect(200,'/admin/nodes',token=admin)
            return rows and rows[0]['appliedVersion']==version
        wait(lambda: applied(1),'V1 实际缓存与节点反馈')
        v1_payload=sql('SELECT snapshot_json FROM fg_config_release WHERE version=1')
        expect(409,'/admin/releases/publish',token=admin,body={'expectedVersion':0})
        print('PASS 02 CRUD/引用/URI/路径/策略/绑定校验，V1 发布和节点实际反馈，过期版本 409',flush=True)
        status,fast,headers=call('/api/demo/fast/123?tag=abc',base=GW,headers={'X-Request-Id':'forged'})
        assert status==200 and fast=={'id':'123','tag':'abc','requestId':headers['X-Request-Id']} and headers['X-Request-Id']!='forged'
        tag='a&中+%'
        special=expect(200,'/api/demo/fast/a%20b?tag='+urllib.parse.quote(tag),base=GW)
        assert special['id']=='a b' and special['tag']==tag, special
        assert call('/api/demo/fast/a%2Fb',base=GW)[0] in (400,404)
        status,echo,headers=call('/api/demo/echo',base=GW,body={'name':'中文','nested':[1,None,True]},headers={'X-Demo-Header':'hello'})
        assert status==201 and echo['demoHeader']=='hello' and echo['body']=={'name':'中文','nested':[1,None,True]} and echo['requestId']==headers['X-Request-Id']
        assert echo['requestId']!=fast['requestId']
        for value,status in (('0',200),('100',200),('2000',200),('-1',400),('2001',400),('abc',400)):
            expect(status,'/api/demo/slow?delayMs='+value,base=GW)
        expect(400,'/api/demo/echo',base=GW,body=b'[]')
        expect(404,'/unknown',base=GW)
        for path in ('/admin/services','/internal/snapshots/current','/actuator/env'):
            expect(404,path,base=GW)
        assert 'spring.cloud.gateway.requests' in expect(200,'/actuator/metrics',base=GW)['names']
        print('PASS 03 块0 三 API/Body/Header/状态/Trace/特殊字符与 Query/慢请求边界/指标',flush=True)

        # 4. 私有路由 V2、调用方用途/Scope/撤销/TTL，再回滚为 V3。
        add_api('private-fast','/api/private/fast/{id}','/demo/fast/{id}',False)
        expect(200,'/admin/releases/publish',token=operator,body={'expectedVersion':1})
        wait(lambda: applied(2),'V2')
        private='/api/private/fast/123'
        expect(401,private,base=GW)
        expect(401,private,base=GW,token=admin)
        correct=expect(201,'/admin/callers',token=admin,body={'username':'good-caller','scopes':['demo:read']})
        wrong=expect(201,'/admin/callers',token=admin,body={'username':'wrong-caller','scopes':['other:read']})
        expect(403,private,base=GW,token=wrong['token'])
        expect(200,private,base=GW,token=correct['token'])
        expect(401,'/admin/services',token=correct['token'])
        digest=hashlib.sha256(correct['token'].encode()).hexdigest()
        assert 0<int(redis('TTL','fg:auth:caller:'+digest))<=3600
        assert redis('GET','fg:auth:caller-index:'+str(correct['id']))==digest
        expect(204,f"/admin/callers/{correct['id']}/revoke",token=admin,body={})
        assert redis('EXISTS','fg:auth:caller:'+digest)== '0'
        assert redis('EXISTS','fg:auth:caller-index:'+str(correct['id']))=='0'
        expect(401,private,base=GW,token=correct['token'])
        expires=expect(201,'/admin/callers',token=admin,body={'username':'expiring-caller','scopes':['demo:read']})
        digest=hashlib.sha256(expires['token'].encode()).hexdigest()
        redis('EXPIRE','fg:auth:caller:'+digest,'1'); redis('EXPIRE','fg:auth:caller-index:'+str(expires['id']),'1'); time.sleep(1.2)
        expect(401,private,base=GW,token=expires['token'])
        still_valid=expect(201,'/admin/callers',token=admin,body={'username':'redis-outage','scopes':['demo:read']})
        stop(redis_process)
        expect(503,private,base=GW,token=still_valid['token'])
        expect(200,'/api/demo/fast/123',base=GW)
        expect(503,'/admin/services',token=admin)
        redis_process=start_redis(); wait(lambda: redis('PING')=='PONG','Redis 恢复')
        admin=login('smoke-admin'); operator=login('operator')
        expect(200,'/admin/releases/1/rollback',token=operator,body={'expectedVersion':2})
        wait(lambda: applied(3),'V3 回滚')
        expect(404,private,base=GW); expect(200,'/api/demo/fast/123',base=GW)
        assert sql('SELECT snapshot_json FROM fg_config_release WHERE version=1')==v1_payload
        print('PASS 04 V2/Caller 401/403/200/用途隔离/TTL/原子撤销/Redis断联503/公开路由保留/V3回滚',flush=True)

        # 5. 冲突发布不能留下孤立历史；并发 CAS 只有一个成功。
        conflict=add_api('conflict','/api/demo/fast/fixed','/demo/fast/fixed')
        expect(400,'/admin/releases/publish',token=admin,body={'expectedVersion':3})
        assert sql('SELECT current_version FROM fg_config_state WHERE id=1')=='3'
        assert sql('SELECT COUNT(*) FROM fg_config_release')=='3'
        conflict['enabled']=False
        expect(200,f"/admin/apis/{conflict['id']}",token=admin,method='PUT',body={k:v for k,v in conflict.items() if k!='id'})
        # 同时停用关联路由，避免把停用父级偷偷当成仍有效路由。
        routes=expect(200,'/admin/routes',token=admin)
        route=next(r for r in routes if r['apiId']==conflict['id']); route['enabled']=False
        expect(200,f"/admin/routes/{route['id']}",token=admin,method='PUT',body={k:v for k,v in route.items() if k!='id'})
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda _:call('/admin/releases/publish',token=operator,body={'expectedVersion':3})[0],range(2)))
        assert sorted(results)==[200,409],results
        wait(lambda: applied(4),'并发唯一版本 V4')
        expect(200,'/admin/releases/1/rollback',token=operator,body={'expectedVersion':4}); wait(lambda: applied(5),'V5 恢复公开演示')
        assert any(row['action']=='ROLLBACK' for row in expect(200,'/admin/audits',token=admin))
        stop(control)
        expect(200,'/api/demo/fast/123',base=GW)
        # 重启无可信快照时必须关闭所有动态入口。
        stop(gateway); gateway=app('flowgate-gateway'); wait(lambda:call('/actuator/health',base=GW)[0]==200,'离线首启 Gateway')
        expect(404,'/api/demo/fast/123',base=GW)
        control=app('flowgate-control'); wait(lambda:call('/status')[0]==200,'Control 恢复')
        wait(lambda:call('/api/demo/fast/123',base=GW)[0]==200,'重新拉取可信快照')
        print('PASS 05 冲突发布事务无孤立版本/并发CAS唯一成功/历史只读/审计/Control离线旧版保留/无快照重启关闭',flush=True)
        expect(200,'/test-console/')
        for asset in ('app.js','style.css'): expect(200,'/test-console/'+asset)
        status,_,headers=call('/api/demo/echo',base=GW,method='OPTIONS',headers={'Origin':BASE,'Access-Control-Request-Method':'POST','Access-Control-Request-Headers':'authorization,content-type'})
        assert status==200 and headers.get('Access-Control-Allow-Origin')==BASE
        for path in ('/actuator/health','/actuator/metrics/spring.cloud.gateway.requests'):
            status,_,headers=call(path,base=GW,headers={'Origin':BASE})
            assert status==200 and headers.get('Access-Control-Allow-Origin')==BASE
        status,_,headers=call('/api/demo/fast/123',base=GW,headers={'Origin':'http://evil.invalid'})
        assert headers.get('Access-Control-Allow-Origin') is None
        # 注销与管理会话到期的真实 Redis 验证。
        token=login('viewer'); expect(204,'/admin/auth/logout',token=token,body={}); expect(401,'/admin/services',token=token)
        token=login('viewer'); redis('EXPIRE','fg:auth:admin:'+hashlib.sha256(token.encode()).hexdigest(),'1'); time.sleep(1.2); expect(401,'/admin/services',token=token)
        print('PASS 06 页面资源/CORS预检/非白名单Origin拒绝/退出与管理会话到期',flush=True)
        keep=os.environ.get('FG_SMOKE_KEEP')=='1'
        if keep:
            credentials=temp/'demo-credentials.json'
            credentials.write_text(json.dumps({'username':'smoke-admin','password':password,'schema':schema,'redisPort':redis_port,'mysqlSocket':sock,'pids':[p.pid for p in processes if p.poll() is None]})); credentials.chmod(0o600)
            print('Demo 保持运行；一次性本地登录文件（0600）：',credentials,flush=True)
        print('全部 P1 定向联调组通过。日志目录:',temp,flush=True)
    finally:
        if not keep:
            for process in reversed(processes): stop(process)
            sql('DROP DATABASE '+schema,False)
        for handle in handles: handle.close()

if __name__=='__main__': main()

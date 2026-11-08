#!/usr/bin/env python3
"""块2独立真实联调：随机端口/专用容器/临时库，退出仅清理本脚本资源。"""
import concurrent.futures
import importlib.util
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
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[1]
JAVA=str(Path(os.environ['JAVA_HOME'])/'bin/java')
HTTP=urllib.request.build_opener(urllib.request.ProxyHandler({}))

def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0)); return sock.getsockname()[1]

def command(args,**kwargs):
    return subprocess.run(args,text=True,capture_output=True,check=True,**kwargs).stdout.strip()

def wait(check,label,seconds=90):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            if check(): return
        except (OSError,subprocess.CalledProcessError,urllib.error.URLError): pass
        time.sleep(.2)
    raise AssertionError('等待超时：'+label)

class Lab:
    def __init__(self):
        self.temp=Path(tempfile.mkdtemp(prefix='flowgate-block2-'))
        self.containers=[]; self.processes=[]; self.handles=[]
        self.password=secrets.token_urlsafe(20)
        self.demo_port=port(); self.control_port=port(); self.gateway_ports=[port(),port()]
        self.control=f'http://127.0.0.1:{self.control_port}'
        self.gateways=[f'http://127.0.0.1:{p}' for p in self.gateway_ports]
        self.admin=None; self.version=0
    def docker(self,image,args,env=None):
        name='fg-block2-'+secrets.token_hex(5); self.containers.append(name)
        command(['docker','run','-d','--name',name,*args,image],env=env)
        return name
    def sql(self,statement):
        return command(['docker','exec','-i','-e','MYSQL_PWD='+self.password,self.mysql,'mysql','-uroot','-N','-B','flowgate'],input=statement)
    def redis(self,*args):
        return command(['docker','exec',self.redis_name,'redis-cli','--raw',*map(str,args)])
    def artifact(self,module):
        # 运行中的Boot Jar必须不可变，避免另一轮Maven重打包破坏嵌套类延迟加载。
        directory=self.temp/'artifacts'; directory.mkdir(exist_ok=True)
        target=directory/(module+'.jar')
        if not target.exists(): shutil.copy2(ROOT/module/'target'/f'{module}-0.0.1-SNAPSHOT.jar',target)
        return target
    def app(self,module,extra=None):
        env=dict(self.env,**(extra or {}))
        handle=open(self.temp/(module+'-'+str(len(self.processes))+'.log'),'w'); self.handles.append(handle)
        process=subprocess.Popen([JAVA,'-jar',str(self.artifact(module))],env=env,stdout=handle,stderr=subprocess.STDOUT)
        self.processes.append(process); return process
    def request(self,path,body=None,base=None,token=None,method=None):
        headers={}
        if token: headers['Authorization']='Bearer '+token
        if body is not None: headers['Content-Type']='application/json'
        req=urllib.request.Request((base or self.control)+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
        try: response=HTTP.open(req,timeout=8)
        except urllib.error.HTTPError as error: response=error
        with response:
            text=response.read().decode()
            try: data=json.loads(text) if text else None
            except ValueError: data=text
            return response.status,data,dict(response.headers)
    def expect(self,status,path,**kwargs):
        actual,data,_=self.request(path,**kwargs)
        assert actual==status,(path,status,actual,'凭证响应省略' if '/auth/' in path or '/callers' in path else data)
        return data
    def admin_call(self,status,path,**kwargs): return self.expect(status,path,token=self.admin,**kwargs)
    def start(self,hotspot=False):
        # 1. 独立 Docker MySQL 与 Redis，无挂载用户数据卷，不修改已有容器或 .env。
        env=dict(os.environ,MYSQL_ROOT_PASSWORD=self.password,MYSQL_DATABASE='flowgate')
        self.mysql=self.docker('mysql:8.4',['-p','127.0.0.1:0:3306','-e','MYSQL_ROOT_PASSWORD','-e','MYSQL_DATABASE'],env)
        self.redis_name=self.docker('redis:7-alpine',['-p','127.0.0.1:0:6379'])
        mysql_port=command(['docker','port',self.mysql,'3306']).split(':')[-1]
        redis_port=command(['docker','port',self.redis_name,'6379']).split(':')[-1]
        wait(lambda:self.sql('SELECT 1')=='1','MySQL')
        self.sql((ROOT/'sql/flowgate-init.sql').read_text())
        with zipfile.ZipFile(ROOT/'flowgate-control/target/flowgate-control-0.0.1-SNAPSHOT.jar') as jar:
            libs=[]
            for name in jar.namelist():
                if name.startswith('BOOT-INF/lib/') and ('spring-security-crypto-' in name or 'commons-logging-' in name):
                    dest=self.temp/Path(name).name; dest.write_bytes(jar.read(name)); libs.append(str(dest))
        hashed=command([JAVA,'-cp',os.pathsep.join(libs),str(ROOT/'scripts/PasswordHashTool.java')],input=self.password+'\n')
        self.env=dict(os.environ,FG_MYSQL_URL=f'jdbc:mysql://127.0.0.1:{mysql_port}/flowgate?connectionTimeZone=UTC',FG_MYSQL_USERNAME='root',FG_MYSQL_PASSWORD=self.password,
            FG_REDIS_HOST='127.0.0.1',FG_REDIS_PORT=redis_port,FG_REDIS_PASSWORD='',FG_REDIS_DATABASE='0',FG_BOOTSTRAP_ADMIN_USERNAME='block2-admin',FG_BOOTSTRAP_ADMIN_BCRYPT=hashed,
            FLOWGATE_INTERNAL_KEY=secrets.token_urlsafe(40),CONTROL_PORT=str(self.control_port),DEMO_PORT=str(self.demo_port),FLOWGATE_CONTROL_URI=self.control,
            FLOWGATE_ALLOWED_UPSTREAMS=f'http://127.0.0.1:{self.demo_port}',FLOWGATE_DEMO_STATUS_URI=f'http://127.0.0.1:{self.demo_port}',FLOWGATE_POLL_MS='200',FLOWGATE_DEMO_OBSERVATION='true')
        self.app('flowgate-demo',{'SERVER_TOMCAT_THREADS_MAX':'8','SERVER_TOMCAT_THREADS_MIN_SPARE':'2'} if hotspot else None)
        self.app('flowgate-control')
        for index,p in enumerate(self.gateway_ports): self.app('flowgate-gateway',{'GATEWAY_PORT':str(p),'FLOWGATE_NODE_ID':'block2-'+str(index)})
        wait(lambda:self.request('/status')[0]==200,'Control')
        for gw in self.gateways: wait(lambda gw=gw:self.request('/actuator/health',base=gw)[0]==200,'Gateway')
        self.admin=self.expect(200,'/admin/auth/login',body={'username':'block2-admin','password':self.password})['token']
        service=self.admin_call(201,'/admin/services',body={'code':'demo','name':'Demo','upstreamUri':f'http://127.0.0.1:{self.demo_port}','enabled':True})
        self.service=service; self.routes={}; self.apis={}
        for name,method,path,upstream,public in [('fast','GET','/api/demo/fast/{id}','/demo/fast/{id}',True),('slow','GET','/api/demo/slow','/demo/slow',True),('echo','POST','/api/demo/echo','/demo/echo',True),('private','GET','/api/private/fast/{id}','/demo/fast/{id}',False)]:
            api=self.admin_call(201,'/admin/apis',body={'serviceId':service['id'],'method':method,'publicPath':path,'upstreamPath':upstream,'isPublic':public,'requiredScope':'' if public else 'demo:read','enabled':True})
            self.apis[name]=api
            self.routes[name]=self.admin_call(201,'/admin/routes',body={'apiId':api['id'],'routeId':'demo-'+name,'priority':0,'enabled':True})
        self.publish()
    def publish(self):
        result=self.admin_call(200,'/admin/releases/publish',body={'expectedVersion':self.version}); self.version=result['version']; self.applied()
        return self.version
    def applied(self):
        wait(lambda:len([n for n in self.admin_call(200,'/admin/nodes') if n['appliedVersion']==self.version])==2,'两个网关应用V'+str(self.version))
    def policy(self,code,type,scope,target,params):
        row=self.admin_call(201,'/admin/policies',body={'code':code,'type':type,'scope':scope,'parametersJson':json.dumps(params),'enabled':True})
        self.admin_call(201,f"/admin/policies/{row['id']}/bindings",body={'targetType':scope,'targetId':target}); return row
    def update(self,row,params=None,enabled=True):
        body={k:v for k,v in row.items() if k!='id'}; body['enabled']=enabled
        if params is not None: body['parametersJson']=json.dumps(params)
        self.admin_call(200,f"/admin/policies/{row['id']}",body=body,method='PUT'); row.update(body)
    def close(self):
        # 2. finally 只终止自己创建的进程和容器，错误时也不留下端口占用。
        for process in reversed(self.processes):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=8)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
        for handle in self.handles: handle.close()
        for name in self.containers: subprocess.run(['docker','rm','-f','-v',name],capture_output=True)
        print('本次临时日志目录（未提交）：',self.temp,flush=True)

def lua_tests(lab):
    script=(ROOT/'flowgate-gateway/src/main/resources/lua/token-bucket.lua').read_text()
    key='block2:lua:'+secrets.token_hex(4)
    def run(cap=3,rate=1): return [int(x) for x in lab.redis('EVAL',script,1,key,cap,rate).splitlines()]
    assert run()==[1,0,2000]
    assert run()[0]==1 and run()[0]==1 and run()[0]==0
    assert lab.redis('HLEN',key)=='2' and 0<int(lab.redis('TTL',key))<=60
    time.sleep(1.05); assert run()[0]==1
    now=lab.redis('TIME').splitlines(); future=int(now[0])*1000+int(now[1])//1000+10000
    lab.redis('HSET',key,'tokensMilli',0,'lastMs',future); assert run()[0]==0
    lab.redis('DEL',key)
    # 并发零补充时间窗口内固定容量；冻结时钟状态避免调度抖动误计。
    lab.redis('HSET',key,'tokensMilli',7000,'lastMs',future)
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        results=list(pool.map(lambda _:run(7,1),range(30)))
    # 每次脚本保存 Redis TIME，因此执行时仍可补充少量；必须低于再生一个令牌的1秒。
    assert sum(r[0] for r in results)==7,results
    expiry='block2:expiry'; lab.redis('EVAL',script,1,expiry,1,1)
    return expiry,time.monotonic()

def main():
    lab=Lab()
    try:
        lab.start(); gw=lab.gateways[0]
        expiry,began=lua_tests(lab); print('PASS Lua 初始/耗尽/补充/回拨/整数数组/同Key并发/Hash有界/TTL',flush=True)
        lab.expect(200,'/api/demo/fast/123?tag=abc',base=gw)
        lab.expect(201,'/api/demo/echo',base=gw,body={'name':'中文'})
        lab.expect(200,'/api/demo/slow?delayMs=1',base=gw)
        lab.expect(401,'/api/private/fast/1',base=gw)
        good=lab.admin_call(201,'/admin/callers',body={'username':'good','scopes':['demo:read']})
        bad=lab.admin_call(201,'/admin/callers',body={'username':'bad','scopes':['other:read']})
        lab.expect(403,'/api/private/fast/1',base=gw,token=bad['token'])
        lab.expect(200,'/api/private/fast/1',base=gw,token=good['token'])
        caller_rate=lab.policy('caller-a-rate','RATE_LIMIT','CALLER',good['id'],{'capacity':1,'refillPerSecond':1}); lab.publish()
        lab.expect(401,'/api/private/fast/1',base=gw)
        lab.expect(403,'/api/private/fast/1',base=gw,token=bad['token'])
        assert lab.redis('KEYS',f"fg:rl:{caller_rate['id']}:*")==''
        lab.expect(200,'/api/private/fast/1',base=gw,token=good['token'])
        lab.expect(429,'/api/private/fast/1',base=gw,token=good['token'])
        other=lab.admin_call(201,'/admin/callers',body={'username':'other','scopes':['demo:read']})
        lab.expect(200,'/api/private/fast/1',base=gw,token=other['token'])
        lab.update(caller_rate,enabled=False); lab.publish()
        rate=lab.policy('slow-rate','RATE_LIMIT','ROUTE',lab.routes['slow']['id'],{'capacity':2,'refillPerSecond':1}); lab.publish()
        # 3. 两网关共用同一桶，前两次允许、第三次拒绝；拒绝必须保留Request ID。
        before=lab.expect(200,'/demo/observation',base=f'http://127.0.0.1:{lab.demo_port}')['slow']
        for gateway in lab.gateways: lab.expect(200,'/api/demo/slow?delayMs=0',base=gateway)
        status,body,headers=lab.request('/api/demo/slow?delayMs=0',base=gw)
        assert status==429 and body['code']=='RATE_LIMITED' and body['requestId']==headers['X-Request-Id']
        after=lab.expect(200,'/demo/observation',base=f'http://127.0.0.1:{lab.demo_port}')['slow']
        assert after-before==2,'429不得调用下游'
        keys=lab.redis('KEYS',f"fg:rl:{rate['id']}:*").splitlines(); assert len(keys)==1
        previous=keys[0]; lab.publish(); assert lab.redis('EXISTS',previous)=='1'
        lab.update(rate,{'capacity':3,'refillPerSecond':1}); lab.publish(); lab.expect(200,'/api/demo/slow?delayMs=0',base=gw)
        assert len(lab.redis('KEYS',f"fg:rl:{rate['id']}:*").splitlines())==2
        print('PASS 公私有代理/认证顺序/ROUTE数字映射/两网关共享预算/429/Trace/稳定revision与参数切换',flush=True)
        lab.update(rate,enabled=False)
        concurrency=lab.policy('slow-concurrency','CONCURRENCY','ROUTE',lab.routes['slow']['id'],{'maxConcurrent':1}); lab.publish()
        before=lab.expect(200,'/demo/observation',base=f'http://127.0.0.1:{lab.demo_port}')['slow']
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results=list(pool.map(lambda _:lab.request('/api/demo/slow?delayMs=400',base=gw)[0],range(8)))
        assert 503 in results and 200 in results,results
        after=lab.expect(200,'/demo/observation',base=f'http://127.0.0.1:{lab.demo_port}')['slow']
        assert after-before==results.count(200),'503拒绝不得进入下游'
        # 单机许可不跨节点共享：两个网关各自允许一个。
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            assert list(pool.map(lambda gateway:lab.request('/api/demo/slow?delayMs=200',base=gateway)[0],lab.gateways))==[200,200]
        lab.expect(200,'/api/demo/slow?delayMs=0',base=gw)
        lab.expect(200,'/actuator/metrics/flowgate.admission.requests',base=gw)
        # 重复启用绑定发布必须400，失败不推进版本；停用后恢复发布。
        duplicate=lab.policy('duplicate','CONCURRENCY','ROUTE',lab.routes['slow']['id'],{'maxConcurrent':1})
        lab.admin_call(400,'/admin/releases/publish',body={'expectedVersion':lab.version}); lab.update(duplicate,enabled=False)
        lab.admin_call(409,'/admin/releases/publish',body={'expectedVersion':0})
        lab.expect(200,'/test-console/'); lab.expect(200,'/test-console/app.js')
        # Redis停止仅影响绑定速率路由，未绑定公开fast保持可访问。
        lab.update(concurrency,enabled=False); lab.update(rate,{'capacity':5,'refillPerSecond':1}); lab.publish()
        command(['docker','pause',lab.redis_name]); lab.expect(503,'/api/demo/slow?delayMs=0',base=gw)
        lab.expect(200,'/api/demo/fast/1',base=gw)
        command(['docker','unpause',lab.redis_name]); wait(lambda:lab.redis('PING')=='PONG','Redis恢复')
        wait(lambda:lab.request('/api/demo/slow?delayMs=0',base=gw)[0]==200,'Lettuce重连后恢复准入')
        # Redis容器不持久化，重启后重新登录；回滚保持历史不可变。
        lab.admin=lab.expect(200,'/admin/auth/login',body={'username':'block2-admin','password':lab.password})['token']
        history=lab.sql('SELECT snapshot_json FROM fg_config_release WHERE version=1')
        result=lab.admin_call(200,'/admin/releases/1/rollback',body={'expectedVersion':lab.version}); lab.version=result['version']; lab.applied()
        assert lab.sql('SELECT snapshot_json FROM fg_config_release WHERE version=1')==history
        for _ in range(4): lab.expect(200,'/api/demo/slow?delayMs=0',base=gw)
        print('PASS 本机并发503/两节点本机许可独立/终止后可再入/重复规则400/409/页面/指标/Redis故障与恢复/历史回滚',flush=True)
        # TTL自然过期的单独桶不再触碰；等待最多60秒，不改过期时间来冒充脚本TTL。
        while time.monotonic()-began<61: time.sleep(min(10,61-(time.monotonic()-began)))
        assert lab.redis('EXISTS',expiry)=='0'
        print('PASS 脚本60秒TTL实际自然过期；块2真实联调完成',flush=True)
    finally: lab.close()

if __name__=='__main__': main()

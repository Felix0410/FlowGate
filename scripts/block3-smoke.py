#!/usr/bin/env python3
"""Block3增量实链路：独立临时MySQL/Redis与JVM，不控制电脑界面，不污染已有数据。"""
import importlib.util
import concurrent.futures
from pathlib import Path
import subprocess
import json
import hashlib
from datetime import datetime,timezone
import time

spec=importlib.util.spec_from_file_location('block2_lab',Path(__file__).with_name('block2-smoke.py'))
b2=importlib.util.module_from_spec(spec);spec.loader.exec_module(b2)

class Lab(b2.Lab):
    def __init__(self):
        super().__init__();self.evidence=[]
    def request(self,path,**kwargs):
        status,data,headers=super().request(path,**kwargs)
        row={'path':path,'status':status,'requestId':headers.get('X-Request-Id')}
        if status>=400 and isinstance(data,dict):row['code']=data.get('code')
        if path=='/admin/nodes' and status==200:
            row['nodes']=[{key:node.get(key) for key in ('nodeId','appliedVersion','freshness','circuitStates')} for node in data]
        if '/actuator/metrics/' in path and status==200:row['metric']=data
        self.evidence.append(row)
        return status,data,headers
    def legacy_fixture(self):
        # 仅本脚本独占临时库：追加旧协议历史夹具，不改写已有历史快照。
        payload=json.loads(self.sql(f'SELECT snapshot_json FROM fg_config_release WHERE version={self.version}'))
        self.version+=1;payload['version']=self.version
        for route in payload['routes']:route['configVersion']=self.version
        payload['policies']=[{'id':100001,'code':'legacy-timeout','type':'TIMEOUT','scope':'ROUTE','enabled':True,'executionStatus':'PENDING_BLOCK3','parametersJson':'{"timeoutMs":50}'},
            {'id':100002,'code':'legacy-cb','type':'CIRCUIT_BREAKER','scope':'ROUTE','enabled':True,'executionStatus':'PENDING_BLOCK3','parametersJson':'{"failureRateThreshold":50,"slidingWindowSize":4,"minimumNumberOfCalls":2,"waitDurationMs":3000}'}]
        payload['bindings']=[{'id':100001,'policyId':100001,'targetType':'ROUTE','targetId':self.routes['slow']['id'],'targetRouteId':'demo-slow'},
            {'id':100002,'policyId':100002,'targetType':'ROUTE','targetId':self.routes['fault']['id'],'targetRouteId':'demo-fault'}]
        payload.pop('checksum');digest=hashlib.sha256(json.dumps(payload,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest();payload['checksum']=digest
        raw=json.dumps(payload,ensure_ascii=False,separators=(',',':')).encode().hex()
        self.sql(f"START TRANSACTION;INSERT INTO fg_config_release(version,snapshot_json,sha256,actor_id) VALUES({self.version},CONVERT(0x{raw} USING utf8mb4),'{digest}',1);UPDATE fg_config_state SET current_version={self.version},optimistic_version=optimistic_version+1 WHERE id=1;COMMIT;")
        self.applied();return self.version
    def save_evidence(self,passed):
        target=b2.ROOT/'docs/progress/evidence/block3-smoke.json';target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(json.dumps({'recordedAt':datetime.now(timezone.utc).isoformat(),'passed':passed,'notes':'isolated HTTP chain; bodies, credentials and auth headers excluded','requests':self.evidence},ensure_ascii=False,indent=2)+'\n')
    def app(self,module,extra=None):
        overrides=dict(extra or {})
        if module=='flowgate-demo': overrides['FLOWGATE_DEMO_FAULT_ENABLED']='true'
        if module=='flowgate-gateway': overrides['SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSE_TIMEOUT']='1500ms'
        process=super().app(module,overrides)
        if module=='flowgate-control': self.control_process=process
        if module=='flowgate-gateway' and overrides.get('FLOWGATE_NODE_ID')=='block2-0': self.gateway_process=process
        return process
    def add_fault(self):
        api=self.admin_call(201,'/admin/apis',body={'serviceId':self.service['id'],'method':'GET','publicPath':'/api/demo/fault','upstreamPath':'/demo/fault','isPublic':True,'requiredScope':'','enabled':True})
        self.routes['fault']=self.admin_call(201,'/admin/routes',body={'apiId':api['id'],'routeId':'demo-fault','priority':0,'enabled':True});self.publish()
    def state(self,route='demo-fault'):
        nodes=self.admin_call(200,'/admin/nodes')
        node=next(n for n in nodes if n['nodeId']=='block2-0')
        assert node['freshness']=='ONLINE',node
        return next((state for state in node['circuitStates'] if state['routeId']==route),{})
    def reached(self): return self.expect(200,'/demo/fault-count',base=f'http://127.0.0.1:{self.demo_port}')['reached']
    def stop(self,process):
        process.terminate();process.wait(timeout=10)

def main():
    lab=Lab();passed=False
    try:
        lab.start();lab.add_fault();gw=lab.gateways[0]
        legacy=lab.legacy_fixture()
        lab.expect(200,'/api/demo/slow?delayMs=100',base=gw)
        for _ in range(3):lab.expect(500,'/api/demo/fault?mode=500',base=gw)
        lab.expect(200,'/api/demo/fault?mode=ok',base=gw)
        lab.expect(200,'/api/demo/fast/1',base=gw)
        lab.expect(201,'/api/demo/echo',base=gw,body={'hello':'中文'})
        lab.expect(504,'/api/demo/fault?mode=slow',base=gw)
        timeout=lab.policy('slow-timeout','TIMEOUT','ROUTE',lab.routes['slow']['id'],{'timeoutMs':200})
        lab.policy('fault-timeout','TIMEOUT','ROUTE',lab.routes['fault']['id'],{'timeoutMs':2500})
        concurrency=lab.policy('slow-inflight','CONCURRENCY','ROUTE',lab.routes['slow']['id'],{'maxConcurrent':1})
        lab.publish()
        status,data,headers=lab.request('/api/demo/slow?delayMs=600',base=gw)
        assert status==504 and data['code']=='UPSTREAM_TIMEOUT' and data['requestId']==headers['X-Request-Id'],(status,data)
        lab.expect(200,'/api/demo/slow?delayMs=0',base=gw)
        lab.expect(200,'/api/demo/fast/1',base=gw)
        for value in (1,10001):
            lab.admin_call(400,'/admin/policies',body={'code':'bad-timeout','type':'TIMEOUT','scope':'ROUTE','parametersJson':'{"timeoutMs":'+str(value)+'}','enabled':True})
        print('PASS 官方Netty路由超时504/Trace/许可归还/无绑定不影响/参数400',flush=True)
        params={'failureRateThreshold':50,'slidingWindowSize':4,'minimumNumberOfCalls':2,'waitDurationMs':3000}
        cb=lab.policy('fault-cb','CIRCUIT_BREAKER','ROUTE',lab.routes['fault']['id'],params);lab.publish()
        b2.wait(lambda:lab.state().get('state')=='CLOSED','真实CLOSED')
        # 1. 下游4xx不记失败；实际500/503才打开；OPEN请求不再到达Demo。
        lab.expect(400,'/api/demo/fault?mode=invalid',base=gw)
        lab.expect(400,'/api/demo/fault?mode=invalid',base=gw)
        lab.expect(500,'/api/demo/fault?mode=500',base=gw)
        lab.expect(503,'/api/demo/fault?mode=503',base=gw)
        b2.wait(lambda:lab.state().get('state')=='OPEN','真实OPEN')
        before=lab.reached();status,data,_=lab.request('/api/demo/fault?mode=ok',base=gw)
        assert status==503 and data['code']=='CIRCUIT_OPEN',(status,data)
        assert lab.reached()==before
        revision=lab.state()['revision'];lab.publish();assert lab.state()['revision']==revision and lab.state()['state']=='OPEN'
        b2.wait(lambda:lab.state().get('state')=='HALF_OPEN','自动HALF_OPEN')
        lab.expect(200,'/api/demo/fault?mode=ok',base=gw)
        assert lab.state()['state']=='HALF_OPEN'
        lab.expect(200,'/api/demo/fault?mode=ok',base=gw)
        b2.wait(lambda:lab.state().get('state')=='CLOSED','两次成功恢复CLOSED')
        # 再次故障，半开第一次失败应立即OPEN。
        for _ in range(4):
            result=lab.request('/api/demo/fault?mode=500',base=gw)
            if result[1].get('code')=='CIRCUIT_OPEN': break
        b2.wait(lambda:lab.state().get('state')=='HALF_OPEN','再次半开')
        lab.expect(500,'/api/demo/fault?mode=500',base=gw)
        b2.wait(lambda:lab.state().get('state')=='OPEN','半开一次失败即OPEN')
        print('PASS 真实500/503计入/4xx不失败/CLOSED→OPEN→HALF_OPEN→CLOSED/半开失败/稳定revision/短路不达下游',flush=True)
        # 2. 新revision启用GET静态降级；直接fallback不能访问，POST发布必须拒绝。
        lab.update(cb,{**params,'fallbackMode':'STATIC_JSON'});lab.publish()
        assert lab.state()['revision']!=revision
        lab.expect(200,'/api/demo/fault?mode=slow',base=gw)  # 2500ms路由超时覆盖1500ms全局，CB不叠加默认1s计时
        status,data,_=lab.request('/api/demo/fault?mode=500',base=gw)
        assert status==503 and data['code']=='DEGRADED' and data['degraded'] and data['source']=='GATEWAY_FALLBACK',(status,data)
        lab.expect(404,'/__flowgate/fallback',base=gw)
        post=lab.policy('post-fallback','CIRCUIT_BREAKER','ROUTE',lab.routes['echo']['id'],{**params,'fallbackMode':'STATIC_JSON'})
        lab.admin_call(400,'/admin/releases/publish',body={'expectedVersion':lab.version});lab.update(post,enabled=False)
        # CB+TIMEOUT使用同一个官方超时源；两次超时使慢路由CB打开。
        lab.update(timeout,{'timeoutMs':1000})
        slow_cb=lab.policy('slow-cb','CIRCUIT_BREAKER','ROUTE',lab.routes['slow']['id'],params);lab.publish()
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            first=pool.submit(lambda:lab.request('/api/demo/slow?delayMs=2000',base=gw))
            b2.wait(lambda:lab.expect(200,'/demo/observation',base=f'http://127.0.0.1:{lab.demo_port}')['inFlight']>0,'真实下游在途')
            assert not first.done(),'并发拒绝测试必须在首请求仍占许可时执行'
            for _ in range(3): assert lab.expect(503,'/api/demo/slow?delayMs=0',base=gw)['code']=='CONCURRENCY_LIMITED'
            assert first.result()[0]==504
        assert lab.state('demo-slow')['state']=='CLOSED','并发拒绝不能成为CB失败样本'
        lab.expect(504,'/api/demo/slow?delayMs=2000',base=gw)
        b2.wait(lambda:lab.state('demo-slow').get('state')=='OPEN','超时计入CB')
        assert lab.expect(503,'/api/demo/slow?delayMs=0',base=gw)['code']=='CIRCUIT_OPEN'
        metric=lab.expect(200,'/actuator/metrics/flowgate.protection.events',base=gw)
        assert {tag['tag'] for tag in metric['availableTags']}=={'routeId','kind','outcome'}
        lab.expect(401,'/admin/nodes')
        lab.expect(404,'/actuator/gateway/routes',base=gw)
        lab.update(timeout,{'timeoutMs':200});lab.publish()
        print('PASS GET显式503降级/直接fallback404/POST降级发布400/超时计入CB/保护指标与节点状态',flush=True)
        # 3. 认证与速率拒绝在CB前：不会降级，不消耗熔断样本。
        private_cb=lab.policy('private-cb','CIRCUIT_BREAKER','ROUTE',lab.routes['private']['id'],{**params,'fallbackMode':'STATIC_JSON'})
        rate=lab.policy('private-rate','RATE_LIMIT','ROUTE',lab.routes['private']['id'],{'capacity':1,'refillPerSecond':1});lab.publish()
        good=lab.admin_call(201,'/admin/callers',body={'username':'good','scopes':['demo:read']})
        wrong=lab.admin_call(201,'/admin/callers',body={'username':'wrong','scopes':['other:read']})
        for _ in range(3):
            lab.expect(401,'/api/private/fast/1',base=gw)
            lab.expect(403,'/api/private/fast/1',base=gw,token=wrong['token'])
        lab.expect(200,'/api/private/fast/1',base=gw,token=good['token'])
        lab.expect(429,'/api/private/fast/1',base=gw,token=good['token'])
        assert lab.state('demo-private')['state']=='CLOSED'
        b2.command(['docker','pause',lab.redis_name])
        lab.expect(503,'/api/private/fast/1',base=gw,token=good['token'])
        lab.expect(200,'/api/demo/fast/1',base=gw)
        b2.command(['docker','unpause',lab.redis_name]);b2.wait(lambda:lab.request('/api/private/fast/1',base=gw,token=good['token'])[0]==200,'Redis恢复')
        # Control暂停时保留旧配置；无可信快照重启Gateway时无公开旁路。
        lab.update(slow_cb,enabled=False);protected_version=lab.publish()
        lab.stop(lab.control_process)
        lab.expect(200,'/api/demo/fast/1',base=gw)
        lab.expect(504,'/api/demo/slow?delayMs=600',base=gw)
        assert lab.expect(503,'/api/demo/fault?mode=500',base=gw)['code']=='DEGRADED'
        lab.stop(lab.gateway_process)
        lab.app('flowgate-gateway',{'GATEWAY_PORT':str(lab.gateway_ports[0]),'FLOWGATE_NODE_ID':'block2-0'})
        b2.wait(lambda:lab.request('/actuator/health',base=gw)[0]==200,'离线首启Gateway')
        lab.expect(404,'/api/demo/fast/1',base=gw)
        lab.app('flowgate-control');b2.wait(lambda:lab.request('/status')[0]==200,'Control恢复')
        lab.admin=lab.expect(200,'/admin/auth/login',body={'username':'block2-admin','password':lab.password})['token'];lab.applied()
        b2.wait(lambda:lab.request('/api/demo/fast/1',base=gw)[0]==200,'恢复后真实路由应用')
        lab.expect(200,'/api/demo/fast/1',base=gw)
        # 回滚无Block3策略历史，timeout不再执行，历史checksum未变。
        old=lab.sql(f'SELECT snapshot_json FROM fg_config_release WHERE version={legacy}')
        result=lab.admin_call(200,f'/admin/releases/{legacy}/rollback',body={'expectedVersion':lab.version});lab.version=result['version'];lab.applied()
        lab.expect(200,'/api/demo/slow?delayMs=500',base=gw)
        assert lab.sql(f'SELECT snapshot_json FROM fg_config_release WHERE version={legacy}')==old
        lab.expect(200,'/api/demo/fault?mode=ok',base=gw)
        result=lab.admin_call(200,f'/admin/releases/{protected_version}/rollback',body={'expectedVersion':lab.version});lab.version=result['version'];lab.applied()
        lab.expect(504,'/api/demo/slow?delayMs=600',base=gw)
        assert lab.expect(503,'/api/demo/fault?mode=500',base=gw)['code']=='DEGRADED'
        lab.expect(200,'/test-console/')
        print('PASS 认证/Scope/限速/Redis故障不降级/Control离线保留/Gateway无快照fail-closed/恢复追平/旧版本回滚',flush=True)
        passed=True
    finally:
        lab.save_evidence(passed);lab.close()

if __name__=='__main__':main()

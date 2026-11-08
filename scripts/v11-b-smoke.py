#!/usr/bin/env python3
"""B6真实双Gateway隔离验收；仅操作自建随机端口Lab，证据仅保存状态/守恒，无Token。"""
import concurrent.futures as futures
import hashlib, http.server, importlib.util, json, os, secrets, socket, subprocess, threading, time, urllib.request, urllib.error, uuid
from datetime import datetime,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('base',ROOT/'scripts/block2-smoke.py');b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
class Lab(b.Lab):
 def __init__(self):
  super().__init__();self.checks=[];self.extra_port=b.port();self.injected=False
 def sql(self,s):
  result=super().sql(s)
  if 'CREATE TABLE' in s and 'fg_config_state' in s and not self.injected:
   self.injected=True;super().sql((ROOT/'sql/flowgate-v11.sql').read_text())
  return result
 def app(self,module,extra=None):
  extra=dict(extra or {})
  if module=='flowgate-gateway':extra['SPRING_APPLICATION_JSON']=json.dumps({'logging':{'level':{'org.springframework.cloud.gateway.handler.FilteringWebHandler':'DEBUG'}}})
  if module in ('flowgate-control','flowgate-gateway'):extra.setdefault('FLOWGATE_ALLOWED_UPSTREAMS',f'http://127.0.0.1:{self.demo_port},http://127.0.0.1:{self.extra_port}')
  if module=='flowgate-control':
   extra.setdefault('FLOWGATE_QUOTA_ACTIVATION_ENABLED','true');extra.setdefault('FLOWGATE_QUOTA_TRAFFIC_NODES','block2-0,block2-1');extra.setdefault('FLOWGATE_ALLOWED_UPSTREAMS',f'http://127.0.0.1:{self.demo_port},http://127.0.0.1:{self.extra_port}')
  init=extra.pop('FLOWGATE_LAB_INIT_SQL',None)
  if init is not None:
   env=dict(self.env,**extra);handle=open(self.temp/(module+'-'+str(len(self.processes))+'.log'),'w');self.handles.append(handle)
   process=subprocess.Popen([b.JAVA,'-jar',str(ROOT/module/'target'/f'{module}-0.0.1-SNAPSHOT.jar'),'--spring.datasource.hikari.connection-init-sql='+init],env=env,stdout=handle,stderr=subprocess.STDOUT);self.processes.append(process);return process
  return super().app(module,extra)
 def expect(self,status,path,**kwargs):
  actual,data,headers=self.request(path,**kwargs)
  assert actual==status,(path,status,actual, data.get('code') if isinstance(data,dict) else '响应省略')
  return data
 def check(self,name):self.checks.append(name);print('PASS B6 '+name,flush=True)
 def count(self,app=None):
  condition='' if app is None else f' WHERE application_id={app}'
  return int(self.sql('SELECT COUNT(*) FROM fg_quota_ledger'+condition))
 def grant_payload(self,credential,version=None):
  version=version or self.version
  checksum=self.sql(f'SELECT sha256 FROM fg_config_release WHERE version={version}')
  return dict(chargeId=str(uuid.uuid4()),principalId=credential['principalId'],credentialGeneration=credential['generation'],apiId=self.apis['private']['id'],routeId='demo-private',appliedVersion=version,snapshotChecksum=checksum,units=1)
 def internal(self,payload):
  req=urllib.request.Request(self.control+'/internal/quota/charge',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json','X-FlowGate-Internal-Key':self.env['FLOWGATE_INTERNAL_KEY']})
  try:r=b.HTTP.open(req,timeout=10)
  except urllib.error.HTTPError as e:r=e
  with r:return r.status,json.loads(r.read())
 def new_app(self,name,plan):
  app=self.admin_call(201,'/admin/apps',body={'developerId':self.dev['id'],'code':name,'name':name})
  now=datetime.now(timezone.utc);start=now.replace(day=1,hour=0,minute=0,second=0,microsecond=0);end=start.replace(year=start.year+1,month=1) if start.month==12 else start.replace(month=start.month+1)
  self.admin_call(201,'/admin/subscriptions',body={'applicationId':app['id'],'planId':plan['id'],'effectiveAt':start.isoformat().replace('+00:00','Z'),'expiresAt':end.isoformat().replace('+00:00','Z')})
  c=self.admin_call(200,f"/admin/apps/{app['id']}/credentials",body={'scopes':['demo:read']});assert len(c['token'])==49 and c['token'].startswith('fgapp_');return app,c
 def batch(self,fn,n=100):
  barrier=threading.Barrier(n)
  def call(i):barrier.wait();return fn(i)
  with futures.ThreadPoolExecutor(max_workers=n) as pool:return list(pool.map(call,range(n)))
 def stop(self,p):
  if p.poll() is None:p.terminate();p.wait(10)
 def restart_control(self,extra=None):
  self.stop(self.cp);self.cp=self.app('flowgate-control',extra);b.wait(lambda:self.request('/status')[0]==200,'重启Control');self.applied()

# 可控真实下游：只记录计数，不保存Header/Body/Token。
class Downstream(http.server.BaseHTTPRequestHandler):
 hits=0;lock=threading.Lock()
 def log_message(self,*args):pass
 def do_GET(self):
  with self.lock:Downstream.hits+=1
  if self.path.startswith('/demo/wait'):time.sleep(1)
  status=500 if self.path.startswith('/demo/fault') else 200
  body=b'{"source":"real-test-downstream"}'
  self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers()
  try:self.wfile.write(body)
  except (BrokenPipeError,ConnectionResetError):pass
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
  with self.lock:Downstream.hits+=1
  payload=json.dumps({'body':body,'requestId':self.headers.get('X-Request-Id'),'authorizationPresent':'Authorization' in self.headers,'appHeaderPresent':'X-App-Id' in self.headers,'chargeHeaderPresent':'X-Charge-Id' in self.headers,'demoHeader':self.headers.get('X-Demo-Header')},ensure_ascii=False).encode()
  self.send_response(201);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
# 透明Control代理：提交成功后丢弃首次响应，确认网关重试同chargeId而不重复代理业务。
class LossProxy(http.server.BaseHTTPRequestHandler):
 attempts=[];drop=True
 def log_message(self,*args):pass
 def do_GET(self):self.forward()
 def do_POST(self):self.forward()
 def forward(self):
  body=self.rfile.read(int(self.headers.get('Content-Length','0'))) if self.command=='POST' else None
  req=urllib.request.Request(lab.control+self.path,data=body,method=self.command,headers={k:v for k,v in self.headers.items() if k.lower() not in ('host','connection','content-length')})
  try:response=b.HTTP.open(req,timeout=8)
  except urllib.error.HTTPError as error:response=error
  except (urllib.error.URLError,TimeoutError,OSError):self.close_connection=True;return
  with response:payload=response.read();status=response.status;headers=dict(response.headers)
  if self.path=='/internal/quota/charge':
   LossProxy.attempts.append(json.loads(body)['chargeId'])
   if LossProxy.drop:
    LossProxy.drop=False;self.close_connection=True;self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
  self.send_response(status)
  for key,value in headers.items():
   if key.lower() not in ('connection','transfer-encoding','content-length'):self.send_header(key,value)
  self.send_header('Content-Length',str(len(payload)));self.end_headers()
  try:self.wfile.write(payload)
  except (BrokenPipeError,ConnectionResetError):pass
lab=Lab();passed=False;server=None;proxy=None
try:
 server=http.server.ThreadingHTTPServer(('127.0.0.1',lab.extra_port),Downstream);threading.Thread(target=server.serve_forever,daemon=True).start()
 lab.start();lab.cp=lab.processes[1];lab.gwprocs=lab.processes[2:4]
 lab.dev=lab.admin_call(201,'/admin/developers',body={'code':'b-customer','name':'B客户'})
 five=lab.admin_call(201,'/admin/plans',body={'code':'b-free','name':'Free','monthlyUnits':5})
 large=lab.admin_call(201,'/admin/plans',body={'code':'b-pro','name':'Pro','monthlyUnits':1000})
 old_caller=lab.admin_call(201,'/admin/callers',body={'username':'b-old','scopes':['demo:read']})['token']
 lab.expect(200,'/api/private/fast/1',base=lab.gateways[0],token=old_caller)
 a,c=lab.new_app('five',five)
 lab.expect(401,'/api/private/fast/1',base=lab.gateways[0],token=c['token'])
 quota=lab.policy('b-quota','USAGE_QUOTA','API',lab.apis['private']['id'],{'unitsPerCall':1});lab.publish();quota_version=lab.version
 lab.expect(401,'/api/private/fast/1',base=lab.gateways[0],token=old_caller)
 results=lab.batch(lambda i:lab.request('/api/private/fast/1',base=lab.gateways[i%2],token=c['token'])[0])
 assert results.count(200)==5 and results.count(429)==95,{str(k):results.count(k) for k in set(results)}
 assert lab.count(a['id'])==5 and lab.sql(f"SELECT remaining_units FROM fg_quota_balance WHERE application_id={a['id']}")=='0'
 assert lab.sql('SELECT COUNT(*) FROM fg_outbox_event')=='5'
 lab.check('双新Gateway真HTTP100并发Free5：5通过/95耗尽，Ledger5=Outbox5/余额0；身份域隔离')
 # 真内部HTTP幂等与旧版本复验。
 app,cred=lab.new_app('idempotent',large);q=lab.grant_payload(cred)
 duplicates=lab.batch(lambda _:lab.internal(q));assert all(s==200 for s,_ in duplicates)
 assert sum(not d['duplicate'] for _,d in duplicates)==1 and lab.count(app['id'])==1
 for key,value in [('snapshotChecksum','0'*64),('apiId',lab.apis['fast']['id']),('units',2),('appliedVersion',99999)]:
  modified=dict(q,**{key:value});assert lab.internal(modified)[0]==409
 lab.publish();assert lab.internal(q)[0]==200
 changed=lab.grant_payload(cred);changed['chargeId']=q['chargeId'];assert lab.internal(changed)[0]==409
 assert lab.count(app['id'])==1;lab.check('内部HTTP同chargeId100并发仅1流水；历史版本可重试，checksum/API/units/版本错误和payload冲突409')
 # 凭证轮换与数据库权威；手动恢复旧Redis会话模拟清理失败。
 token=cred['token'];digest=hashlib.sha256(token.encode()).hexdigest();old_session=lab.redis('GET','fg:auth:app:'+digest)
 rotated=lab.admin_call(200,f"/admin/apps/{app['id']}/credentials/rotate",body={'scopes':['demo:read']})
 lab.redis('SET','fg:auth:app:'+digest,old_session,'EX',60)
 before=lab.count();lab.expect(403,'/api/private/fast/2',base=lab.gateways[0],token=token);assert lab.count()==before
 lab.expect(200,'/api/private/fast/2',base=lab.gateways[1],token=rotated['token'])
 rd=hashlib.sha256(rotated['token'].encode()).hexdigest();rs=lab.redis('GET','fg:auth:app:'+rd)
 lab.admin_call(200,f"/admin/apps/{app['id']}/credentials/revoke",body={});lab.redis('SET','fg:auth:app:'+rd,rs,'EX',60)
 before=lab.count();lab.expect(403,'/api/private/fast/2',base=lab.gateways[1],token=rotated['token']);assert lab.count()==before
 rotated=lab.admin_call(200,f"/admin/apps/{app['id']}/credentials/rotate",body={'scopes':['demo:read']})
 for kind,row in [('apps',app),('developers',lab.dev)]:
  lab.admin_call(200,f"/admin/{kind}/{row['id']}",body={'status':'SUSPENDED'},method='PATCH');before=lab.count()
  lab.expect(403,'/api/private/fast/2',base=lab.gateways[0],token=rotated['token']);assert lab.count()==before
  lab.admin_call(200,f"/admin/{kind}/{row['id']}",body={'status':'ACTIVE'},method='PATCH')
 lab.check('轮换/撤销后即使Redis残留旧Token仍403；应用/客户暂停不扣额')
 # 同App并发首次签发只允许一个，另一个409。
 fresh=lab.admin_call(201,'/admin/apps',body={'developerId':lab.dev['id'],'code':'concurrent','name':'并发签发'})
 with futures.ThreadPoolExecutor(max_workers=2) as pool:
  issued=list(pool.map(lambda _:lab.request(f"/admin/apps/{fresh['id']}/credentials",body={'scopes':['demo:read']},token=lab.admin)[0],range(2)))
 assert sorted(issued)==[200,409] and lab.sql(f"SELECT COUNT(*) FROM fg_app_principal WHERE application_id={fresh['id']}")=='1';lab.check('双管理员并发首次签发仅1主体/1成功/1冲突')
 # 新私有受控下游路由同时覆盖真实CB、timeout、并发。
 svc=lab.admin_call(201,'/admin/services',body={'code':'b-real','name':'真实故障下游','upstreamUri':f'http://127.0.0.1:{lab.extra_port}','enabled':True})
 cases={}
 for name,path in [('fault','/fault'),('wait','/wait'),('limited','/ok'),('capacity','/wait'),('post','/post')]:
  api=lab.admin_call(201,'/admin/apis',body={'serviceId':svc['id'],'method':'POST' if name=='post' else 'GET','publicPath':'/api/b/'+name,'upstreamPath':'/demo'+path,'isPublic':False,'requiredScope':'demo:read','enabled':True})
  route=lab.admin_call(201,'/admin/routes',body={'apiId':api['id'],'routeId':'b-'+name,'priority':0,'enabled':True});cases[name]=(api,route)
  lab.policy('quota-'+name,'USAGE_QUOTA','API',api['id'],{'unitsPerCall':1})
 cb=lab.policy('b-cb','CIRCUIT_BREAKER','ROUTE',cases['fault'][1]['id'],{'failureRateThreshold':50,'slidingWindowSize':4,'minimumNumberOfCalls':1,'waitDurationMs':30000,'fallbackMode':'STATIC_JSON'})
 timeout=lab.policy('b-timeout','TIMEOUT','ROUTE',cases['wait'][1]['id'],{'timeoutMs':100})
 rate=lab.policy('b-rate','RATE_LIMIT','ROUTE',cases['limited'][1]['id'],{'capacity':1,'refillPerSecond':1})
 concurrency=lab.policy('b-concurrency','CONCURRENCY','ROUTE',cases['capacity'][1]['id'],{'maxConcurrent':1})
 lab.publish();testapp,testcred=lab.new_app('filters',large);testtoken=testcred['token'];gw=lab.gateways[0]
 before=lab.count(testapp['id']);lab.expect(503,'/api/b/fault',base=gw,token=testtoken);assert lab.count(testapp['id'])==before+1
 before=lab.count();hits=Downstream.hits
 for _ in range(3):lab.expect(503,'/api/b/fault',base=gw,token=testtoken)
 assert lab.count()==before and Downstream.hits==hits
 # 同一CB配置第二网关仍CLOSED；耗尽App配额429不能假降级或打开该CB。
 for _ in range(3):lab.expect(429,'/api/b/fault',base=lab.gateways[1],token=c['token'])
 lab.expect(503,'/api/b/fault',base=lab.gateways[1],token=testtoken);assert Downstream.hits==hits+1
 lab.check('真实官方Route CB先于Quota：OPEN零扣额/零下游；Quota429不降级不触发CB，双节点状态独立')
 before=lab.count();lab.expect(504,'/api/b/wait',base=gw,token=testtoken);assert lab.count()==before+1
 before=lab.count();lab.expect(200,'/api/b/limited',base=gw,token=testtoken);lab.expect(429,'/api/b/limited',base=gw,token=testtoken);assert lab.count()==before+1
 before=lab.count()
 with futures.ThreadPoolExecutor(max_workers=1) as pool:
  pending=pool.submit(lab.request,'/api/b/capacity',base=gw,token=testtoken);time.sleep(.25)
  lab.expect(503,'/api/b/capacity',base=gw,token=testtoken);assert pending.result()[0]==200
 assert lab.count()==before+1
 lab.expect(200,'/api/b/capacity',base=gw,token=testtoken)
 lab.check('Grant后真实500/超时仍扣；Rate429/并发503先拒绝不扣；完成后许可复用')
 # 用户主动取消：用实际socket关闭等待中的响应，Grant仍在，许可可再次使用。
 before=lab.count();s=socket.create_connection(('127.0.0.1',lab.gateway_ports[0]));s.sendall(('GET /api/b/capacity HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer '+testtoken+'\r\nConnection: close\r\n\r\n').encode());time.sleep(.3);s.close();time.sleep(1.1)
 assert lab.count()==before+1;lab.expect(200,'/api/b/capacity',base=gw,token=testtoken);lab.check('真实客户端取消不退款，本机并发许可可再次使用')
 # 丢回包经过实际HTTP转发，重试charge相同，DB及下游均仅一次。
 proxy_port=b.port();proxy=http.server.ThreadingHTTPServer(('127.0.0.1',proxy_port),LossProxy);threading.Thread(target=proxy.serve_forever,daemon=True).start()
 lab.stop(lab.gwprocs[0]);lab.gwprocs[0]=lab.app('flowgate-gateway',{'GATEWAY_PORT':str(lab.gateway_ports[0]),'FLOWGATE_NODE_ID':'block2-0','FLOWGATE_CONTROL_URI':f'http://127.0.0.1:{proxy_port}'})
 b.wait(lambda:lab.request('/actuator/health',base=gw)[0]==200,'丢回包代理Gateway');lab.applied()
 before=lab.count();hits=Downstream.hits
 forged=str(uuid.uuid4());body={'marker':'计量POST透传'}
 request=urllib.request.Request(gw+'/api/b/post',data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+testtoken,'Content-Type':'application/json','X-App-Id':str(a['id']),'X-Charge-Id':forged,'X-Request-Id':'forged-request','X-Demo-Header':'B-header'})
 with b.HTTP.open(request,timeout=8) as response:
  received=json.loads(response.read());assert response.status==201 and received['body']==body and received['demoHeader']=='B-header' and received['requestId']==response.headers['X-Request-Id'] and received['requestId']!='forged-request'
  assert not received['authorizationPresent'] and not received['appHeaderPresent'] and not received['chargeHeaderPresent']
 assert LossProxy.attempts[0]!=forged
 assert len(LossProxy.attempts)==2 and len(set(LossProxy.attempts))==1 and lab.count()==before+1 and Downstream.hits==hits+1
 lab.check('Control成功提交后丢回包：实际WebClient同chargeId重试，1扣额/1下游POST；Header/Body/201/RequestId透传且伪造身份被清理')
 # 实际FilteringWebHandler的排序日志，不以手写order数字代替运行证据。
 lines=[]
 for file in lab.temp.glob('flowgate-gateway-*.log'):
  lines.extend(line for line in file.read_text().splitlines() if 'Sorted gatewayFilterFactories' in line and 'QuotaAdmissionGlobalFilter' in line and 'SpringCloudCircuitBreaker' in line)
 assert lines,'缺少真实组合Filter日志'
 line=lines[0];assert line.index('CallerAuthFilter')<line.index('AdmissionGlobalFilter')<line.index('SpringCloudCircuitBreaker')<line.index('QuotaAdmissionGlobalFilter')<line.index('NettyRoutingFilter')
 lab.check('实际FilteringWebHandler排序日志：Auth → Rate/Concurrency → 官方CB → Quota → Netty')
 # 无Scope/无订阅均不得留下流水。
 noapp=lab.admin_call(201,'/admin/apps',body={'developerId':lab.dev['id'],'code':'none','name':'无订阅'})
 nc=lab.admin_call(200,f"/admin/apps/{noapp['id']}/credentials",body={'scopes':['demo:read']});before=lab.count()
 lab.expect(403,'/api/private/fast/1',base=gw,token=nc['token']);assert lab.count()==before
 scope=lab.admin_call(200,f"/admin/apps/{noapp['id']}/credentials/rotate",body={'scopes':[]});lab.expect(403,'/api/private/fast/1',base=gw,token=scope['token']);assert lab.count()==before
 # 用户Header无法指定应用或charge，内部入口不接受AdminToken。
 lab.expect(401,'/internal/quota/charge',body=q,token=lab.admin)
 lab.check('无订阅/Scope不足不扣；内部配额不接受管理凭证')
 # 当前B管理端HTTP权限矩阵，新增凭证写仍严格ADMIN。
 lab.sql("INSERT INTO fg_principal(principal_type,username,password_hash,role,scopes_json,enabled) SELECT 'ADMIN',LOWER(r.role),p.password_hash,r.role,'[]',1 FROM fg_principal p JOIN (SELECT 'VIEWER' role UNION ALL SELECT 'OPERATOR') r WHERE p.username='block2-admin'")
 for username in ('viewer','operator'):
  role_token=lab.expect(200,'/admin/auth/login',body={'username':username,'password':lab.password})['token']
  lab.expect(200,f"/admin/apps/{app['id']}/credentials/status",token=role_token)
  for action in ('','/rotate','/revoke'):lab.expect(403,f"/admin/apps/{app['id']}/credentials"+action,body={'scopes':['demo:read']},token=role_token)
  lab.expect(200,'/admin/quota/status',token=role_token)
 lab.check('新增凭证API真实HTTP：OPERATOR/VIEWER可读，签发/轮换/撤销全部403')
 metrics=lab.expect(200,'/actuator/metrics/flowgate.quota.admission',base=gw)
 assert not {'appId','principalId','chargeId','token'}.intersection(t['tag'] for t in metrics['availableTags']);lab.check('实际Quota指标可读且无身份/charge高基数标签')
 # 实际运行A历史二进制，不能只伪造无feature报告来冒充混合升级验证。
 r=lab.admin_call(200,'/admin/releases/1/rollback',body={'expectedVersion':lab.version});lab.version=r['version'];lab.applied()
 lab.stop(lab.gwprocs[1]);legacy=ROOT/'.local/v11-a-legacy-binary/flowgate-gateway/target/flowgate-gateway-0.0.1-SNAPSHOT.jar'
 assert legacy.exists(),'先按README构建A历史二进制夹具，不切换或重写当前工作树'
 handle=open(lab.temp/'legacy-gateway.log','w');lab.handles.append(handle)
 legacy_env=dict(lab.env,GATEWAY_PORT=str(lab.gateway_ports[1]),FLOWGATE_NODE_ID='block2-1',FLOWGATE_ALLOWED_UPSTREAMS=f'http://127.0.0.1:{lab.demo_port},http://127.0.0.1:{lab.extra_port}')
 legacy_process=subprocess.Popen([b.JAVA,'-jar',str(legacy)],env=legacy_env,stdout=handle,stderr=subprocess.STDOUT);lab.processes.append(legacy_process)
 b.wait(lambda:lab.request('/actuator/health',base=lab.gateways[1])[0]==200,'旧A Gateway启动');lab.applied()
 b.wait(lambda:lab.sql("SELECT features_json FROM fg_node_capability WHERE node_id='block2-1'")=='[]','旧节点覆盖能力')
 lab.expect(200,'/api/private/fast/1',base=lab.gateways[1],token=old_caller);lab.expect(401,'/api/private/fast/1',base=lab.gateways[1],token=testtoken)
 lab.admin_call(409,'/admin/releases/publish',body={'expectedVersion':lab.version})
 lab.admin_call(409,f'/admin/releases/{quota_version}/rollback',body={'expectedVersion':lab.version})
 lab.stop(legacy_process);lab.gwprocs[1]=lab.app('flowgate-gateway',{'GATEWAY_PORT':str(lab.gateway_ports[1]),'FLOWGATE_NODE_ID':'block2-1'});lab.applied()
 lab.check('实际A旧Gateway+新Gateway：App格式401/旧Caller200，含Quota发布及历史回滚409')
 b.wait(lambda:'USAGE_QUOTA_V1' in lab.sql("SELECT features_json FROM fg_node_capability WHERE node_id='block2-1'"),'新节点重报能力')
 lab.publish();lab.applied()
 # Control停机，被计量fail-closed，公开旧路径不依赖Control。
 lab.stop(lab.cp);before=lab.count();lab.expect(503,'/api/private/fast/1',base=gw,token=testtoken);lab.expect(200,'/api/demo/fast/1',base=gw);assert lab.count()==before
 lab.cp=lab.app('flowgate-control');b.wait(lambda:lab.request('/status')[0]==200,'恢复Control');lab.applied();lab.check('Control停机仅计量503，非计量公开路径200，不新增流水')
 # MySQL暂停仅本次容器；完成后解除暂停并等待连接恢复。
 b.command(['docker','pause',lab.mysql])
 try:
  lab.expect(503,'/api/private/fast/1',base=gw,token=testtoken);lab.expect(200,'/api/demo/fast/1',base=gw)
 finally:b.command(['docker','unpause',lab.mysql])
 b.wait(lambda:lab.request('/status')[1].get('mysql')=='UP','MySQL恢复');lab.check('MySQL失联计量503，旧非计量公开路径200')
 # DB会话时间推进到下月：HTTP重试返回原Ledger月份且无需下月订阅。
 crossapp,crosscred=lab.new_app('crossmonth',large);crossq=lab.grant_payload(crosscred);status,original=lab.internal(crossq);assert status==200
 now=datetime.now(timezone.utc);first=now.replace(day=1,hour=0,minute=0,second=0,microsecond=0);nextmonth=first.replace(year=first.year+1,month=1) if first.month==12 else first.replace(month=first.month+1)
 lab.restart_control({'FLOWGATE_LAB_INIT_SQL':"SET time_zone='+00:00', timestamp="+str(int(nextmonth.timestamp()))})
 status,repeated=lab.internal(crossq);assert status==200 and repeated['duplicate'] and repeated['periodUtc']==original['periodUtc']
 assert lab.sql(f"SELECT COUNT(*) FROM fg_quota_balance WHERE application_id={crossapp['id']}")=='1'
 freshmonth=dict(crossq,chargeId=str(uuid.uuid4()));assert lab.internal(freshmonth)[0]==403,'实际新月时钟必须令首次charge因无新月订阅拒绝'
 lab.restart_control();lab.check('真实Control HTTP跨UTC月重复返回原月，不要求新月订阅且无第二余额')
 # 清理Redis期间DB代次先提交：通过Redis ACL只拒绝EVAL，不阻断Admin会话GET。
 before_generation=int(lab.sql(f"SELECT generation FROM fg_app_credential_state WHERE principal_id={testcred['principalId']}"))
 lab.redis('ACL','SETUSER','default','-eval','-evalsha')
 try:
  lab.admin_call(503,f"/admin/apps/{testapp['id']}/credentials/rotate",body={'scopes':['demo:read']})
 finally:lab.redis('ACL','SETUSER','default','+eval','+evalsha')
 assert int(lab.sql(f"SELECT generation FROM fg_app_credential_state WHERE principal_id={testcred['principalId']}"))==before_generation+1
 before=lab.count();lab.expect(403,'/api/private/fast/1',base=gw,token=testtoken);assert lab.count()==before
 lab.check('Redis Lua发布失败返回503但DB代次已前进，旧Redis Token不可复活')
 recovered=lab.admin_call(200,f"/admin/apps/{testapp['id']}/credentials/rotate",body={'scopes':['demo:read']});before=lab.count()
 lab.redis('ACL','SETUSER','default','-eval','-evalsha')
 try:lab.admin_call(503,f"/admin/apps/{testapp['id']}/credentials/revoke",body={})
 finally:lab.redis('ACL','SETUSER','default','+eval','+evalsha')
 lab.expect(403,'/api/private/fast/1',base=gw,token=recovered['token']);assert lab.count()==before
 lab.check('Redis Lua撤销清理真实失败仍DB enabled=false，旧会话403不扣额')

 # 默认OFF旧V1回滚兼容：关计量前先回滚到原非计量快照，旧Caller可用且APP被拒绝。
 r=lab.admin_call(200,'/admin/releases/1/rollback',body={'expectedVersion':lab.version});lab.version=r['version'];lab.applied()
 lab.restart_control({'FLOWGATE_QUOTA_ACTIVATION_ENABLED':'false'})
 lab.admin_call(409,f"/admin/apps/{testapp['id']}/credentials",body={'scopes':['demo:read']})
 lab.expect(200,'/api/private/fast/1',base=gw,token=old_caller);lab.expect(401,'/api/private/fast/1',base=gw,token=testtoken)
 lab.check('回滚旧快照后默认OFF凭证409，旧Caller正常/APP不可跨域')
 for path in ['/console/','/console/quota.js','/console/guide.html']:lab.expect(200,path)
 assert lab.sql('SELECT COUNT(*) FROM fg_quota_ledger')==lab.sql('SELECT COUNT(*) FROM fg_outbox_event')
 assert lab.sql("SELECT COUNT(*) FROM fg_outbox_event WHERE status<>'NEW'")=='0'
 assert lab.sql('SELECT COUNT(*) FROM fg_quota_balance b LEFT JOIN (SELECT application_id,period_yyyymm,SUM(units) spent FROM fg_quota_ledger GROUP BY application_id,period_yyyymm) l ON l.application_id=b.application_id AND l.period_yyyymm=b.period_yyyymm WHERE b.remaining_units+COALESCE(l.spent,0)<>b.total_units')=='0'
 assert lab.sql("SELECT COUNT(*) FROM fg_quota_ledger l LEFT JOIN fg_outbox_event o ON JSON_UNQUOTE(JSON_EXTRACT(o.payload_json,'$.chargeId'))=l.charge_id WHERE o.id IS NULL")=='0'
 lab.check('真实HTTP产品入口/新资源/指南可达；全部Ledger与Outbox守恒且仅NEW')
 passed=True
finally:
 if proxy:proxy.shutdown();proxy.server_close()
 if server:server.shutdown();server.server_close()
 lab.close();target=ROOT/('.local/acceptance/v11-b-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps({'passed':passed,'checks':lab.checks,'evidenceType':'real HTTP + isolated MySQL8.4/Redis7/two Gateway','ownerBrowserAcceptance':'not executed'},ensure_ascii=False,indent=2));target.chmod(0o600);print('B6本机脱敏证据：',target,'passed=',passed,flush=True)

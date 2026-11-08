#!/usr/bin/env python3
"""C6实际Kafka/双Gateway/MySQL/Redis宕机与幂等验收；只清理本次资源。"""
import importlib.util,json,os,subprocess,concurrent.futures as futures,time,zipfile
from pathlib import Path
from datetime import datetime,timezone,timedelta
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('base',ROOT/'scripts/block2-smoke.py');b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
class Lab(b.Lab):
 def __init__(self):super().__init__();self.injected=False;self.cp=None;self.async_on=False;self.topic='flowgate.usage.v1';self.kafka_port=b.port();self.kafka_name=None;self.historical=True;self.billing=datetime.now(timezone.utc)-timedelta(days=2);self.checks=[]
 def sql(self,s):
  result=super().sql(s)
  if 'CREATE TABLE' in s and 'fg_config_state' in s and not self.injected:
   self.injected=True;super().sql((ROOT/'sql/flowgate-v11.sql').read_text());super().sql((ROOT/'sql/flowgate-v11-c.sql').read_text())
  return result
 def app(self,module,extra=None):
  extra=dict(extra or {})
  if module=='flowgate-control':
   extra.update(FLOWGATE_QUOTA_ACTIVATION_ENABLED='true',FLOWGATE_QUOTA_TRAFFIC_NODES='block2-0,block2-1',FLOWGATE_ASYNC_ENABLED=str(self.async_on).lower(),FLOWGATE_KAFKA_BOOTSTRAP_SERVERS=f'127.0.0.1:{self.kafka_port}',FLOWGATE_ASYNC_POLL_MS='200',FLOWGATE_USAGE_TOPIC=self.topic)
   env=dict(self.env,**extra);handle=open(self.temp/('control-'+str(len(self.processes))+'.log'),'w');self.handles.append(handle)
   args=[b.JAVA,'-jar',str(ROOT/module/'target'/f'{module}-0.0.1-SNAPSHOT.jar')]
   if self.historical:args.append("--spring.datasource.hikari.connection-init-sql=SET time_zone='+00:00', timestamp="+str(int(self.billing.timestamp())))
   p=subprocess.Popen(args,env=env,stdout=handle,stderr=subprocess.STDOUT);self.processes.append(p);self.cp=p;return p
  return super().app(module,extra)
 def stop_control(self):
  if self.cp and self.cp.poll() is None:
   self.cp.terminate()
   try:self.cp.wait(8)
   except subprocess.TimeoutExpired:self.cp.kill();self.cp.wait()
 def restart(self,enabled):
  self.stop_control();self.async_on=enabled;self.app('flowgate-control');b.wait(lambda:self.request('/status')[0]==200,'Control重启',90)
  b.wait(lambda:all(n['freshness']=='ONLINE' and n['appliedVersion']==self.version for n in self.admin_call(200,'/admin/nodes')),'真实新节点心跳')
 def mark(self,name):self.checks.append(name);print('PASS C6 '+name,flush=True)
 def expect(self,status,path,**kw):
  actual,data,_=self.request(path,**kw);assert actual==status,(path,status,actual,data.get('code') if isinstance(data,dict) else '省略');return data
 def count(self,table):return int(self.sql('SELECT COUNT(*) FROM '+table))
 def total(self):return int(self.sql('SELECT COALESCE(SUM(grant_count),0) FROM fg_usage_daily'))
 def converge(self,n):b.wait(lambda:self.count('fg_consumed_event')==n and self.total()==n and self.sql("SELECT COUNT(*) FROM fg_outbox_event WHERE status<>'SENT'")=='0','异步追平'+str(n),150)
 def new_app(self,code,plan):
  app=self.admin_call(201,'/admin/apps',body={'developerId':self.dev['id'],'code':code,'name':code})
  self.subscribe(app,plan,self.billing if self.historical else datetime.now(timezone.utc))
  credential=self.admin_call(200,f"/admin/apps/{app['id']}/credentials",body={'scopes':['demo:read']});return app,credential
 def subscribe(self,app,plan,now):
  start=now.replace(day=1,hour=0,minute=0,second=0,microsecond=0);end=start.replace(year=start.year+1,month=1) if start.month==12 else start.replace(month=start.month+1)
  self.admin_call(201,'/admin/subscriptions',body={'applicationId':app['id'],'planId':plan['id'],'effectiveAt':start.isoformat().replace('+00:00','Z'),'expiresAt':end.isoformat().replace('+00:00','Z')})
 def gateway(self,token,index=0):return self.request('/api/private/fast/1',base=self.gateways[index],token=token)[0]
 def kafka_tool(self,tool,*args):return b.command(['docker','exec',self.kafka_name,'/opt/kafka/bin/'+tool,'--bootstrap-server','localhost:9092',*args])
 def start_kafka(self):
  env=dict(os.environ,KAFKA_NODE_ID='1',KAFKA_PROCESS_ROLES='broker,controller',KAFKA_LISTENERS='INTERNAL://:9092,CONTROLLER://:9093,EXTERNAL://:9094',KAFKA_ADVERTISED_LISTENERS=f'INTERNAL://localhost:9092,EXTERNAL://127.0.0.1:{self.kafka_port}',KAFKA_LISTENER_SECURITY_PROTOCOL_MAP='INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT',KAFKA_CONTROLLER_LISTENER_NAMES='CONTROLLER',KAFKA_CONTROLLER_QUORUM_VOTERS='1@localhost:9093',KAFKA_INTER_BROKER_LISTENER_NAME='INTERNAL',KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR='1',KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR='1',KAFKA_TRANSACTION_STATE_LOG_MIN_ISR='1',KAFKA_AUTO_CREATE_TOPICS_ENABLE='false')
  args=['-p',f'127.0.0.1:{self.kafka_port}:9094']
  for k in env:
   if k.startswith('KAFKA_'):args.extend(['-e',k])
  self.kafka_name=self.docker('apache/kafka:4.1.1',args,env)
  b.wait(lambda:self.kafka_tool('kafka-topics.sh','--list') is not None,'Kafka KRaft',100)
  self.kafka_tool('kafka-topics.sh','--create','--topic','flowgate.usage.v1','--partitions','3','--replication-factor','1')
 def prepare_probe(self):
  self.libs=self.temp/'probe-libs';self.libs.mkdir();self.classes=self.temp/'probe-classes';self.classes.mkdir()
  with zipfile.ZipFile(ROOT/'flowgate-control/target/flowgate-control-0.0.1-SNAPSHOT.jar') as jar:
   for item in jar.namelist():
    if item.startswith('BOOT-INF/lib/') and item.endswith('.jar'):(self.libs/Path(item).name).write_bytes(jar.read(item))
    elif item.startswith('BOOT-INF/classes/') and not item.endswith('/'):
     p=self.classes/item.removeprefix('BOOT-INF/classes/');p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(jar.read(item))
 def probe(self,mode,exitcode=0):
  env=dict(self.env,FLOWGATE_KAFKA_BOOTSTRAP_SERVERS=f'127.0.0.1:{self.kafka_port}')
  p=subprocess.run([b.JAVA,'-cp',str(self.classes)+os.pathsep+str(self.libs/'*'),str(ROOT/'scripts/V11KafkaFaultProbe.java'),mode],env=env,capture_output=True,text=True,timeout=65)
  (self.temp/(mode+'-'+str(time.monotonic_ns())+'.log')).write_text(p.stdout+'\n'+p.stderr)
  assert p.returncode==exitcode,(mode,p.returncode,p.stderr[-500:]);lines=[line for line in p.stdout.splitlines() if line.startswith('{')];return json.loads(lines[-1]) if lines else None
lab=Lab();passed=False
try:
 lab.start();lab.prepare_probe();lab.dev=lab.admin_call(201,'/admin/developers',body={'code':'c','name':'C客户'})
 five=lab.admin_call(201,'/admin/plans',body={'code':'c-five','name':'Free5','monthlyUnits':5});large=lab.admin_call(201,'/admin/plans',body={'code':'c-large','name':'Pro','monthlyUnits':1000})
 old=lab.admin_call(201,'/admin/callers',body={'username':'c-old','scopes':['demo:read']})['token'];assert lab.gateway(old)==200
 app,credential=lab.new_app('c-five',five);pro,pc=lab.new_app('c-pro',large)
 lab.policy('c-quota','USAGE_QUOTA','API',lab.apis['private']['id'],{'unitsPerCall':1});lab.publish()
 with futures.ThreadPoolExecutor(max_workers=100) as pool:codes=list(pool.map(lambda i:lab.gateway(credential['token'],i%2),range(100)))
 assert codes.count(200)==5 and codes.count(429)==95 and lab.count('fg_quota_ledger')==5 and lab.count('fg_outbox_event')==5
 lab.mark('无Kafka/Async OFF双Gateway100并发Free5精确5Grant95耗尽')
 assert lab.gateway(old)==401 and lab.count('fg_consumed_event')==0;lab.mark('App/旧Caller身份隔离且OFF不虚构消费')
 # 显式开启异步但Broker不存在，控制面及B热路径仍可用。
 lab.restart(True);assert lab.gateway(pc['token'])==200 and lab.count('fg_quota_ledger')==6
 state=lab.admin_call(200,'/admin/async-events/summary');assert state['broker']['state']=='BROKER_UNAVAILABLE' and int(state['pendingConsumption'])==6
 lab.mark('Async ON但Broker未启动：B授权200、Outbox积压6、真实探测不可用')
 lab.stop_control();lab.historical=False;lab.restart(False)
 if lab.billing.month!=datetime.now(timezone.utc).month:lab.subscribe(pro,large,datetime.now(timezone.utc))
 day=lab.billing.date().isoformat();query=f'?from={day}&to={day}&limit=1'
 pending=lab.admin_call(200,'/admin/reconciliation'+query);assert pending['rows'][0]['status']=='PENDING' and pending['nextCursor']
 more=lab.admin_call(200,'/admin/reconciliation'+query+'&cursor='+pending['nextCursor']);assert more['rows'] and more['nextCursor'] is None;lab.mark('真实封账HTTP：积压PENDING且同日多App复合游标不漏页')
 lab.stop_control();lab.start_kafka();lab.mark('专用单KRaft Broker及3分区真实启动')
 # 把之前未ACK发送失败的测试租约设为到期，专用首次ACK崩溃实验仍等待真实30秒租约。
 lab.sql("UPDATE fg_outbox_event SET status='NEW',lease_owner=NULL,lease_until=NULL,next_attempt_at=UTC_TIMESTAMP(3) WHERE status<>'SENT'")
 lab.probe('send-and-halt',23);assert lab.sql("SELECT COUNT(*) FROM fg_outbox_event WHERE status='LEASED'")=='1' and lab.sql("SELECT COUNT(*) FROM fg_outbox_event WHERE status='SENT'")=='0';lab.mark('真实Broker ACK后halt23，SENT未写且原租约保留')
 lab.probe('consume-and-halt',24);assert lab.count('fg_consumed_event')==1 and lab.total()==1;lab.mark('真实消费DB提交后halt24未ACK，幂等标记和汇总已落库')
 lab.restart(True);lab.converge(6);lab.mark('实际30秒租约到期重发/offset重放后仅6消费6汇总，无第7笔')
 match=lab.admin_call(200,'/admin/reconciliation'+query.replace('limit=1','limit=20'));assert all(r['status']=='MATCH' for r in match['rows']);lab.mark('积压追平后真实封账对账MATCH')
 lab.probe('duplicate');b.wait(lambda:lab.admin_call(200,'/admin/async-events/summary')['processCounters']['duplicate']>=100,'100消息重放',60)
 assert lab.total()==6 and lab.count('fg_consumed_event')==6;lab.mark('真实Kafka重发同eventId100次，读模型仍6次')
 # Broker实际停机/恢复，B授权继续，不伪造SENT。
 b.command(['docker','stop',lab.kafka_name]);assert lab.gateway(pc['token'],1)==200 and lab.count('fg_quota_ledger')==7
 b.wait(lambda:lab.admin_call(200,'/admin/async-events/summary')['broker']['state']=='BROKER_UNAVAILABLE','Broker停机探测',20);lab.mark('实际停Broker后B授权继续200/新Outbox积压')
 b.command(['docker','start',lab.kafka_name]);b.wait(lambda:lab.kafka_tool('kafka-topics.sh','--list') is not None,'Broker恢复',90);lab.converge(7);lab.mark('Broker恢复重排追平7笔，未重复扣量')
 # 两Worker领取100条真实Outbox，随后实际租约过期让生产Worker追平。
 lab.restart(False)
 with futures.ThreadPoolExecutor(max_workers=20) as pool:assert all(code==200 for code in pool.map(lambda i:lab.gateway(pc['token'],i%2),range(100)))
 lab.stop_control();lease=lab.probe('lease100');assert lease['leases']==100;lab.mark('两Worker真实领取100个不重叠租约，迟到owner更新0')
 lab.restart(True);lab.converge(107);lab.mark('100个租约真实到期后恢复发送，107Ledger/Consumed/汇总守恒')
 # 管理重投并发与ACL。
 eventid=lab.sql('SELECT event_id FROM fg_outbox_event ORDER BY id LIMIT 1');id=int(lab.sql('SELECT MIN(id) FROM fg_outbox_event'));lab.sql(f"UPDATE fg_outbox_event SET status='FAILED' WHERE id={id}")
 with futures.ThreadPoolExecutor(max_workers=2) as pool:retries=list(pool.map(lambda _:lab.request(f'/admin/async-events/{id}/retry',body={},token=lab.admin)[0],range(2)))
 assert sorted(retries)==[200,409] and lab.sql(f'SELECT event_id FROM fg_outbox_event WHERE id={id}')==eventid;lab.converge(107)
 assert lab.sql("SELECT COUNT(*) FROM fg_audit_log WHERE action='RETRY_ASYNC_EVENT'")=='1';lab.mark('两管理员真实并发重投仅一次审计，原eventId/Grant总数不变')
 lab.sql("INSERT INTO fg_principal(principal_type,username,password_hash,role,scopes_json,enabled) SELECT 'ADMIN',LOWER(r.role),p.password_hash,r.role,'[]',1 FROM fg_principal p JOIN (SELECT 'VIEWER' role UNION ALL SELECT 'OPERATOR') r WHERE p.username='block2-admin'")
 for username in ('viewer','operator'):
  token=lab.expect(200,'/admin/auth/login',body={'username':username,'password':lab.password})['token']
  for path in ('/admin/usage','/admin/async-events','/admin/reconciliation'):
   lab.expect(200,path,token=token)
   for method in ('POST','PUT','DELETE'):lab.expect(403,path+'/999',token=token,method=method,body={})
  lab.expect(403,f'/admin/async-events/{id}/retry',token=token,body={})
 lab.mark('新运营HTTP只读角色可读、POST/未知PUT/DELETE均403')
 # 人为只读模型差异必须报告MISMATCH，不修权威余额。
 lab.sql("UPDATE fg_usage_daily SET authorized_units=authorized_units+1 ORDER BY day_utc LIMIT 1")
 result=lab.admin_call(200,'/admin/reconciliation'+query.replace('limit=1','limit=20'));assert any(r['status']=='MISMATCH' for r in result['rows']);lab.mark('真实HTTP读模型差异MISMATCH，Ledger/余额不自动改写')
 lab.sql("UPDATE fg_usage_daily SET authorized_units=authorized_units-1 ORDER BY day_utc LIMIT 1")
 # 真实消费者SQL失败：标记和Daily同事务回滚，恢复后offset可重放。
 lab.sql('RENAME TABLE fg_usage_daily TO fg_usage_daily_fault')
 try:
  assert lab.gateway(pc['token'])==200;b.wait(lambda:lab.sql("SELECT COUNT(*) FROM fg_outbox_event WHERE status='SENT'")=='108','聚合故障事件送达')
  time.sleep(2);assert lab.count('fg_consumed_event')==107
 finally:lab.sql('RENAME TABLE fg_usage_daily_fault TO fg_usage_daily')
 lab.mark('实际消费日表SQL故障不留下第108个消费标记、不提前确认')
 lab.restart(True);lab.converge(108);lab.mark('修复DB并重启消费者后原offset重放，恰好108汇总')
 # 毒消息首版无DLT：暂停问题分区，Broker可用或不可用都不能跳过offset。
 fault=lab.probe('poison');b.wait(lambda:lab.admin_call(200,'/admin/async-events/summary')['processCounters']['pausedPartition']>=1,'暂停毒消息分区')
 offset=lab.probe('offset')['offset'];assert 0<=offset<=fault['offset'] and lab.total()==108
 lab.mark('毒消息实际暂停分区2，未确认原offset，未假发送DLT')
 lab.probe('conflict');b.wait(lambda:lab.admin_call(200,'/admin/async-events/summary')['processCounters']['pausedPartition']>=2,'暂停内容冲突分区')
 assert lab.total()==108;lab.mark('同eventId不同payload实际诊断，不累计读模型')
 b.command(['docker','stop',lab.kafka_name]);time.sleep(1);assert lab.total()==108
 b.command(['docker','start',lab.kafka_name]);b.wait(lambda:lab.kafka_tool('kafka-topics.sh','--list') is not None,'毒消息后Broker恢复',90)
 assert 0<=lab.probe('offset')['offset']<=fault['offset'];lab.mark('无DLT且Broker断联/恢复，毒消息offset仍未前进')
 today=datetime.now(timezone.utc).date().isoformat();lab.admin_call(400,f'/admin/reconciliation?from={today}&to={today}');lab.admin_call(400,'/admin/usage?from=2026-01-01&to=2026-03-01');lab.mark('实际日期边界拒绝未封账日和超31天范围')
 for path in ('/console/','/console/usage.js','/console/guide.html','/test-console/'):lab.expect(200,path)
 events=lab.admin_call(200,'/admin/async-events');assert 'payload' not in json.dumps(events).lower();lab.mark('实际产品新资源可达且运营事件无原始payload')
 # 首次配置新Topic且Broker离线：恢复后自动创建显式Topic并投递，不要求人工重扣。
 b.command(['docker','stop',lab.kafka_name]);lab.topic='flowgate.recovery.v1';lab.restart(True)
 assert lab.gateway(pc['token'])==200 and lab.count('fg_quota_ledger')==109
 b.command(['docker','start',lab.kafka_name])
 b.wait(lambda:'flowgate.recovery.v1' in lab.kafka_tool('kafka-topics.sh','--list'),'首次Topic故障恢复自动创建',100);lab.converge(109)
 lab.mark('首次Topic初始化时Broker离线，恢复后自动创建声明Topic并幂等追平109笔')
 assert lab.count('fg_quota_ledger')==lab.count('fg_outbox_event')==lab.count('fg_consumed_event')==lab.total()==109
 lab.mark('最终109权威Grant/Outbox/消费标记/汇总一致；毒消息没有制造Grant')
 passed=True
finally:
 lab.close();data={'passed':passed,'checks':lab.checks,'evidenceType':'real Kafka4.1.1/MySQL8.4/Redis7/two Gateway/abrupt JVM halt','ownerBrowserAcceptance':'not executed'}
 target=ROOT/('.local/acceptance/v11-c-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,ensure_ascii=False,indent=2));target.chmod(0o600);print('C6脱敏证据：',target,'passed=',passed,flush=True)

#!/usr/bin/env python3
"""固定到达率/有界inflight的两轮Gateway公平对照。真实扣额，不含耗尽拒绝样本。"""
import time,secrets,json,os,concurrent.futures,threading,csv,urllib.request,urllib.error,statistics
from pathlib import Path
from datetime import datetime,timedelta,timezone
from v12_d_lab import Lab,c
l=Lab();l.wait(lambda:len([n for n in l.request('/admin/nodes',l.admin) if n.get('freshness')=='ONLINE' and 'USAGE_QUOTA_V12' in n.get('supportedFeatures',[])])==2,'trusted Gateway capabilities');run=secrets.token_hex(4);token=l.portal();dev=l.request('/portal/api/my-developers',token)[0];service=l.request('/admin/services',l.admin)[0];gateways=['http://127.0.0.1:'+str(l.baseline['ports'][k]) for k in ['gateway','gateway2']];demo='http://127.0.0.1:'+str(l.state['ports']['demo']);routes={};plan=l.request('/admin/plans',l.admin,{'code':'d-bench-'+secrets.token_hex(4),'name':'D性能fixture充足额度','monthlyUnits':1000000},201);legacydev=l.request('/admin/developers',l.admin,{'code':'d-bench-'+secrets.token_hex(4),'name':'D性能fixture'},201);la=l.request('/admin/apps',l.admin,{'developerId':legacydev['id'],'code':'d-bench-'+secrets.token_hex(4),'name':'D性能计量'},201);now=datetime.now(timezone.utc);first=now.replace(day=1,hour=0,minute=0,second=0,microsecond=0);end=(first+timedelta(days=32)).replace(day=1);iso=lambda x:x.isoformat(timespec='milliseconds').replace('+00:00','Z')
l.request('/admin/subscriptions',l.admin,{'applicationId':la['id'],'planId':plan['id'],'effectiveAt':iso(first),'expiresAt':iso(end)},201);v11=l.request('/admin/apps/'+str(la['id'])+'/credentials',l.admin,{'scopes':['demo:read']})['token'];caller=l.request('/admin/callers',l.admin,{'username':'d-bench-'+secrets.token_hex(4),'scopes':['demo:read']},201)['token'];pa=l.app(token,'D性能充足额度')
for kind,upstream in [('fast','/demo/fast/{id}'),('slow','/demo/slow')]:
 for group in ['PROXY','GOVERNED','METERED_V11','METERED_V12']:
  path='/api/d-bench/'+run+'/'+group.lower()+'/'+kind+('/{id}' if kind=='fast' else '')
  api=l.request('/admin/apis',l.admin,{'serviceId':service['id'],'method':'GET','publicPath':path,'upstreamPath':upstream,'isPublic':group=='PROXY','requiredScope':'' if group=='PROXY' else 'demo:read','enabled':True},201)
  route=l.request('/admin/routes',l.admin,{'apiId':api['id'],'routeId':'d-'+run+'-'+group.lower()+'-'+kind,'priority':0,'enabled':True},201)
  if group!='PROXY':
   for typ,params in [('RATE_LIMIT',{'capacity':10000,'refillPerSecond':10000}),('CONCURRENCY',{'maxConcurrent':64}),('CIRCUIT_BREAKER',{'failureRateThreshold':50,'slidingWindowSize':20,'minimumNumberOfCalls':10,'waitDurationMs':1000,'fallbackMode':'STATIC_JSON'})]:
    p=l.request('/admin/policies',l.admin,{'code':'d-'+secrets.token_hex(5),'type':typ,'scope':'ROUTE','parametersJson':json.dumps(params),'enabled':True},201);l.request('/admin/policies/'+str(p['id'])+'/bindings',l.admin,{'targetType':'ROUTE','targetId':route['id']},201)
  if group.startswith('METERED'):
   p=l.request('/admin/policies',l.admin,{'code':'d-'+secrets.token_hex(5),'type':'USAGE_QUOTA_V12' if group.endswith('V12') else 'USAGE_QUOTA','scope':'API','parametersJson':'{"unitsPerCall":1}','enabled':True},201);l.request('/admin/policies/'+str(p['id'])+'/bindings',l.admin,{'targetType':'API','targetId':api['id']},201)
  routes[group,kind]={'path':path.replace('{id}','1')+('?delayMs=10' if kind=='slow' else ''),'apiId':api['id'],'routeId':route['routeId'] if 'routeId' in route else 'd-'+run+'-'+group.lower()+'-'+kind}
version=l.request('/admin/releases/current',l.admin)['version'];release=l.request('/admin/releases/publish',l.admin,{'expectedVersion':version});l.wait(lambda:sum(n['appliedVersion']==release['version'] for n in l.request('/admin/nodes',l.admin))==2,'two nodes benchmark version')
cat=l.request('/admin/market/categories',l.admin)[0]
for kind in ['fast','slow']:
 api=routes['METERED_V12',kind]['apiId'];listing=l.request('/admin/market/listings',l.admin,{'apiId':api,'categoryId':cat['id'],'title':'D性能'+kind,'summary':'固定负载fixture，非性能承诺','docText':'D隔离性能测试'});sku=l.request('/admin/market/listings/'+str(listing['id'])+'/skus',l.admin,{'code':'d-bench-'+secrets.token_hex(4),'kind':'PAID_BASE','quotaUnits':1000000,'priceCents':990,'periodPolicy':'CURRENT_MONTH'});l.request('/admin/market/listings/'+str(listing['id'])+'/publish',l.admin,{})
 o=l.request('/portal/api/orders',token,{'applicationId':pa['id'],'skuId':sku['id'],'clientRequestId':secrets.token_hex(12)},201);l.request('/portal/api/orders/'+o['orderNo']+'/simulate-pay',token,{});l.wait(lambda:l.request('/portal/api/orders/'+o['orderNo'],token)['status']=='FULFILLED','benchmark real grant')
v12=l.request('/portal/api/apps/'+str(pa['id'])+'/credentials/issue',token,{})['token']
# All direct/proxied calls send same X-Request-Id bytes; gateway replaces with same-length own identifier.
requestId='11111111-1111-4111-8111-111111111111';raw=[];reports=[]
def request(url,key):
 st=time.perf_counter();headers={'X-Request-Id':requestId}
 if key:headers['Authorization']='Bearer '+key
 try:
  try:response=c.HTTP.open(urllib.request.Request(url,headers=headers),timeout=5)
  except urllib.error.HTTPError as e:response=e
  with response:body=response.read();code=response.status
 except OSError:code='NETWORK_ERROR';body=b''
 return code,(time.perf_counter()-st)*1000,len(body)
def sample_resources():
 import subprocess
 pids=[l.state['processes'][x]['pid'] for x in ['flowgate-control','flowgate-gateway','flowgate-demo']]+[l.cleanup['secondControlPid'],l.cleanup['secondPid']]
 return {'ps':subprocess.check_output(['ps','-p',','.join(map(str,pids)),'-o','pid=,time=,rss='],text=True),'sql':l.sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Threads_running','Innodb_row_lock_waits','Innodb_row_lock_time');"),'rabbit':c.command(['docker','exec',l.container('rabbitmq'),'rabbitmqctl','list_queues','-p','/flowgate-v12','name','messages'],timeout=15)}
for round in [1,2]:
 for group in (['DIRECT','PROXY','GOVERNED','METERED_V11','METERED_V12'] if round==1 else ['METERED_V12','METERED_V11','GOVERNED','PROXY','DIRECT']):
  for kind in ['fast','slow']:
   key={'DIRECT':None,'PROXY':None,'GOVERNED':caller,'METERED_V11':v11,'METERED_V12':v12}[group];path=('/demo/fast/1' if kind=='fast' else '/demo/slow?delayMs=10') if group=='DIRECT' else routes[group,kind]['path']
   for n in range(8):assert request((demo if group=='DIRECT' else gateways[n%2])+path,key)[0]==200,(group,kind,'warmup')
   before=sample_resources();slots=threading.BoundedSemaphore(16);start=time.perf_counter();rows=[];lock=threading.Lock();planned=100;rate=50
   def call(n):
    try:
     result=request((demo if group=='DIRECT' else gateways[n%2])+path,key)
     with lock:rows.append((n,*result))
    finally:slots.release()
   with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
    for n in range(planned):
     delay=start+n/rate-time.perf_counter()
     if delay>0:time.sleep(delay)
     if slots.acquire(False):pool.submit(call,n)
     else:rows.append((n,'CLIENT_SHED',0,0))
   elapsed=time.perf_counter()-start;after=sample_resources();success=[r[2] for r in rows if r[1]==200];alllat=[r[2] for r in rows if isinstance(r[1],int)];pct=lambda xs,p:sorted(xs)[min(len(xs)-1,int(len(xs)*p))] if xs else None
   from collections import Counter
   counts=dict(Counter(str(x[1]) for x in rows));report={'round':round,'group':group,'kind':kind,'planned':planned,'sent':planned-counts.get('CLIENT_SHED',0),'completed':len(alllat),'clientShed':counts.get('CLIENT_SHED',0),'statusCounts':counts,'seconds':elapsed,'completedQps':len(alllat)/elapsed,'successQps':len(success)/elapsed,'successLatency':{str(p):pct(success,p) for p in [.5,.95,.99]},'allLatency':{str(p):pct(alllat,p) for p in [.5,.95,.99]},'resourcesBefore':before,'resourcesAfter':after,'responseBytes':sorted(set(r[3] for r in rows if r[1]==200))};reports.append(report);raw.extend([[round,group,kind,*r] for r in rows]);print(group+' '+kind+' '+str(counts),flush=True)
   assert len(success)==planned,report
path=c.ROOT/'docs/acceptance/evidence';path.mkdir(parents=True,exist_ok=True)
with (path/'v12-d-gateway.csv').open('w') as f:w=csv.writer(f);w.writerow(['round','group','kind','sequence','status','latencyMs','bytes']);w.writerows(raw)
metrics={}
for i,g in enumerate(gateways):
 for metric in ['flowgate.quota.hop','jvm.gc.pause','hikaricp.connections.active','flowgate.quota.hop.percentile']:
  code,data=c.http_json(g,'/actuator/metrics/'+metric);metrics[str(i)+':'+metric]={'status':code,'data':data}
l.save('v12-d4-gateway-four-way.json',{'passed':True,'exitCode':0,'fixedArrivalRate':50,'maxInflight':16,'warmup':8,'rounds':2,'samples':reports,'metrics':metrics,'quotaHopP95':'actual Micrometer percentile gauge in metrics, seconds; fixed low-cardinality route tag','workload':'same existing Demo fast/slow10ms, same method and X-Request-Id length; no new compute/DB-heavy fixture','notes':['GOVERNED uses old Caller; METERED uses APP purpose as required; auth overhead is part of measured path','quota sufficiently funded through real subscriptions/order/Grant','not a max throughput test, no0cost claim'],'notExecuted':['compute/DB-heavy upstream absent in existing Demo','GC percentile/histogram','maximum sustainable throughput beyond fixed50rps']});print('D_GATEWAY_BENCHMARK_PASS',flush=True)

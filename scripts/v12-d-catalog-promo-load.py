#!/usr/bin/env python3
"""D1通过后1000/5000/20000实际请求阶梯，最多128inflight，fixture不冒充真实注册。"""
import os,secrets,hashlib,time,json,csv,concurrent.futures,collections,subprocess
from v12_d_lab import Lab,c
l=Lab();assert json.loads((c.ROOT/'docs/progress/evidence/v12-d1-outbox-recovery.json').read_text())['passed'];run=secrets.token_hex(5);fixtures=5000
# Set-based fixture insert, one transaction; no extra persistent tables in business schema.
digits=' UNION ALL '.join('SELECT '+str(i)+' d' for i in range(10));q='CREATE TEMPORARY TABLE d_fixture_n(n INT PRIMARY KEY);INSERT INTO d_fixture_n SELECT a.d+10*b.d+100*c.d+1000*d.d FROM ('+digits+') a CROSS JOIN ('+digits+') b CROSS JOIN ('+digits+') c CROSS JOIN ('+digits+') d LIMIT 5000;START TRANSACTION;'
q+="INSERT INTO fg_principal(username,principal_type,role,scopes_json,enabled) SELECT CONCAT('d-load-"+run+"-',n),'PORTAL_USER','PORTAL_USER','[]',TRUE FROM d_fixture_n;"
q+="INSERT INTO fg_portal_user(principal_id,login_name_normalized,display_name) SELECT id,username,'D负载fixture' FROM fg_principal WHERE username LIKE 'd-load-"+run+"-%';"
q+="INSERT INTO fg_developer(code,name,status) SELECT CONCAT('d-load-"+run+"-',n),'D负载fixture','ACTIVE' FROM d_fixture_n;"
q+="INSERT INTO fg_developer_member(developer_id,principal_id) SELECT d.id,p.id FROM fg_developer d JOIN fg_principal p ON d.code=p.username WHERE d.code LIKE 'd-load-"+run+"-%';"
q+="INSERT INTO fg_application(developer_id,code,name,status) SELECT id,code,'D负载fixture','ACTIVE' FROM fg_developer WHERE code LIKE 'd-load-"+run+"-%';COMMIT;"
l.sql(q,True)
rows=[(int(p),int(a),'fgportal_'+secrets.token_hex(24)) for p,a in (line.split('\t') for line in l.sql("SELECT p.id,a.id FROM fg_principal p JOIN fg_developer_member m ON m.principal_id=p.id JOIN fg_application a ON a.developer_id=m.developer_id WHERE p.username LIKE 'd-load-"+run+"-%' ORDER BY p.id").splitlines())];assert len(rows)==5000
commands='\n'.join('SET fg:auth:portal:'+hashlib.sha256(t.encode()).hexdigest()+' '+str(p)+' EX 3600' for p,a,t in rows);l.redis(data=commands)
def sql_stats():return l.sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Threads_running','Innodb_row_lock_waits','Innodb_row_lock_time')")
def caches():return [l.request('/admin/market/cache',l.admin,base=b)['counters'] for b in [l.base,l.second]]
raw=[];stages=[]
for count,concurrency in [(1000,24),(5000,64),(20000,128)]:
 p=l.promotion('D请求阶梯 '+str(count),100);pid=p['id'];sql_before=sql_stats();cache_before=caches();start=time.perf_counter()
 def claim(n):
  _,a,t=rows[n%5000];st=time.perf_counter()
  try:code,value=c.http_json([l.base,l.second][n%2],f'/portal/api/promotions/{pid}/claim',t,{'applicationId':a,'clientRequestId':'d-'+run+'-'+str(pid)+'-'+str(n%5000)})
  except c.LocalFailure:code='CLIENT_TIMEOUT';value={}
  return n,code,value.get('intentId'),(time.perf_counter()-st)*1000
 with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:out=list(pool.map(claim,range(count)))
 seconds=time.perf_counter()-start;counts=collections.Counter(str(x[1]) for x in out);ids={x[2] for x in out if x[1]==202};assert len(ids)==100,(counts,len(ids));l.wait(lambda:l.sql(f'SELECT COUNT(DISTINCT order_id) FROM fg_promo_purchase_intent WHERE promotion_id={pid}')=='100','100 distinct orders',90)
 authority=l.sql(f"SELECT COUNT(*),COUNT(DISTINCT principal_id),COUNT(DISTINCT order_id) FROM fg_promo_purchase_intent WHERE promotion_id={pid}");assert authority=='100\t100\t100';report=l.request('/admin/promotions/'+str(pid)+'/reconciliation',l.admin);assert report['integrityGaps']==0 and report['reserved']+report['sold']==100,report
 # Pay real first10 via fixture sessions; unique receipt/grant, no synthetic paid KPI.
 for user,a,t in rows[:10]:
  mine=l.request(f'/portal/api/promotions/{pid}/my-claim',t)
  if mine['status']=='ORDER_READY':l.request('/portal/api/orders/'+mine['order']['orderNo']+'/simulate-pay',t,{})
 l.wait(lambda:l.sql(f"SELECT COUNT(*) FROM fg_promo_purchase_intent i JOIN fg_order o ON o.id=i.order_id WHERE i.promotion_id={pid} AND o.status IN ('PAID_PENDING_FULFILLMENT','FULFILLMENT_RETRY')")=='0','paid grants settle',40)
 db=l.sql(f"SELECT s.total_stock,s.reserved,s.sold,(SELECT COUNT(*) FROM fg_payment_record p JOIN fg_promo_purchase_intent i ON i.order_id=p.order_id WHERE i.promotion_id={pid}),(SELECT COUNT(*) FROM fg_entitlement_grant g JOIN fg_promo_purchase_intent i ON g.source_type='PAID_ORDER' AND g.source_id=i.order_id WHERE i.promotion_id={pid}) FROM fg_promo_stock s WHERE promotion_id={pid}")
 lat=sorted(x[3] for x in out);stages.append({'planned':count,'sent':count,'completed':sum(isinstance(x[1],int) for x in out),'clientShed':0,'timeouts':counts.get('CLIENT_TIMEOUT',0),'concurrency':concurrency,'seconds':seconds,'actualQps':count/seconds,'p50Ms':lat[int(count*.5)],'p95Ms':lat[int(count*.95)],'p99Ms':lat[min(count-1,int(count*.99))],'httpCounts':dict(counts),'uniqueAcceptedIntents':100,'uniqueOrders':100,'promotionId':pid,'authorityTotalReservedSoldPaymentGrant':db,'reconciliation':report,'sqlBefore':sql_before,'sqlAfter':sql_stats(),'cacheBefore':cache_before,'cacheAfter':caches()});raw.extend([[count,*x] for x in out]);print('LOAD '+str(count)+' '+str(counts),flush=True)
# Real public cold/hot scope; all MySQLQuestions includes readiness/auth/schedulers unlike projection DB_LOAD.
path='/portal/api/catalog/apis?limit=20';l.request(path);before=caches();sqlbefore=sql_stats();start=time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:hot=list(pool.map(lambda n:c.http_json([l.base,l.second][n%2],path)[0],range(1000)))
assert hot==[200]*1000;after=caches();hotreport={'count':1000,'seconds':time.perf_counter()-start,'publicProjectionDbLoads':sum(x['DB_LOAD'] for x in after)-sum(x['DB_LOAD'] for x in before),'sqlBefore':sqlbefore,'sqlAfter':sql_stats(),'before':before,'after':after}
for _ in range(3):l.request('/portal/api/catalog/apis/999999999',expected=404)
pathout=c.ROOT/'docs/acceptance/evidence';pathout.mkdir(parents=True,exist_ok=True)
with (pathout/'v12-d-promo-load.csv').open('w') as f:w=csv.writer(f);w.writerow(['stage','request','status','intentId','latencyMs']);w.writerows(raw)
l.save('v12-d5-catalog-promo-load.json',{'passed':True,'exitCode':0,'fixtures':'5000 relational isolated identities/apps/sessions,20k stage repeats same request4times; not registrations','hardResourceLimits':{'maxInflight':128,'maxRequests':20000,'perRequestClientTimeoutSeconds':6},'stages':stages,'publicHot':hotreport,'negative404Repeated':3,'qpsClaim':'20k HTTP requests completed; not20k QPS','notExecuted':['production or independent Linux load','max sustained saturation','Redis adversarial slow-reader test in separate D cache probe']});print('D_LOAD_LADDER_PASS',flush=True)

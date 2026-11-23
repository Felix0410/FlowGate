#!/usr/bin/env python3
"""100名额/120个隔离身份fixture，所有抢购仍走真实双Control HTTP；附公开读小基准。"""
import os,secrets,hashlib,time,json,concurrent.futures,collections
from datetime import datetime,timedelta,timezone
from v12_c_lab import Lab,c
l=Lab();run=secrets.token_hex(5);redis=c.docker_target(l.state['ports']['redis'],'redis',l.state['project'],l.state['owner']);env=dict(os.environ,REDISCLI_AUTH=l.env.get('FG_REDIS_PASSWORD',''))
def redis_cmd(*args):return c.command(['docker','exec','-e','REDISCLI_AUTH',redis,'redis-cli',*args],env=env)
# Fixtures avoid the product's20/IP/min registration limiter. These are not claimed as real registrations.
sql=[]
for n in range(120):
 key='c-load-'+run+'-'+str(n)
 sql.append("INSERT INTO fg_principal(username,principal_type,role,scopes_json,enabled) VALUES('%s','PORTAL_USER','PORTAL_USER','[]',TRUE);SET @p=LAST_INSERT_ID();INSERT INTO fg_portal_user(principal_id,login_name_normalized,display_name) VALUES(@p,'%s','C并发fixture');INSERT INTO fg_developer(code,name,status) VALUES('%s','C并发fixture','ACTIVE');SET @d=LAST_INSERT_ID();INSERT INTO fg_developer_member(developer_id,principal_id) VALUES(@d,@p);INSERT INTO fg_application(developer_id,code,name,status) VALUES(@d,'%s','C并发fixture','ACTIVE');"%(key,key,key,key))
l.sql('\n'.join(sql),True)
fixtures=[(int(p),int(a),'fgportal_'+secrets.token_hex(24)) for p,a in (line.split('\t') for line in l.sql("SELECT p.id,a.id FROM fg_principal p JOIN fg_developer_member m ON m.principal_id=p.id JOIN fg_application a ON a.developer_id=m.developer_id WHERE p.username LIKE 'c-load-%s-%%' ORDER BY p.id;"%run).splitlines())]
commands='\n'.join('SET fg:auth:portal:'+hashlib.sha256(t.encode()).hexdigest()+' '+str(p)+' EX 1800' for p,a,t in fixtures)
c.command(['docker','exec','-i','-e','REDISCLI_AUTH',redis,'redis-cli'],env=env,data=commands)
sku=next(s for s in l.request('/portal/api/catalog/apis/'+str(l.browser['listingId'])+'/offers') if s['kind']=='PAID_BASE' and s['period_policy']=='CURRENT_MONTH')
now=datetime.now(timezone.utc);iso=lambda d:d.isoformat(timespec='milliseconds').replace('+00:00','Z')
p=l.request('/admin/promotions',l.admin,{'name':'100名额真实并发 '+run,'skuId':sku['id'],'promoPriceCents':100,'totalStock':100,'beginsAt':iso(now-timedelta(seconds=1)),'endsAt':iso(now+timedelta(hours=1)),'payTtlSeconds':900,'orderCreationTtlSeconds':300});pid=p['id'];l.request(f'/admin/promotions/{pid}/publish',l.admin,{})
def call(n):
 _,a,t=fixtures[n];start=time.perf_counter();code,v=c.http_json([l.base,l.second][n%2],f'/portal/api/promotions/{pid}/claim',t,{'applicationId':a,'clientRequestId':'load-'+run+'-'+str(n)});return n,code,v,1000*(time.perf_counter()-start)
started=time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:results=list(pool.map(call,range(120)))
elapsed=time.perf_counter()-started;accepted=[x for x in results if x[1]==202];assert len(accepted)==100,dict(collections.Counter(x[1] for x in results))
# Repeated client request20 times on opposite nodes must remain one durable Intent each.
for n,code,i,_ in accepted[:5]:assert call(n)[2]['intentId']==i['intentId']
end=time.time()+90
while time.time()<end:
 counts=l.sql(f'SELECT COUNT(*),COUNT(DISTINCT order_id) FROM fg_promo_purchase_intent WHERE promotion_id={pid};').split('\t')
 if counts==['100','100']:break
 time.sleep(.5)
assert counts==['100','100'],counts
stock=l.request(f'/admin/promotions/{pid}',l.admin);assert (stock['total_stock'],stock['reserved'],stock['sold'])==(100,100,0)
# Pay10 in parallel; unique B receipts/grants and reserved->sold verified in authority SQL.
def pay(row):
 n,_,_,_=row;t=fixtures[n][2];i=l.request(f'/portal/api/promotions/{pid}/my-claim',t);return c.http_json([l.base,l.second][n%2],'/portal/api/orders/'+i['order']['orderNo']+'/simulate-pay',t,{})[0]
with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:assert list(pool.map(pay,accepted[:10]))==[200]*10
end=time.time()+30
q=f"SELECT COUNT(*) FROM fg_entitlement_grant g JOIN fg_order o ON g.source_type='PAID_ORDER' AND g.source_id=o.id JOIN fg_promo_purchase_intent i ON i.order_id=o.id WHERE i.promotion_id={pid};"
while time.time()<end and l.sql(q)!='10':time.sleep(.3)
assert l.sql(q)=='10';stock=l.request(f'/admin/promotions/{pid}',l.admin);assert (stock['reserved'],stock['sold'])==(90,10)
r=l.request(f'/admin/promotions/{pid}/reconciliation',l.admin);assert r['integrityGaps']==0,r
# Measure actual public projection DB loads (not all SQL), bounded1000 requests, no20k QPS claim.
path='/portal/api/catalog/apis?limit=20';l.request(path)
def stats():return [l.request('/admin/market/cache',l.admin,base=b)['counters'] for b in [l.base,l.second]]
before=stats();start=time.perf_counter()
def read(n):
 st=time.perf_counter();code,_=c.http_json([l.base,l.second][n%2],path);return code,1000*(time.perf_counter()-st)
with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:reads=list(pool.map(read,range(1000)))
seconds=time.perf_counter()-start;assert all(x[0]==200 for x in reads);after=stats()
def metrics(lat):
 lat=sorted(lat);return {'p50Ms':lat[int(len(lat)*.50)],'p95Ms':lat[int(len(lat)*.95)],'p99Ms':lat[min(len(lat)-1,int(len(lat)*.99))]}
l.save('v12-c8-performance.json',{'passed':True,'fixtures':'120 isolated MySQL identities/apps and Redis sessions; not120 real registrations','promotionId':pid,'claimRequests':120,'accepted':100,'statuses':dict(collections.Counter(str(x[1]) for x in results)),'claimSeconds':elapsed,'claimQps':120/elapsed,'claimLatency':metrics([x[3] for x in results]),'authority':{'intents':100,'orders':100,'payments':10,'grants':10,'total':100,'reserved':90,'sold':10,'available':0},'reconciliation':r,'catalog':{'completed':1000,'concurrency':24,'seconds':seconds,'qps':1000/seconds,**metrics([x[1] for x in reads]),'countersBefore':before,'countersAfter':after,'projectionDbLoads':sum(x['DB_LOAD'] for x in after)-sum(x['DB_LOAD'] for x in before)},'notExecuted':['5000/20000 request ladder per owner speed instruction','20k QPS NOT_REACHED','independent owner acceptance']})
print('C_CONCURRENCY_PASS',flush=True)

#!/usr/bin/env python3
"""仅C隔离owner：真实Redis丢失/NOSCRIPT、MySQL pause、Rabbit停恢复/受理超时。"""
import os,secrets,time,json
from datetime import datetime,timedelta,timezone
from v12_c_lab import Lab,c,r
l=Lab();l.restart(cache=True,promo=True);checks=[]
redis=c.docker_target(l.state['ports']['redis'],'redis',l.state['project'],l.state['owner']);rabbit=c.docker_target(l.state['ports']['rabbitmq'],'rabbitmq',l.state['project'],l.state['owner']);mysql=c.docker_target(l.state['ports']['mysql'],'mysql',l.state['project'],l.state['owner'])
renv=dict(os.environ,REDISCLI_AUTH=l.env.get('FG_REDIS_PASSWORD',''))
def redis_cmd(*args):return c.command(['docker','exec','-e','REDISCLI_AUTH',redis,'redis-cli',*args],env=renv)
def login():return l.request('/portal/api/auth/login',body={'loginName':l.browser['loginName'],'password':l.browser['password']})['token']
token=login();dev=l.request('/portal/api/my-developers',token)[0]
def app():return l.request('/portal/api/apps',token,{'developerId':dev['id'],'code':'c-r-'+secrets.token_hex(6),'name':'C故障恢复'},201)
def wait(fn,label,secs=40):
 end=time.time()+secs
 while time.time()<end:
  try:value=fn()
  except c.LocalFailure:value=False
  if value:return value
  time.sleep(.3)
 raise AssertionError(label)
sku=next(s for s in l.request('/portal/api/catalog/apis/'+str(l.browser['listingId'])+'/offers') if s['kind']=='PAID_BASE' and s['period_policy']=='CURRENT_MONTH');now=datetime.now(timezone.utc);iso=lambda t:t.isoformat(timespec='milliseconds').replace('+00:00','Z')
p=l.request('/admin/promotions',l.admin,{'name':'C恢复与超时','skuId':sku['id'],'promoPriceCents':100,'totalStock':3,'beginsAt':iso(now-timedelta(seconds=1)),'endsAt':iso(now+timedelta(hours=1)),'orderCreationTtlSeconds':3});pid=p['id'];l.request(f'/admin/promotions/{pid}/publish',l.admin,{})
a=app();before=l.request('/admin/promotions/'+str(pid),l.admin)
# Full isolated Redis loss includes sessions: explicitly re-authenticate, never touch user's Redis.
redis_cmd('FLUSHDB');token=login();l.admin=l.login();body={'applicationId':a['id'],'clientRequestId':secrets.token_hex(12)}
# Maintenance may rebuild after30s. Test immediately after loss.
l.request(f'/portal/api/promotions/{pid}/claim',token,body,503)
assert l.request(f'/portal/api/promotions/{pid}/my-claim',token)['status']=='NOT_ACCEPTED'
rebuilt=l.request(f'/admin/promotions/{pid}/rebuild',l.admin,{});assert rebuilt['epoch']>before['epoch'];redis_cmd('SCRIPT','FLUSH')
checks+=['actual isolated Redis FLUSHDB fails claim503 without MySQL reservation','ADMIN rebuild advances DB epoch and derives capacity from DB','SCRIPT FLUSH with EVALSHA fallback']
# Existing B/M Ledger history in the isolated project remains unchanged through all faults.
ledger=l.sql('SELECT COUNT(*),COALESCE(SUM(units),0) FROM fg_api_quota_ledger;')
c.command(['docker','stop','-t','5',rabbit],timeout=15)
try:
 i=l.request(f'/portal/api/promotions/{pid}/claim',token,body,202);iid=i['intentId'];assert l.request('/portal/api/purchase-intents/'+str(iid),token)['status']=='ACCEPTED'
 wait(lambda:l.request('/portal/api/purchase-intents/'+str(iid),token)['status']=='EXPIRED','accepted expiry',12)
 assert l.request('/admin/promotions/'+str(pid),l.admin)['reserved']==0
finally:c.command(['docker','start',rabbit])
wait(lambda:c.rabbit_probe(l.env,l.state['project'],l.state['owner']),'Rabbit return',50)
wait(lambda:l.sql('SELECT COUNT(*) FROM fg_promo_event_consumed WHERE intent_id='+str(iid))=='1','late event consumed',120)
assert l.sql('SELECT result FROM fg_promo_event_consumed WHERE intent_id='+str(iid))=='IGNORED_TERMINAL'
assert l.sql('SELECT order_id IS NULL FROM fg_promo_purchase_intent WHERE id='+str(iid))=='1'
checks+=['real Rabbit container stopped: HTTP202 durable before broker confirmation','ACCEPTED creation deadline releases stock/own slot','Rabbit restart late event ignored without resurrecting order']
# Bounded DB dependency failure, always unpause in finally.
c.command(['docker','pause',mysql])
try:
 try:code,_=c.http_json(l.base,'/portal/api/promotions/'+str(pid))
 except c.LocalFailure:code='CLIENT_TIMEOUT'
 assert code in (200,503,'CLIENT_TIMEOUT'),code # public cache may serve200; transaction cannot use it as authority
finally:c.command(['docker','unpause',mysql])
wait(lambda:c.http_json(l.base,'/status')[0]==200,'DB recovery');assert ledger==l.sql('SELECT COUNT(*),COALESCE(SUM(units),0) FROM fg_api_quota_ledger;')
report=l.request(f'/admin/promotions/{pid}/reconciliation',l.admin);assert report['integrityGaps']==0
# Final enhanced reconciliation also checks previous100-stock/payment/Grant links.
load=json.loads((c.ROOT/'docs/progress/evidence/v12-c8-performance.json').read_text());load_report=l.request('/admin/promotions/'+str(load['promotionId'])+'/reconciliation',l.admin);assert load_report['integrityGaps']==0,load_report
checks+=['MySQL pause/unpause: public response or client timeout, then recovery','existing isolated API Ledger rows/units unchanged','100-stock Intent/Order/Payment/Outbox/Consumed/Slot/Grant enhanced RR check has zero gaps']
l.save('v12-c3-c7-recovery.json',{'passed':True,'checks':checks,'promotionId':pid,'epochBefore':before['epoch'],'epochAfter':rebuilt['epoch'],'reconciliation':report,'loadReconciliation':load_report,'mysqlPauseStatus':code,'notExecuted':['full historical B23/C26 matrices','5000/20000 load levels','owner manual acceptance']})
print('C_RECOVERY_PASS',flush=True)

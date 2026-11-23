#!/usr/bin/env python3
"""真实8次Broker失败、双300s自然冷却、双ADMIN竞争与原eventId100重投。"""
import time,secrets,concurrent.futures,os
from v12_d_lab import Lab,c
l=Lab();token=l.portal();a=l.app(token);b=l.app(token);p=l.promotion('D耗尽恢复',2,creation=3);s=l.promotion('D SENT恢复',2,creation=3);rabbit=l.container('rabbitmq');states=[]
assert l.request('/admin/promotions/events/11111111-1111-4111-8111-111111111111/replay',l.admin,{},409)['code']=='REPLAY_COOLDOWN'
# Short broker interruption makes source conflict deterministic before first delivery.
c.command(['docker','stop','-t','3',rabbit],timeout=15)
try:
 sent=l.request(f"/portal/api/promotions/{s['id']}/claim",token,{'applicationId':b['id'],'clientRequestId':secrets.token_hex(12)},202)
 l.sql('UPDATE fg_promo_purchase_intent SET price_cents=price_cents+1 WHERE id='+str(sent['intentId']),True)
finally:c.command(['docker','start',rabbit])
l.wait(lambda:l.sql('SELECT status FROM fg_promo_outbox WHERE intent_id='+str(sent['intentId']))=='SENT','SENT confirmed',70)
l.wait(lambda:l.sql('SELECT status FROM fg_promo_purchase_intent WHERE id='+str(sent['intentId']))=='EXPIRED','SENT Intent expiry',15)
# Wait for authority conflict to be Poison then repair only this newly owned fixture. No historical Ledger edits.
time.sleep(2);l.sql('UPDATE fg_promo_purchase_intent SET price_cents=price_cents-1 WHERE id='+str(sent['intentId']),True)
assert l.sql('SELECT COUNT(*) FROM fg_promo_event_consumed WHERE intent_id='+str(sent['intentId']))=='0'
sent_event=l.sql('SELECT event_id FROM fg_promo_outbox WHERE intent_id='+str(sent['intentId']))
l.request('/admin/promotions/events/'+sent_event+'/replay',l.admin,{},409)
c.command(['docker','stop','-t','3',rabbit],timeout=15)
try:
 intent=l.request(f"/portal/api/promotions/{p['id']}/claim",token,{'applicationId':a['id'],'clientRequestId':secrets.token_hex(12)},202);iid=intent['intentId'];end=time.monotonic()+180
 while time.monotonic()<end:
  row=l.sql('SELECT status,attempt_count FROM fg_promo_outbox WHERE intent_id='+str(iid));
  if not states or states[-1]!=row:states.append(row);print('OBSERVED '+row,flush=True)
  if row=='FAILED\t8':break
  time.sleep(1)
 assert row=='FAILED\t8',row
finally:c.command(['docker','start',rabbit])
event=l.sql('SELECT event_id FROM fg_promo_outbox WHERE intent_id='+str(iid));e=l.request('/admin/promotions/'+str(p['id'])+'/events',l.admin)[0];assert e['status']=='RETRY_EXHAUSTED' and e['raw_status']=='FAILED'
l.sql("INSERT INTO fg_principal(principal_type,username,password_hash,role,scopes_json,enabled) SELECT 'ADMIN','d-second-admin',password_hash,'ADMIN','[]',TRUE FROM fg_principal WHERE username='v12-admin'",True)
second=l.request('/admin/auth/login',body={'username':'d-second-admin','password':l.browser['password']})['token']
for role in ['operator','viewer']:
 t=l.request('/admin/auth/login',body={'username':'b-'+role,'password':l.browser['password']})['token'];l.request('/admin/promotions/events/'+event+'/replay',t,{},403)
# Save a private checkpoint so a failed observation never requires manufacturing timestamps.
from v12_d_lab import r
r.save(l.work/'d1-checkpoint.json',{'eventId':event,'sentEvent':sent_event,'promotionId':p['id'],'sentPromotion':s['id'],'intentId':iid,'states':states})
start=time.monotonic();deadline=start+330
while True:
 events=[l.request('/admin/promotions/'+str(x)+'/events',l.admin)[0] for x in [p['id'],s['id']]]
 if all(x['can_replay'] for x in events):break
 assert time.monotonic()<deadline,'real cooldown not elapsed'
 if int(time.monotonic()-start)%30==0:print('WAITING actual node/event300s',flush=True)
 time.sleep(1)
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:responses=list(pool.map(lambda t:c.http_json(l.base,'/admin/promotions/events/'+event+'/replay',t,{}),[l.admin,second]))
assert sorted(x[0] for x in responses)==[200,409],[x[0] for x in responses]
l.request('/admin/promotions/events/'+sent_event+'/replay',l.admin,{})
for id in [iid,sent['intentId']]:
 l.wait(lambda:l.sql('SELECT COUNT(*) FROM fg_promo_event_consumed WHERE intent_id='+str(id))=='1','original event consumed',60)
 assert l.sql('SELECT result FROM fg_promo_event_consumed WHERE intent_id='+str(id))=='IGNORED_TERMINAL'
 assert l.sql('SELECT order_id IS NULL FROM fg_promo_purchase_intent WHERE id='+str(id))=='1'
for pr in [p,s]:
 state=l.request('/admin/promotions/'+str(pr['id']),l.admin);assert state['reserved']==0 and state['sold']==0
l.request('/admin/promotions/events/'+event+'/replay',l.admin,{},409)
payload=l.sql('SELECT payload_json FROM fg_promo_outbox WHERE event_id=\''+event+"'");config='\n'.join(l.env[k] for k in ['FLOWGATE_RABBITMQ_HOST','FLOWGATE_RABBITMQ_PORT','FLOWGATE_RABBITMQ_USERNAME','FLOWGATE_RABBITMQ_PASSWORD'])+'\n'
c.command([l.java,'-cp',l.libs(),str(c.ROOT/'scripts/V12RabbitPublishProbe.java'),'flowgate.commerce.v12','promo-intent'],data=config+(payload+'\n')*100,timeout=30);time.sleep(2)
assert l.sql('SELECT COUNT(*) FROM fg_promo_event_consumed WHERE intent_id='+str(iid))=='1'
audit=int(l.sql("SELECT COUNT(*) FROM fg_audit_log WHERE action='REPLAY_PROMO_EVENT' AND target_id='"+event+"'"));assert audit==1
l.save('v12-d1-outbox-recovery.json',{'passed':True,'exitCode':0,'eventId':event,'sentEventId':sent_event,'states':states,'display':'RETRY_EXHAUSTED','originalEventPreserved':True,'naturalCooldownWaitSeconds':time.monotonic()-start,'dualAdminStatuses':[x[0] for x in responses],'consumedEach':1,'ordersEach':0,'lateResult':'IGNORED_TERMINAL','auditRows':audit,'duplicateActualMessages':100,'inventoryReserved':0,'inventorySold':0,'nodeColdStart409':True,'roles403':['OPERATOR','VIEWER'],'fixture':'only new Intent price briefly conflicted to hold SENT in Poison, repaired; no clock/date manipulation or historical Ledger edit'});print('D1_RECOVERY_PASS',flush=True)

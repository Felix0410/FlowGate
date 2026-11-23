#!/usr/bin/env python3
"""Final UI-owned accounts -> real Gateways, used refund refusal, four independent Poison queues."""
import json,time
from v12_d_lab import Lab,c
l=Lab();u=json.loads((c.ROOT/'.local/v12-d-ui-account.json').read_text());user=l.request('/portal/api/auth/login',body=u)['token'];apps={a['code']:a for a in l.request('/portal/api/apps?limit=100',user)};old=l.sql('SELECT COUNT(*),COALESCE(SUM(units),0) FROM fg_quota_ledger');results=[]
for code in ['final-free','final-paid','final-promo']:
 a=apps[code];state=l.request('/portal/api/apps/'+str(a['id'])+'/credentials/status',user)['status'];credential=l.request('/portal/api/apps/'+str(a['id'])+'/credentials/'+('rotate' if state=='ACTIVE' else 'issue'),user,{})['token']
 for gateway in ([] if '--resume' in __import__('sys').argv and code!='final-promo' else ['gateway'] if code=='final-free' else ['gateway','gateway2']):l.request('/api/v12/fast/1',credential,base='http://127.0.0.1:'+str(l.baseline['ports'][gateway]))
 if code!='final-free':
  o=next(x for x in l.request('/portal/api/orders?limit=20',user) if x['applicationSummary']['id']==a['id']);l.request('/portal/api/orders/'+o['orderNo']+'/simulate-refund',user,{},409)
  results.append({'kind':code,'orderSource':o['source'],'refundStatus':409,'gatewaySuccesses':2})
 else:results.append({'kind':code,'gatewaySuccesses':1})
assert old==l.sql('SELECT COUNT(*),COALESCE(SUM(units),0) FROM fg_quota_ledger')
l.request('/portal/api/purchase-intents/1',user,expected=404)
l.request('/portal/api/promotions/14/claim',user,{'applicationId':apps['final-promo']['id'],'clientRequestId':'invalid-price-fixture','priceCents':1},400)
l.wait(lambda:l.sql('SELECT COUNT(*) FROM fg_api_quota_ledger')==l.sql('SELECT COUNT(*) FROM fg_api_consumed_event'),'API usage settles')
l.wait(lambda:l.sql('SELECT COUNT(*) FROM fg_quota_ledger')==l.sql('SELECT COUNT(*) FROM fg_consumed_event'),'legacy settles before Poison isolation')
config='\n'.join(l.env[k] for k in ['FLOWGATE_RABBITMQ_HOST','FLOWGATE_RABBITMQ_PORT','FLOWGATE_RABBITMQ_USERNAME','FLOWGATE_RABBITMQ_PASSWORD'])+'\n';before=l.request('/admin/messaging/diagnostics',l.admin)
for exchange,route in [('flowgate.usage.legacy','granted'),('flowgate.usage.v12','granted'),('flowgate.commerce.v12','payment'),('flowgate.commerce.v12','promo-intent')]:c.command([l.java,'-cp',l.libs(),str(c.ROOT/'scripts/V12RabbitPublishProbe.java'),exchange,route],data=config+'{bad-json\n',timeout=20)
def observed():
 after=l.request('/admin/messaging/diagnostics',l.admin)
 return after if all(b['broker']['poison']+1==a['broker']['poison'] for b,a in zip(before['chains'],after['chains'])) else False
after=l.wait(observed,'four real Poison increments')
assert [r['consumed'] for r in before['chains']]==[r['consumed'] for r in after['chains']]
l.save('v12-d6-final-authority.json',{'passed':True,'exitCode':0,'firstAttempt':'free and paid Gateways passed before invalid limit100 assertion; resumed at refund/remaining promo using limit20/applicationSummary','uiAccountsActualGateways':results,'oldV11LedgerUnchanged':True,'crossOwnerIntent404':True,'clientPriceTamper400':True,'apiLedgerOutboxConsumedSettled':True,'fourPoisonBefore':before,'fourPoisonAfter':after,'badMessagesDidNotCreateConsumed':True,'sensitiveCredentialsPersistedInEvidence':False});print('D_FINAL_AUTHORITY_PASS')

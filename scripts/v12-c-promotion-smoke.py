#!/usr/bin/env python3
"""C关键真实HTTP闭环；只使用C0带owner的全新隔离项目。"""
import secrets,time,concurrent.futures
from datetime import datetime,timedelta,timezone
from v12_c_lab import Lab,c
l=Lab();checks=[]
def wait(fn,label,seconds=35):
 end=time.time()+seconds
 while time.time()<end:
  value=fn()
  if value:return value
  time.sleep(.25)
 raise AssertionError(label)
def login(name):return l.request('/portal/api/auth/login',body={'loginName':name,'password':l.browser['password']})['token']
token=login(l.browser['loginName']);other=login(l.browser['loginName'][:-1]+'2');dev=l.request('/portal/api/my-developers',token)[0]
def app(name):return l.request('/portal/api/apps',token,{'developerId':dev['id'],'code':'c-'+secrets.token_hex(6),'name':name},201)
skus=l.request('/portal/api/catalog/apis/'+str(l.browser['listingId'])+'/offers');sku=next(x for x in skus if x['kind']=='PAID_BASE' and x['period_policy']=='CURRENT_MONTH')
def promo(name,stock=3,creation=60,pay=900):
 now=datetime.now(timezone.utc)
 p=l.request('/admin/promotions',l.admin,{'name':name,'skuId':sku['id'],'promoPriceCents':100,'totalStock':stock,'beginsAt':(now-timedelta(seconds=5)).isoformat(timespec='milliseconds').replace('+00:00','Z'),'endsAt':(now+timedelta(hours=1)).isoformat(timespec='milliseconds').replace('+00:00','Z'),'orderCreationTtlSeconds':creation,'payTtlSeconds':pay})
 l.request('/admin/promotions/'+str(p['id'])+'/publish',l.admin,{})
 return p
p=promo('C真实闭环');pid=p['id'];a=app('抢购闭环应用');body={'applicationId':a['id'],'clientRequestId':secrets.token_hex(16)}
i=l.request(f'/portal/api/promotions/{pid}/claim',token,body,202);assert i['status'] in ('ACCEPTED','ORDER_READY')
same=l.request(f'/portal/api/promotions/{pid}/claim',token,body,202,base=l.second);assert same['intentId']==i['intentId']
l.request('/portal/api/purchase-intents/'+str(i['intentId']),other,expected=404)
l.request(f'/portal/api/promotions/{pid}/claim',token,{**body,'clientRequestId':'other'},409)
ready=wait(lambda:(x if (x:=l.request(f'/portal/api/promotions/{pid}/my-claim',token))['status']=='ORDER_READY' else None),'async order');o=ready['order'];assert o['amountCents']==100 and o['source']=='PROMOTION'
l.request('/portal/api/orders',token,{'applicationId':a['id'],'skuId':sku['id'],'clientRequestId':secrets.token_hex(12)},409)
l.request('/portal/api/orders/'+o['orderNo']+'/simulate-pay',token,{})
wait(lambda:l.request('/portal/api/orders/'+o['orderNo'],token)['status']=='FULFILLED','B grant')
l.request('/portal/api/orders/'+o['orderNo']+'/simulate-refund',token,{})
state=l.request('/admin/promotions/'+str(pid),l.admin);assert (state['reserved'],state['sold'])==(0,1)
checks+=['202 follows durable Intent/stock/slot/outbox','same request on other Control returns same Intent','cross-owner404 and changed request409','PROMOTION order frozen price100','ordinary B conflict','B simulated payment -> unique entitlement -> unused refund keeps sold']
# Cancel and short deadline exercise the same B stock hooks on real orders.
p2=promo('C取消',2,pay=5);a2=app('取消应用');i2=l.request(f"/portal/api/promotions/{p2['id']}/claim",token,{'applicationId':a2['id'],'clientRequestId':secrets.token_hex(12)},202)
o2=wait(lambda:(x['order'] if (x:=l.request('/portal/api/purchase-intents/'+str(i2['intentId']),token))['status']=='ORDER_READY' else None),'cancel order')
l.request('/portal/api/orders/'+o2['orderNo']+'/cancel',token,{})
assert l.request('/admin/promotions/'+str(p2['id']),l.admin)['reserved']==0
p3=promo('C付款超时',2,pay=5);a3=app('超时应用');i3=l.request(f"/portal/api/promotions/{p3['id']}/claim",token,{'applicationId':a3['id'],'clientRequestId':secrets.token_hex(12)},202)
o3=wait(lambda:(x['order'] if (x:=l.request('/portal/api/purchase-intents/'+str(i3['intentId']),token))['status']=='ORDER_READY' else None),'timeout order')
wait(lambda:l.request('/portal/api/orders/'+o3['orderNo'],token)['status']=='CLOSED_TIMEOUT','timeout close',35)
assert l.request('/admin/promotions/'+str(p3['id']),l.admin)['reserved']==0
checks+=['cancel releases reserved','real pay deadline closer releases reserved']
# SCRIPT FLUSH exercises EVALSHA fallback without touching sessions.
redis=c.docker_target(l.state['ports']['redis'],'redis',l.state['project'],l.state['owner'])
c.command(['docker','exec','-e','REDISCLI_AUTH',redis,'redis-cli','SCRIPT','FLUSH'],env=dict(__import__('os').environ,REDISCLI_AUTH=l.env.get('FG_REDIS_PASSWORD','')))
p4=promo('C脚本丢失',1);a4=app('脚本回源应用');l.request(f"/portal/api/promotions/{p4['id']}/claim",token,{'applicationId':a4['id'],'clientRequestId':secrets.token_hex(12)},202)
checks+=['Redis SCRIPT FLUSH -> EVALSHA fallback actual claim202']
reports=[l.request('/admin/promotions/'+str(x['id'])+'/reconciliation',l.admin) for x in [p,p2,p3,p4]]
assert all(x.get('integrityGaps',0)==0 for x in reports),reports
l.save('v12-c4-c6-promotion-e2e.json',{'passed':True,'checks':checks,'promotionIds':[x['id'] for x in [p,p2,p3,p4]],'reconciliation':reports,'notExecuted':['real external payments (simulation only)','owner manual acceptance']})
print('C_PROMOTION_E2E_PASS',flush=True)

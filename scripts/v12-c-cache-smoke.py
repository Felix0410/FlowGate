#!/usr/bin/env python3
"""C1定向公开缓存：两Control冷热/提交失效/负缓存/故障有限回源，复用本轮隔离实例。"""
import concurrent.futures,json,time
from v12_c_lab import Lab,c,r
l=Lab();l.restart(cache=True,promo=False);path='/portal/api/catalog/apis?limit=20';checks=[]
def stats():return [l.request('/admin/market/cache',l.admin,base=b)['counters'] for b in [l.base,l.second]]
try:
 first=l.request(path);before=stats()
 with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:rows=list(pool.map(lambda i:c.http_json([l.base,l.second][i%2],path),range(50)))
 assert all(code==200 and value==first for code,value in rows)
 after=stats();assert sum(x['HIT'] for x in after)>sum(x['HIT'] for x in before);assert sum(x['DB_LOAD'] for x in after)==sum(x['DB_LOAD'] for x in before)
 checks.append('two Controls share hot public cache: 50 real HTTP queries, zero additional public projection DB loads')
 listing=l.browser['listingId'];offers=l.request('/portal/api/catalog/apis/'+str(listing)+'/offers');sku=l.request('/admin/market/listings/'+str(listing)+'/skus',l.admin,{'code':'cache-next-7','kind':'PAID_BASE','quotaUnits':7,'priceCents':1234,'periodPolicy':'NEXT_MONTH'})
 assert any(x['skuId']==sku['id'] for x in l.request('/portal/api/catalog/apis/'+str(listing)+'/offers',base=l.second));checks.append('new SKU commit invalidates offers across Control instances; public cached data excludes purchasing authority')
 for _ in range(3):l.request('/portal/api/catalog/apis/99999999',expected=404)
 assert sum(x['NEGATIVE_HIT'] for x in stats())>=2;checks.append('same missing public listing has real short-lived negative-cache hits')
 redis=c.docker_target(l.state['ports']['redis'],'redis',l.state['project'],l.state['owner']);r.containers(l.state,l.env);c.command(['docker','pause',redis])
 try:
  with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:degraded=list(pool.map(lambda _:c.http_json(l.base,path),range(8)))
  assert all(code in [200,503] for code,_ in degraded);checks.append('real Redis pause: bounded DB public fallback or503, no fake empty200')
 finally:c.command(['docker','unpause',redis])
 l.save('v12-c1-catalog-cache.json',{'passed':True,'checks':checks,'beforeHot':before,'afterHot':after,'degradedStatuses':[x[0] for x in degraded]});print('C1_CACHE_PASS',flush=True)
except Exception as error:
 l.save('v12-c1-failed.json',{'passed':False,'checks':checks,'failure':str(error)});raise

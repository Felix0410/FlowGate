#!/usr/bin/env python3
"""按routeId/phi实际采样quotaHop，避免把多个Gauge聚合值冒充整体p95。"""
import json,secrets,time
from v12_d_lab import Lab,c
l=Lab();user=l.portal();apps=l.request('/portal/api/apps?limit=100',user);pa=max((a for a in apps if a['name']=='D性能充足额度'),key=lambda a:a['id']);v12=l.request('/portal/api/apps/'+str(pa['id'])+'/credentials/rotate',user,{})['token'];result=[]
# Latest benchmark route set is selected from actual metered private APIs, not guessed IDs.
apis=l.request('/admin/apis',l.admin);groups={}
for a in apis:
 path=a['publicPath'];parts=path.split('/')
 if path.startswith('/api/d-bench/') and parts[4].startswith('metered_'):groups.setdefault(parts[3],[]).append(a)
# Name alone does not prove grants. Check selected app actually owns API entitlements.
allowed={int(x) for x in l.sql('SELECT api_id FROM fg_api_subscription WHERE application_id='+str(pa['id'])).splitlines()}
selected=[a for v in groups.values() for a in v if a['id'] in allowed];assert len(selected)==2
run=selected[0]['publicPath'].split('/')[3]
legacy=int(l.sql("SELECT application_id FROM fg_quota_ledger WHERE route_id LIKE 'd-"+run+"-metered_v11-%' ORDER BY id DESC LIMIT 1"));v11=l.request('/admin/apps/'+str(legacy)+'/credentials/rotate',l.admin,{'scopes':['demo:read']})['token']
for a in groups[run]:
 parts=a['publicPath'].split('/');mode,kind=parts[4],parts[5];route='d-'+run+'-'+mode+'-'+kind;key=v12 if mode=='metered_v12' else v11
 for i,g in enumerate(['http://127.0.0.1:'+str(l.baseline['ports'][k]) for k in ['gateway','gateway2']]):
  path=a['publicPath'].replace('{id}','1')+('?delayMs=10' if kind=='slow' else '')
  for n in range(20):l.request(path,key,base=g)
  metrics=l.request('/actuator/metrics/flowgate.quota.hop.percentile?tag=routeId:'+route+'&tag=phi:0.95',base=g);assert metrics['baseUnit']=='seconds';result.append({'gateway':i,'routeId':route,'mode':mode,'kind':kind,'sampleCount':20,'p95Seconds':metrics['measurements'][0]['value'],'raw':metrics})
l.save('v12-d4-quota-hop.json',{'passed':True,'exitCode':0,'window':'separate fresh20 perroute perGateway diagnostics after fair benchmark; not global or pooled percentile','samples':result});print('D_QUOTA_HOP_REAL_P95_PASS')

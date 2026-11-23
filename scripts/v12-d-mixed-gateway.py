#!/usr/bin/env python3
"""真实A历史二进制混版；D owner pin后运行，故障结束恢复新双Gateway和原快照。"""
import os,signal,subprocess,secrets
from v12_d_lab import Lab,c,r
l=Lab();legacy=c.ROOT/'.local/v11-a-legacy-binary/flowgate-gateway/target/flowgate-gateway-0.0.1-SNAPSHOT.jar';assert legacy.is_file();pinned=l.home/'artifacts/d-legacy-gateway.jar';pinned.write_bytes(legacy.read_bytes());latest=l.request('/admin/releases/current',l.admin)['version']
def rollback(version,expected=200):
 cur=l.request('/admin/releases/current',l.admin)['version'];result=l.request('/admin/releases/'+str(version)+'/rollback',l.admin,{'expectedVersion':cur},expected)
 if expected==200:l.wait(lambda:sum(n['appliedVersion']==result['version'] for n in l.request('/admin/nodes',l.admin))==2,'both applied')
 return result
rollback(1);pid=l.cleanup['secondPid'];identity=r.identity(pid);assert str(l.home/'artifacts/flowgate-gateway.jar') in identity[0] and l.state['owner'] in identity[0];os.kill(pid,signal.SIGTERM);r.wait_for(lambda:not r.identity(pid),'second owner stop',20)
env=dict(os.environ,**l.env);env.update(GATEWAY_PORT=str(l.baseline['ports']['gateway2']),FLOWGATE_NODE_ID='v12-second-'+l.work.name)
for k in list(env):
 if k.startswith(('FG_MYSQL_','FG_BOOTSTRAP_','FLOWGATE_ASYNC_','FLOWGATE_QUOTA_','FLOWGATE_RABBITMQ_','FLOWGATE_V12_','FLOWGATE_PORTAL_')):env.pop(k)
with (l.work/'d-legacy-gateway.log').open('w') as f:process=subprocess.Popen([l.java,'-Dflowgate.v12-test.owner='+l.state['owner'],'-jar',str(pinned)],env=env,cwd=c.ROOT,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
try:
 l.wait(lambda:any(n['nodeId']==env['FLOWGATE_NODE_ID'] and n['freshness']=='ONLINE' and n.get('supportedFeatures')==[] for n in l.request('/admin/nodes',l.admin)),'actual legacy capabilities',50)
 old=l.request('/admin/callers',l.admin,{'username':'d-mixed-'+secrets.token_hex(5),'scopes':['demo:read']},201)['token'];base='http://127.0.0.1:'+str(l.baseline['ports']['gateway2']);l.request('/api/v12/fast/1',old,base=base)
 user=l.portal();a=l.app(user);token=l.request('/portal/api/apps/'+str(a['id'])+'/credentials/issue',user,{},409) # backend refuses issue while incompatible node active
 cur=l.request('/admin/releases/current',l.admin)['version'];l.request('/admin/releases/publish',l.admin,{'expectedVersion':cur},409);rollback(latest,409)
finally:
 actual=r.identity(process.pid)
 if actual:assert str(pinned) in actual[0] and l.state['owner'] in actual[0];os.kill(process.pid,signal.SIGTERM);process.wait(20)
 l.cleanup['secondPid']=None;r.save(l.work/'cleanup.json',l.cleanup);l.restart_gateways();l.wait(lambda:sum('USAGE_QUOTA_V12' in n.get('supportedFeatures',[]) for n in l.request('/admin/nodes',l.admin))==2,'restored new capabilities');rollback(latest)
l.save('v12-d2-mixed-gateway.json',{'passed':True,'exitCode':0,'legacyJarSha256':c.file_hash(pinned),'checks':['actual A legacy binary reports no quota features','legacy Caller200 on nonquota snapshot','quota credential issuance409 with mixed node','metered publish and rollback409 until old node removed','new owner Gateways restored and original latest snapshot reapplied'],'userAssetsTouched':False});print('D_MIXED_GATEWAY_PASS',flush=True)

#!/usr/bin/env python3
"""本机D最终候选预览：只接管D0证据的隔离owner，不迁移flowgate-local；所有卷保留。"""
import argparse,os,signal,subprocess
from pathlib import Path
from v12_d_lab import Lab,c,r
p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['start','status','stop','doctor']);args=p.parse_args();l=Lab(authenticate=False)
if args.action=='start':
 if any(l.state.get('artifactHashes',{}).get(m)!=c.file_hash(c.jar_path(m)) for m in c.MODULES):
  subprocess.run([__import__('sys').executable,__file__,'stop'],check=True)
  l=Lab(authenticate=False)
 l.stop_controls()
 # 1. 运行器核验既有owner后按原配置恢复，不初始化已有库，不改用户.env。
 r.start(l.home,Path(l.state['configPath']) if 'configPath' in l.state else l.work/'config.env',l.state['ports'])
 l.state,l.env=r.load(l.home);l.restart(cache=True,promo=True)
 # 2. 原D0第二Gateway是本轮自有实例；按命令行owner与Jar同时核验再复用/恢复。
 pid=l.cleanup.get('secondPid');identity=r.identity(pid) if pid else None
 if identity:
  if str(l.home/'artifacts/flowgate-gateway.jar') not in identity[0] or '-Dflowgate.v12-test.owner='+l.state['owner'] not in identity[0]:raise c.LocalFailure('第二Gateway身份已变化，拒绝接管')
 else:
  env=dict(os.environ,**l.env);env.update(GATEWAY_PORT=str(l.baseline['ports']['gateway2']),FLOWGATE_NODE_ID='v12-second-'+l.work.name)
  with (l.work/'gateway2.log').open('a') as log:process=subprocess.Popen([l.java,'-Dflowgate.v12-test.owner='+l.state['owner'],'-jar',str(l.home/'artifacts/flowgate-gateway.jar')],cwd=c.ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
  l.cleanup['secondPid']=process.pid;r.save(l.work/'cleanup.json',l.cleanup)
 r.wait_for(lambda:c.http_json('http://127.0.0.1:'+str(l.baseline['ports']['gateway2']),'/actuator/health')[0]==200,'第二Gateway健康',45)
 r.wait_for(lambda:len([n for n in c.http_json(l.base,'/admin/nodes',l.admin)[1] if n.get('freshness')=='ONLINE' and 'USAGE_QUOTA_V12' in n.get('supportedFeatures',[])])==2,'双Gateway能力和心跳',45)
 print('Portal '+l.base+'/portal/#promotions\nConsole '+l.base+'/console/#business/market\n仅本轮隔离数据；账号见.local/v12-d-access.md')
elif args.action=='stop':
 l.stop_controls()
 pid=l.cleanup.get('secondPid');identity=r.identity(pid) if pid else None
 if identity:
  if str(l.home/'artifacts/flowgate-gateway.jar') not in identity[0] or '-Dflowgate.v12-test.owner='+l.state['owner'] not in identity[0]:raise c.LocalFailure('第二Gateway身份已变化，拒绝停止')
  os.kill(pid,signal.SIGTERM);r.wait_for(lambda:r.identity(pid) is None,'本轮第二Gateway停止',20)
 print(r.stop(l.home))
elif args.action=='doctor':
 print(r.status(l.home))
else:
 print({'project':l.state['project'],'control':c.http_json(l.base,'/status')[0] if r.owned_pid(l.home,l.state,'flowgate-control') else 'STOPPED','portal':l.base+'/portal/#promotions','volumes':'保留'})

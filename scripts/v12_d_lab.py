"""D只接管本轮全新owner；C旧预览和flowgate-local从不作为D测试环境。"""
from v12_c_lab import Lab as BaseLab,c,r
import json,os,time,subprocess,secrets
from datetime import datetime,timedelta,timezone
class Lab(BaseLab):
 def __init__(self,authenticate=True):
  super().__init__(authenticate,baseline='v12-d0-e2e.json')
  assert self.baseline['sourceCommit']!=json.loads((c.ROOT/'docs/progress/evidence/v12-c0-e2e.json').read_text())['sourceCommit']
  assert self.state['owner']!=json.loads((c.ROOT/'docs/progress/evidence/v12-c1-catalog-cache.json').read_text())['owner']
 def portal(self):return self.request('/portal/api/auth/login',body={'loginName':self.browser['loginName'],'password':self.browser['password']})['token']
 def container(self,name):return c.docker_target(self.state['ports'][name],name,self.state['project'],self.state['owner'])
 def redis(self,*args,data=None):return c.command(['docker','exec',*(['-i'] if data else []),'-e','REDISCLI_AUTH',self.container('redis'),'redis-cli',*args],env=dict(os.environ,REDISCLI_AUTH=self.env.get('FG_REDIS_PASSWORD','')),data=data)
 def wait(self,fn,label,seconds=60):
  end=time.monotonic()+seconds
  while time.monotonic()<end:
   try:v=fn()
   except c.LocalFailure:v=False
   if v:return v
   time.sleep(.3)
  raise AssertionError(label)
 def app(self,token,name='D验收'):
  dev=self.request('/portal/api/my-developers',token)[0]
  return self.request('/portal/api/apps',token,{'developerId':dev['id'],'code':'d-'+secrets.token_hex(8),'name':name},201)
 def promotion(self,name,stock=100,creation=300,pay=900):
  sku=next(s for s in self.request('/portal/api/catalog/apis/'+str(self.browser['listingId'])+'/offers') if s['kind']=='PAID_BASE' and s['period_policy']=='CURRENT_MONTH');now=datetime.now(timezone.utc);iso=lambda t:t.isoformat(timespec='milliseconds').replace('+00:00','Z')
  p=self.request('/admin/promotions',self.admin,{'name':name,'skuId':sku['id'],'promoPriceCents':100,'totalStock':stock,'beginsAt':iso(now-timedelta(seconds=1)),'endsAt':iso(now+timedelta(hours=2)),'orderCreationTtlSeconds':creation,'payTtlSeconds':pay})
  self.request('/admin/promotions/'+str(p['id'])+'/publish',self.admin,{})
  return p

 def restart_gateways(self):
  import signal
  ids=[self.state.get('processes',{}).get('flowgate-gateway',{}).get('pid'),self.cleanup.get('secondPid')]
  jar=self.home/'artifacts/flowgate-gateway.jar'
  for pid in ids:
   actual=r.identity(pid) if pid else None
   if actual:
    assert str(jar) in actual[0] and ('-Dflowgate.v11-demo.owner='+self.state['owner'] in actual[0] or '-Dflowgate.v12-test.owner='+self.state['owner'] in actual[0])
    if pid==ids[0]:assert actual[1]==self.state['processes']['flowgate-gateway']['start']
    os.kill(pid,signal.SIGTERM)
  r.wait_for(lambda:all(not r.identity(p) for p in ids if p),'自有Gateway退出',30);jar.write_bytes(c.jar_path('flowgate-gateway').read_bytes())
  for i,port in enumerate([self.state['ports']['gateway'],self.baseline['ports']['gateway2']]):
   env=dict(os.environ,**self.env);env['GATEWAY_PORT']=str(port)
   if i:env['FLOWGATE_NODE_ID']='v12-second-'+self.work.name
   for k in list(env):
    if k.startswith(('FG_MYSQL_','FG_BOOTSTRAP_','FLOWGATE_ASYNC_','FLOWGATE_QUOTA_','FLOWGATE_RABBITMQ_','FLOWGATE_V12_','FLOWGATE_PORTAL_')):env.pop(k)
   with (self.work/('d-gateway'+str(i)+'.log')).open('a') as log:p=subprocess.Popen([self.java,('-Dflowgate.v12-test.owner=' if i else '-Dflowgate.v11-demo.owner=')+self.state['owner'],'-jar',str(jar)],cwd=c.ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
   r.wait_for(lambda:c.http_json('http://127.0.0.1:'+str(port),'/actuator/health')[0]==200,'自有Gateway启动',45)
   if i==0:self.state['processes']['flowgate-gateway']={'pid':p.pid,'start':r.identity(p.pid)[1]}
   else:self.cleanup['secondPid']=p.pid
  self.state['artifactHashes']['flowgate-gateway']=c.file_hash(c.jar_path('flowgate-gateway'));r.save(self.home/'state.json',self.state);r.save(self.work/'cleanup.json',self.cleanup)

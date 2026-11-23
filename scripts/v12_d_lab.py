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

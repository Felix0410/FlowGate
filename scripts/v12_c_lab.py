"""C验证共用本轮C0隔离实例；只核对并接管该owner，绝不接管flowgate-local。"""
import json,os,signal,subprocess,time,zipfile
from pathlib import Path
import v11_local_core as c
import v11_local_runtime as r
class Lab:
 def __init__(self):
  self.baseline=json.loads((c.ROOT/'docs/progress/evidence/v12-c0-e2e.json').read_text());self.work=Path(self.baseline['localRuntime']);self.cleanup=json.loads((self.work/'cleanup.json').read_text());self.home=Path(self.cleanup['home']);self.state,self.env=r.load(self.home);self.browser=json.loads((self.work/'browser.json').read_text());self.base=self.browser['control'];self.second='http://127.0.0.1:'+str(self.baseline['ports']['control2']);self.java=str(Path(self.env['JAVA_HOME'])/'bin/java');self.admin=self.login();self.requests=[]
 def sql(self,q,write=False):return c.mysql_query(self.env,q,self.state['project'],self.state['owner'],read_only=not write)
 def request(self,path,token=None,body=None,expected=200,base=None,method=None):
  code,value=c.http_json(base or self.base,path,token,body,method);self.requests.append({'path':path,'status':code})
  if code!=expected:raise AssertionError((path,code,value.get('code') if isinstance(value,dict) else 'non-json'))
  return value
 def login(self):
  code,value=c.http_json(self.base,'/admin/auth/login',body={'username':self.browser['adminLogin'],'password':self.browser['password']});assert code==200;return value['token']
 def stop_controls(self):
  pids=[r.owned_pid(self.home,self.state,'flowgate-control'),self.cleanup.get('secondControlPid')]
  for pid in pids:
   actual=r.identity(pid) if pid else None
   if actual:
    assert str(self.home/'artifacts/flowgate-control.jar') in actual[0] and ('-Dflowgate.v11-demo.owner='+self.state['owner'] in actual[0] or '-Dflowgate.v12-test.owner='+self.state['owner'] in actual[0]);os.kill(pid,signal.SIGTERM)
  r.wait_for(lambda:all(not r.identity(p) for p in pids if p),'本轮双Control退出',30)
 def restart(self,cache=True,promo=False):
  self.stop_controls();self.env.update(FLOWGATE_V12_CATALOG_CACHE_ENABLED=str(cache).lower(),FLOWGATE_V12_PROMOTION_ENABLED=str(promo).lower());r.save(self.home/'env.json',self.env)
  jar=self.home/'artifacts/flowgate-control.jar';jar.write_bytes(c.jar_path('flowgate-control').read_bytes())
  for index,port in enumerate([self.state['ports']['control'],self.baseline['ports']['control2']]):
   env=dict(os.environ,**self.env);env['CONTROL_PORT']=str(port)
   with (self.work/('c-control'+str(index)+'.log')).open('a') as log:p=subprocess.Popen([self.java,'-Dflowgate.v11-demo.owner='+self.state['owner'],'-jar',str(jar)],cwd=c.ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
   r.wait_for(lambda:c.http_json('http://127.0.0.1:'+str(port),'/status')[0]==200,'C Control启动',45)
   if index==0:self.state['processes']['flowgate-control']={'pid':p.pid,'start':r.identity(p.pid)[1]}
   else:self.cleanup['secondControlPid']=p.pid
  self.state['artifactHashes']['flowgate-control']=c.file_hash(c.jar_path('flowgate-control'));r.save(self.home/'state.json',self.state);r.save(self.work/'cleanup.json',self.cleanup);self.admin=self.login()
 def save(self,name,data):
  data.update(sourceCommit=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),controlJarSha256=c.file_hash(c.jar_path('flowgate-control')),owner=self.state['owner'],project=self.state['project'],localRuntime=str(self.work),requests=self.requests,rootEnvUnchanged=self.baseline['rootEnvUnchanged']);(c.ROOT/'docs/progress/evidence'/name).write_text(json.dumps(data,ensure_ascii=False,indent=2,default=str)+'\n')
 def libs(self):
  target=self.work/'c-libs';r.private_directory(target)
  with zipfile.ZipFile(c.jar_path('flowgate-control')) as jar:
   for name in jar.namelist():
    if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):(target/Path(name).name).write_bytes(jar.read(name))
  return os.pathsep.join([str(c.ROOT/'flowgate-control/target/classes'),str(c.ROOT/'flowgate-contract/target/classes'),str(target/'*')])

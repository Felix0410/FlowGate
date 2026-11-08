#!/usr/bin/env python3
"""B0独立阻断：非UTC服务端、两JVM时区、真实池/Mapper/旧V1及订阅月度锁。"""
from datetime import datetime,timezone,timedelta
import importlib.util
import json,os,subprocess,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('a',ROOT/'scripts/v11-a-smoke.py');a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
class Lab(a.Lab):
 def __init__(self,zone):super().__init__();self.zone=zone
 def docker(self,image,args,env=None):
  if image!='mysql:8.4':return super().docker(image,args,env)
  name='fg-b0-'+__import__('secrets').token_hex(5);self.containers.append(name)
  a.b2.command(['docker','run','-d','--name',name,*args,image,'--default-time-zone=+08:00'],env=env);return name
 def app(self,module,extra=None):return super().app(module,dict(extra or {},JAVA_TOOL_OPTIONS='-Duser.timezone='+self.zone))
def main():
 output=ROOT/('.local/acceptance/v11-b0-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')+'.json');cases=[];passed=False
 try:
  for zone in ('Asia/Shanghai','America/Los_Angeles'):
   lab=Lab(zone);checks={}
   try:
    a.run(lab,checks)
    # 1. 实际Control池所有闲置连接都必须UTC，数据库默认非UTC。
    actual=lab.sql("SELECT DISTINCT v.VARIABLE_VALUE FROM performance_schema.variables_by_thread v JOIN performance_schema.threads t ON t.THREAD_ID=v.THREAD_ID WHERE v.VARIABLE_NAME='time_zone' AND t.PROCESSLIST_DB='flowgate' AND t.PROCESSLIST_COMMAND='Sleep'")
    assert actual=='+00:00',actual
    libs=lab.temp/'probe-libs';libs.mkdir();classes=lab.temp/'probe-classes';classes.mkdir()
    with zipfile.ZipFile(ROOT/'flowgate-control/target/flowgate-control-0.0.1-SNAPSHOT.jar') as jar:
     for item in jar.namelist():
      if item.startswith('BOOT-INF/lib/') and item.endswith('.jar'):(libs/Path(item).name).write_bytes(jar.read(item))
      elif item.startswith('BOOT-INF/classes/') and not item.endswith('/'):
       target=classes/item.removeprefix('BOOT-INF/classes/');target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(jar.read(item))
    mysqlport=a.b2.command(['docker','port',lab.mysql,'3306']).split(':')[-1]
    probe=subprocess.run([a.b2.JAVA,'-Duser.timezone='+zone,'-cp',str(classes)+os.pathsep+str(libs/'*'),str(ROOT/'scripts/V11B0JdbcProbe.java')],env=dict(os.environ,PROBE_URL=f'jdbc:mysql://127.0.0.1:{mysqlport}/flowgate?connectionTimeZone=UTC',PROBE_PASSWORD=lab.password),capture_output=True,text=True)
    assert probe.returncode==0,probe.stderr[-2000:];result=json.loads(next(line for line in probe.stdout.splitlines() if line.startswith('{')))
    # 2. 旧时间到期且不重叠的同月套餐仍必须拒绝；既有数据仅测试库插入。
    app=lab.admin_call(201,'/admin/apps',body={'developerId':1,'code':'b0-gap','name':'月度缺口'})
    now=datetime.fromisoformat(lab.sql("SELECT DATE_FORMAT(UTC_TIMESTAMP(3),'%Y-%m-%dT%H:%i:%s.%f')")).replace(tzinfo=timezone.utc)
    month=now.replace(day=1,hour=0,minute=0,second=0,microsecond=0);end=a.datetime(now.year+(now.month==12),1 if now.month==12 else now.month+1,1,tzinfo=timezone.utc)
    assert now>month+timedelta(seconds=2),'月首前两秒需用固定数据库时钟场景执行'
    fmt=lambda t:t.isoformat().replace('+00:00','Z')
    lab.sql(f"SET time_zone='+00:00';INSERT INTO fg_subscription(application_id,plan_id,effective_at,expires_at,status,plan_version_snapshot) VALUES({app['id']},1,'{month.strftime('%Y-%m-%d %H:%M:%S')}','{(month+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S')}','EXPIRED',1)")
    denied=lab.admin_call(409,'/admin/subscriptions',body={'applicationId':app['id'],'planId':2,'effectiveAt':fmt(month+timedelta(seconds=1)),'expiresAt':fmt(end)})
    assert denied['code']=='SUBSCRIPTION_CONFLICT'
    nodes=lab.admin_call(200,'/admin/nodes');assert all(n['freshness']=='ONLINE' for n in nodes)
    cases.append({'zone':zone,'probe':result,'actualControlPoolSessionTimeZone':actual,'sameMonthNonOverlappingRejected':True,'checks':checks,'requests':lab.evidence})
    print('PASS B0 非UTC MySQL/JVM '+zone+'：池/Mapper/毫秒/同月锁/旧V1',flush=True)
   finally:lab.close()
  passed=True
 finally:
  output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps({'passed':passed,'cases':cases},ensure_ascii=False,indent=2));output.chmod(0o600);print('B0本机证据：',output,'passed=',passed,flush=True)
if __name__=='__main__':main()

#!/usr/bin/env python3
"""C4真MySQL：运营查询、复合游标、三态对账和重排审计。"""
import importlib.util,subprocess,os,json,zipfile
from datetime import datetime,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('lab',ROOT/'scripts/block2-smoke.py');b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
lab=b.Lab();passed=False;result=None
try:
 lab.start();lab.sql((ROOT/'sql/flowgate-v11.sql').read_text());ddl=(ROOT/'sql/flowgate-v11-c.sql').read_text();lab.sql(ddl);lab.sql(ddl);assert lab.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='flowgate' AND table_name IN ('fg_consumed_event','fg_usage_daily')")=='2'
 libs=lab.temp/'probe-libs';libs.mkdir();classes=lab.temp/'probe-classes';classes.mkdir()
 with zipfile.ZipFile(ROOT/'flowgate-control/target/flowgate-control-0.0.1-SNAPSHOT.jar') as jar:
  for item in jar.namelist():
   if item.startswith('BOOT-INF/lib/') and item.endswith('.jar'):(libs/Path(item).name).write_bytes(jar.read(item))
   elif item.startswith('BOOT-INF/classes/') and not item.endswith('/'):
    p=classes/item.removeprefix('BOOT-INF/classes/');p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(jar.read(item))
 child=subprocess.run([b.JAVA,'-cp',str(classes)+os.pathsep+str(libs/'*'),str(ROOT/'scripts/V11QuotaDbProbe.java')],env=lab.env,capture_output=True,text=True)
 (lab.temp/'quota-probe.log').write_text(child.stdout+'\n'+child.stderr)
 assert child.returncode==0,child.stderr[-3500:]
 result=json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{')));assert result['passed'];passed=False
 child=subprocess.run([b.JAVA,'-cp',str(classes)+os.pathsep+str(libs/'*'),str(ROOT/'scripts/V11AsyncOperationsProbe.java')],env=lab.env,capture_output=True,text=True)
 (lab.temp/'operations-probe.log').write_text(child.stdout+'\n'+child.stderr);assert child.returncode==0,child.stderr[-1500:]
 result=json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{')));assert result['passed'];passed=True
 print('PASS C4 真实运营查询/同日复合游标/对账PENDING-MATCH-MISMATCH/重投审计/脱敏',flush=True)
finally:
 lab.close();target=ROOT/('.local/acceptance/v11-c4-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps({'passed':passed,'result':result},ensure_ascii=False,indent=2));target.chmod(0o600);print('C4本机证据：',target,'passed=',passed)

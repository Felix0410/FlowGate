#!/usr/bin/env python3
"""F1只从仓库Compose提取Kafka到独立项目；down/up留测试卷，最终仅删本项目新卷。"""
import importlib.util,os,subprocess,json,tempfile,secrets,zipfile,time
from pathlib import Path
from datetime import datetime,timezone
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('base',ROOT/'scripts/block2-smoke.py');b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
project='fg-c-volume-'+secrets.token_hex(5);temp=Path(tempfile.mkdtemp(prefix=project+'-'));port=b.port();env=dict(os.environ,FLOWGATE_KAFKA_PORT=str(port));passed=False;results=[];composefile=temp/'compose.json'
def compose(*args):return b.command(['docker','compose','-p',project,'-f',str(composefile),*args],env=env)
try:
 config=json.loads(b.command(['docker','compose','-f',str(ROOT/'compose.local.yml'),'--profile','mq','config','--format','json'],env=env))
 # 1. 只保留Kafka，移除规范化配置中的固定卷名，绝不引用用户MySQL/Redis服务或卷。
 kafka=config['services']['kafka'];kafka.pop('profiles',None)
 composefile.write_text(json.dumps({'services':{'kafka':kafka},'volumes':{'kafka-data':{}}}));composefile.chmod(0o600)
 libs=temp/'libs';libs.mkdir()
 with zipfile.ZipFile(ROOT/'flowgate-control/target/flowgate-control-0.0.1-SNAPSHOT.jar') as jar:
  for n in jar.namelist():
   if n.startswith('BOOT-INF/lib/') and n.endswith('.jar'):(libs/Path(n).name).write_bytes(jar.read(n))
 def ready():
  cid=compose('ps','-q','kafka');return bool(cid) and b.command(['docker','exec',cid,'/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','localhost:9092','--list']) is not None
 def probe(mode):
  p=subprocess.run([b.JAVA,'-cp',str(libs/'*'),str(ROOT/'scripts/V11KafkaVolumeProbe.java'),f'127.0.0.1:{port}',mode],env=env,text=True,capture_output=True,timeout=65);(temp/(mode+'.log')).write_text(p.stdout+'\n'+p.stderr);assert p.returncode==0,(mode,p.stderr[-800:]);return json.loads(next(line for line in p.stdout.splitlines() if line.startswith('{')))
 compose('up','-d','kafka');b.wait(ready,'Kafka新卷UID权限及KRaft启动',100);first=compose('ps','-q','kafka');results.append(probe('write'))
 # 2. 删除容器而不是stop/start；不传-v，重建后容器ID必须改变。
 compose('down');compose('up','-d','kafka');b.wait(ready,'Kafka持卷重建',100);second=compose('ps','-q','kafka');assert first!=second;results.append(probe('check'));passed=True
 print('PASS F1 隔离Compose真实down/up，消息offset0与已提交组位点1保留',flush=True)
finally:
 if composefile.exists():
  subprocess.run(['docker','compose','-p',project,'-f',str(composefile),'down'],env=env,capture_output=True)
  # 3. 只删除这一次随机项目新建的Kafka卷，绝不删除其他卷。
  subprocess.run(['docker','volume','rm',project+'_kafka-data'],capture_output=True)
 target=ROOT/('.local/acceptance/v11-c-volume-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps({'passed':passed,'proofs':results,'isolatedCompose':True,'newVolumeOnly':True},ensure_ascii=False,indent=2));target.chmod(0o600);print('F1卷证据',target,'passed=',passed,'临时日志',temp)

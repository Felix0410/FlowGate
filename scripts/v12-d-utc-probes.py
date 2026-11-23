#!/usr/bin/env python3
"""D MySQL新独立schema里重跑原真实事务探针：跨月去重/UTC日/汇总回滚。不调系统时钟。"""
import os,secrets,subprocess,json,time
from v12_d_lab import Lab,c
l=Lab();cid=l.container('mysql');db='fgd_utc_'+secrets.token_hex(5);source=c.jdbc_target(l.env)[1];rootenv=dict(os.environ,MYSQL_PWD=l.env['FG_MYSQL_ROOT_PASSWORD'])
c.command(['docker','exec','-e','MYSQL_PWD',cid,'mysql','-u','root','-e','CREATE DATABASE '+db],env=rootenv)
def sql(q):return c.command(['docker','exec','-i','-e','MYSQL_PWD',cid,'mysql','-u','root','-D',db,'-N','-B'],env=rootenv,data=q,timeout=30)
for f in ['flowgate-init.sql','flowgate-v11.sql','flowgate-v11-c.sql','flowgate-v12-m.sql']:sql((c.ROOT/'sql'/f).read_text())
for table in ['fg_service','fg_api']:sql('INSERT INTO '+table+' SELECT * FROM '+source+'.'+table+';')
sql('INSERT INTO fg_config_release SELECT * FROM '+source+'.fg_config_release WHERE version=1;');sql("UPDATE fg_legacy_mq_cutover SET mode='RABBIT',epoch=2 WHERE id=1;")
env=dict(os.environ,**l.env);env.update(FG_REDIS_DATABASE='14',FG_MYSQL_URL=f'jdbc:mysql://127.0.0.1:{l.state["ports"]["mysql"]}/{db}?connectionTimeZone=UTC',FG_MYSQL_USERNAME='root',FG_MYSQL_PASSWORD=l.env['FG_MYSQL_ROOT_PASSWORD'],FLOWGATE_ASYNC_ENABLED='false',FLOWGATE_PORTAL_ENABLED='false',FLOWGATE_V12_QUOTA_ENABLED='false',FLOWGATE_V12_ASYNC_ENABLED='false',FLOWGATE_V12_COMMERCE_ENABLED='false',FLOWGATE_V12_COMMERCE_ASYNC_ENABLED='false',FLOWGATE_V12_PROMOTION_ENABLED='false',FLOWGATE_V12_CATALOG_CACHE_ENABLED='false');results=[]
for file in ['V11QuotaDbProbe.java','V11UsageAggregationProbe.java']:
 st=time.monotonic()
 with (l.work/('d-utc-'+file+'.log')).open('w') as f:p=subprocess.run([l.java,'-cp',l.libs(),str(c.ROOT/'scripts'/file)],env=env,stdout=f,stderr=subprocess.STDOUT,timeout=100)
 assert p.returncode==0,file
 text=(l.work/('d-utc-'+file+'.log')).read_text();lines=[x for x in text.splitlines() if x.startswith('{"passed":true')];assert lines,file;results.append({'file':file,'exitCode':0,'seconds':time.monotonic()-st,'result':json.loads(lines[-1])})
l.save('v12-d2-utc-transactions.json',{'passed':True,'exitCode':0,'independentSchema':db,'probes':results,'clock':'only fresh Hikari connections in independent schema have nextmonth session timestamp; OS/business controls unchanged','covers':['100 different charges Free5','100 samecharge transactions','next month duplicate returns original month without second balance','100 same event consumer concurrency','real RENAME daily failure rolls back consumed marker','late Ledger insertion date does not change source event UTC day','duplicate charge/source conflict rejected'],'mainBusinessDatabaseUntouched':True});print('D_UTC_TRANSACTION_PASS',flush=True)

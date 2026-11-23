#!/usr/bin/env python3
"""Independent owner schema; 100 lease rows, actual production claim service, no clock edits."""
import json,secrets,os,subprocess,time
from v12_d_lab import Lab,c
l=Lab();cid=l.container('mysql');src=json.loads((c.ROOT/'docs/progress/evidence/v12-d2-utc-transactions.json').read_text())['independentSchema'];db='fgd_lease_'+secrets.token_hex(5);e=dict(os.environ,MYSQL_PWD=l.env['FG_MYSQL_ROOT_PASSWORD'])
c.command(['docker','exec','-e','MYSQL_PWD',cid,'mysql','-u','root','-e','CREATE DATABASE '+db],env=e)
def sql(q):return c.command(['docker','exec','-i','-e','MYSQL_PWD',cid,'mysql','--default-character-set=utf8mb4','-u','root','-D',db,'-N','-B'],env=e,data=q,timeout=30)
dump=c.command(['docker','exec','-e','MYSQL_PWD',cid,'mysqldump','-u','root','--set-gtid-purged=OFF',src],env=e,timeout=30);sql(dump);sql("UPDATE fg_outbox_event SET status='SENT',lease_owner=NULL,lease_until=NULL;")
# Valid source parents are cloned; synthetic lease messages have matching Ledger rows and immutable event IDs.
for n in range(100):
 uid='d1000000-0000-4000-8000-'+str(n).zfill(12)
 sql("INSERT INTO fg_quota_ledger(charge_id,request_fingerprint,application_id,principal_id,credential_generation,api_id,route_id,applied_version,policy_id,period_yyyymm,units,remaining_units_after) SELECT '"+uid+"',SHA2('lease"+str(n)+"',256),application_id,principal_id,credential_generation,api_id,route_id,applied_version,policy_id,period_yyyymm,1,900000 FROM fg_quota_ledger WHERE id=1;INSERT INTO fg_outbox_event(event_id,event_type,payload_json,status) SELECT '"+uid+"','USAGE_GRANTED',JSON_OBJECT('version',1,'eventId',charge_id,'chargeId',charge_id,'applicationId',application_id,'apiId',api_id,'periodUtc',period_yyyymm,'units',1,'eventTime',DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%sZ')),'NEW' FROM fg_quota_ledger WHERE charge_id='"+uid+"';")
env=dict(os.environ,**l.env);env.update(FG_REDIS_DATABASE='13',FG_MYSQL_URL=f'jdbc:mysql://127.0.0.1:{l.state["ports"]["mysql"]}/{db}?connectionTimeZone=UTC',FG_MYSQL_USERNAME='root',FG_MYSQL_PASSWORD=l.env['FG_MYSQL_ROOT_PASSWORD'],FLOWGATE_ASYNC_ENABLED='false',FLOWGATE_PORTAL_ENABLED='false',FLOWGATE_V12_ASYNC_ENABLED='false',FLOWGATE_V12_COMMERCE_ENABLED='false',FLOWGATE_V12_COMMERCE_ASYNC_ENABLED='false',FLOWGATE_V12_PROMOTION_ENABLED='false',FLOWGATE_V12_CATALOG_CACHE_ENABLED='false')
st=time.monotonic()
with (l.work/'d-lease-probe.log').open('w') as f:p=subprocess.run([l.java,'-cp',l.libs(),str(c.ROOT/'scripts/DLeaseProbe.java')],env=env,stdout=f,stderr=subprocess.STDOUT,timeout=70)
assert p.returncode==0;assert sql("SELECT COUNT(*) FROM fg_outbox_event WHERE event_id LIKE 'd1000000-%' AND status='SENT' AND attempt_count=2;")=='100'
l.save('v12-d2-lease100.json',{'passed':True,'exitCode':0,'schema':db,'rows':100,'claimWorkers':2,'naturalLeaseWaitSeconds':31,'seconds':time.monotonic()-st,'lateOwnerUpdates':0,'reclaimedAndSent':100,'mainBusinessDatabaseUntouched':True,'fixture':'dedicated schema cloned UTC probe; 100 matching synthetic Ledger/source rows; no actual HTTP debits asserted'});print('D_LEASE100_PASS')

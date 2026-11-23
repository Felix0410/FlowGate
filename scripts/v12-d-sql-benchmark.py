#!/usr/bin/env python3
"""D owner内部单独克隆schema，100k合法关系fixture；不向业务演示库写样本。"""
import os,json,time,re,statistics,secrets,csv
from pathlib import Path
from v12_d_lab import Lab,c
l=Lab();cid=l.container('mysql');db='fgd_sql_'+secrets.token_hex(5);assert l.state['project']!='flowgate-local'
env=dict(os.environ,MYSQL_PWD=l.env['FG_MYSQL_ROOT_PASSWORD'])
def sql(q,timeout=90):return c.command(['docker','exec','-i','-e','MYSQL_PWD',cid,'mysql','-u','root','-D',db,'-N','-B'],env=env,data="SET time_zone='+00:00';\n"+q,timeout=timeout)
c.command(['docker','exec','-e','MYSQL_PWD',cid,'mysql','-u','root','-e','CREATE DATABASE '+db],env=env)
source=c.jdbc_target(l.env)[1];dump=c.command(['docker','exec','-e','MYSQL_PWD',cid,'mysqldump','-u','root','--single-transaction','--set-gtid-purged=OFF',source],env=env,timeout=40);sql(dump)
digits=' UNION ALL '.join('SELECT '+str(i)+' d' for i in range(10));sql('CREATE TABLE d_numbers(n INT PRIMARY KEY);INSERT INTO d_numbers SELECT a.d+10*b.d+100*c.d+1000*d.d+10000*e.d+1 FROM ('+digits+') a CROSS JOIN ('+digits+') b CROSS JOIN ('+digits+') c CROSS JOIN ('+digits+') d CROSS JOIN ('+digits+') e;')
seedstart=time.perf_counter()
# Relational fixture uses copied, existing APP/principal/API/release FKs and synthetic unique charges.
for table in ['fg_quota_ledger','fg_api_quota_ledger']:
 source_id=sql('SELECT MIN(id) FROM '+table+';');prefix='11' if table=='fg_quota_ledger' else '12'
 sql("INSERT INTO "+table+"(charge_id,request_fingerprint,application_id,principal_id,credential_generation,api_id,route_id,applied_version,policy_id,period_yyyymm,units,remaining_units_after,created_at) SELECT CONCAT('d00000"+prefix+"-0000-4000-8000-',LPAD(HEX(n),12,'0')),SHA2(CONCAT('d-fixed-seed-23-"+prefix+"-',n),256),l.application_id,l.principal_id,l.credential_generation,l.api_id,l.route_id,l.applied_version,l.policy_id,l.period_yyyymm,1,1000000-n,TIMESTAMP(CONCAT(SUBSTRING(l.period_yyyymm,1,4),'-',SUBSTRING(l.period_yyyymm,5,2),'-01 00:00:00'))+INTERVAL FLOOR(n/100) SECOND FROM "+table+" l JOIN d_numbers ON l.id="+source_id+";")
 # Keep synthetic quota budget in clone consistent with the generated debits; original business DB remains untouched.
 if table=='fg_quota_ledger':sql('UPDATE fg_quota_balance b JOIN '+table+' l ON l.application_id=b.application_id AND l.period_yyyymm=b.period_yyyymm SET b.total_units=1000000,b.remaining_units=1000000-(SELECT SUM(x.units) FROM '+table+' x WHERE x.application_id=b.application_id AND x.period_yyyymm=b.period_yyyymm) WHERE l.id='+source_id+';')
 else:sql('UPDATE fg_api_quota_balance b JOIN '+table+' l ON l.application_id=b.application_id AND l.api_id=b.api_id AND l.period_yyyymm=b.period_yyyymm SET b.total_units=1000000,b.remaining_units=1000000-(SELECT SUM(x.units) FROM '+table+' x WHERE x.application_id=b.application_id AND x.api_id=b.api_id AND x.period_yyyymm=b.period_yyyymm) WHERE l.id='+source_id+';')
# Matching source/consumed/daily fixture, explicit canonical payload and hashes. No worker connects to clone.
for legacy in [True,False]:
 ledger='fg_quota_ledger' if legacy else 'fg_api_quota_ledger';out='fg_outbox_event' if legacy else 'fg_api_usage_outbox';cons='fg_consumed_event' if legacy else 'fg_api_consumed_event';daily='fg_usage_daily' if legacy else 'fg_api_usage_daily'
 canon="CONCAT('{\"version\":"+('1' if legacy else '12')+( '' if legacy else ',\"eventType\":\"USAGE_GRANTED_V12\"')+",\"eventId\":\"',charge_id,'\",\"chargeId\":\"',charge_id,'\",\"applicationId\":',application_id,',\"apiId\":',api_id,',\"periodUtc\":\"',period_yyyymm,'\",\"units\":1,\"eventTime\":\"',DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%sZ'),'\"}')"
 cols='event_id,event_type,payload_json,status,created_at' if legacy else 'event_id,charge_id,event_type,payload_json,status,created_at';vals="charge_id,"+("" if legacy else 'charge_id,')+"'"+('USAGE_GRANTED' if legacy else 'USAGE_GRANTED_V12')+"',"+canon+",'SENT',created_at"
 sql('INSERT INTO '+out+'('+cols+') SELECT '+vals+' FROM '+ledger+" WHERE charge_id LIKE 'd00000%'"+';')
 sql('INSERT INTO '+cons+'(event_id,charge_id,payload_sha256) SELECT charge_id,charge_id,SHA2('+canon+',256) FROM '+ledger+" WHERE charge_id LIKE 'd00000%';")
 sql('INSERT INTO '+daily+'(day_utc,application_id,api_id,authorized_units,grant_count) SELECT DATE(created_at),application_id,api_id,SUM(units),COUNT(*) FROM '+ledger+" WHERE charge_id LIKE 'd00000%' GROUP BY DATE(created_at),application_id,api_id ON DUPLICATE KEY UPDATE authorized_units=authorized_units+VALUES(authorized_units),grant_count=grant_count+VALUES(grant_count);")
sql("INSERT INTO fg_audit_log(actor_id,action,target_type,target_id,summary,created_at) SELECT (SELECT MIN(id) FROM fg_principal),'BENCHMARK_FIXTURE','application',CAST(n AS CHAR),'deterministic synthetic fixture; not real calls',TIMESTAMP('2026-11-01')+INTERVAL FLOOR(n/100) SECOND FROM d_numbers;")
seedseconds=time.perf_counter()-seedstart;queries={};plans={};raw=[]
for table in ['fg_quota_ledger','fg_api_quota_ledger']:
 where=sql('SELECT CONCAT(\'application_id=\',application_id,\' AND period_yyyymm=\\\'\',period_yyyymm,\'\\\'\') FROM '+table+' WHERE charge_id LIKE \'d00000%\' LIMIT 1;')
 queries[table]='SELECT id,created_at FROM '+table+' WHERE '+where+" AND (created_at>'2026-11-01 00:15:00' OR (created_at='2026-11-01 00:15:00' AND id>0)) ORDER BY created_at,id LIMIT 20"
for stage in ['before','after']:
 if stage=='after':
  for table in queries:sql('ALTER TABLE '+table+' ADD INDEX ix_d_ledger_app_period_created(application_id,period_yyyymm,created_at,id);')
 plans[stage]={}
 for table,q in queries.items():
  plan=sql('EXPLAIN ANALYZE '+q+';');samples=[]
  for i in range(10):
   st=time.perf_counter();sql(q+';');samples.append((time.perf_counter()-st)*1000);raw.append([stage,table,i,samples[-1]])
  plans[stage][table]={'explainAnalyze':plan,'clientRoundtripP95Ms':sorted(samples)[9],'note':'includes docker exec/client overhead; server times in EXPLAIN','serverActual':re.findall(r'actual time=([0-9.]+)\.\.([0-9.]+) rows=([0-9.]+) loops=([0-9.]+)',plan)}
# Tied timestamps: two keyset pages advance both time and id, no repeated IDs.
q=queries['fg_quota_ledger'];first=sql(q+';').splitlines();lastid,lasttime=first[-1].split('\t');base=q[:q.index(' AND (created_at')];nextq=base+" AND (created_at>'"+lasttime+"' OR (created_at='"+lasttime+"' AND id>"+lastid+")) ORDER BY created_at,id LIMIT 20;";second=sql(nextq).splitlines();assert set(x.split('\t')[0] for x in first).isdisjoint(x.split('\t')[0] for x in second)
counts={t:int(sql('SELECT COUNT(*) FROM '+t+';')) for t in ['fg_quota_ledger','fg_api_quota_ledger','fg_outbox_event','fg_api_usage_outbox','fg_consumed_event','fg_api_consumed_event','fg_audit_log']};assert counts['fg_quota_ledger']>=100000 and counts['fg_api_quota_ledger']>=100000
sizes=sql("SELECT TABLE_NAME,DATA_LENGTH,INDEX_LENGTH FROM information_schema.tables WHERE table_schema='"+db+"' AND table_name IN ('fg_quota_ledger','fg_api_quota_ledger');")
path=c.ROOT/'docs/acceptance/evidence';path.mkdir(parents=True,exist_ok=True)
with (path/'v12-d-sql-benchmark.csv').open('w') as f:w=csv.writer(f);w.writerow(['stage','table','sample','roundtripMs']);w.writerows(raw)
l.save('v12-d3-sql-100k.json',{'passed':True,'exitCode':0,'benchmarkSchema':db,'businessDatabaseUnmodifiedByFixture':True,'fixture':'deterministic synthetic Ledger/Outbox/Consumed/Daily plus audit in separate full schema clone, FK parents copied; not actual HTTP authorizations','seed':23,'seedSeconds':seedseconds,'counts':counts,'measurements':plans,'stableTiedTimestampPages':True,'tableSizes':sizes,'recommendedMigration':'sql/flowgate-v12-d-indexes.sql; not applied to user database','notExecuted':['100k order/promo tables (ledger/outbox/audit100k exercised)','production index migration','physical index write amplification benchmark']});print('D_SQL_100K_PASS',flush=True)

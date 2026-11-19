#!/usr/bin/env python3
"""C三项复审修复真实隔离验收；继承原C6 Lab，不操作用户容器/卷。"""
import importlib.util,json,os,time,subprocess,uuid,concurrent.futures as futures
from pathlib import Path
from datetime import datetime,timezone
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('cbase',ROOT/'scripts/v11-c-smoke.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
os.environ['FLOWGATE_ASYNC_REPLAY_GRACE_SECONDS']='2' # 仅本独占Lab缩短时间；生产默认300秒。
lab=c.Lab();lab.historical=False;passed=False;details={}
try:
 lab.start();lab.prepare_probe();lab.dev=lab.admin_call(201,'/admin/developers',body={'code':'review','name':'复审测试客户'})
 plan=lab.admin_call(201,'/admin/plans',body={'code':'review-plan','name':'Review','monthlyUnits':1000})
 app,credential=lab.new_app('review-app',plan)
 lab.policy('review-quota','USAGE_QUOTA','API',lab.apis['private']['id'],{'unitsPerCall':1});lab.publish();assert lab.gateway(credential['token'])==200
 lab.start_kafka();lab.probe('send-and-mark');assert lab.sql("SELECT COUNT(*) FROM fg_outbox_event WHERE status='SENT'")=='1' and lab.count('fg_consumed_event')==0
 remaining=lab.sql(f"SELECT remaining_units FROM fg_quota_balance WHERE application_id={app['id']}");payload=lab.sql('SELECT payload_json FROM fg_outbox_event LIMIT 1');event=lab.sql('SELECT event_id FROM fg_outbox_event LIMIT 1');id=int(lab.sql('SELECT MIN(id) FROM fg_outbox_event'))
 lab.mark('F1前置：实际Broker ACK/SENT但未消费，原Worker不再领取SENT')
 # 原容器及记录在独占Lab彻底丢失，MySQL权威事实不改。
 c.b.command(['docker','rm','-f','-v',lab.kafka_name]);lab.start_kafka();assert lab.probe('topic-size')['records']==0
 lab.mark('F1反例：独占Broker容器彻底重建，新topic记录0，MySQL仍1Ledger/1SENT/0Consumed')
 lab.sql(f"UPDATE fg_outbox_event SET next_attempt_at=UTC_TIMESTAMP(3) WHERE id={id}")
 lab.admin_call(409,f'/admin/async-events/{id}/replay',body={});lab.mark('F1最近SENT等待期内拒绝补投')
 time.sleep(2.2)
 with futures.ThreadPoolExecutor(max_workers=2) as pool:codes=list(pool.map(lambda _:lab.request(f'/admin/async-events/{id}/replay',body={},token=lab.admin)[0],range(2)))
 assert sorted(codes)==[200,409] and lab.sql('SELECT event_id FROM fg_outbox_event LIMIT 1')==event and lab.sql('SELECT payload_json FROM fg_outbox_event LIMIT 1')==payload
 assert lab.sql("SELECT COUNT(*) FROM fg_audit_log WHERE action='REPLAY_SENT_EVENT'")=='1';lab.mark('F1双管理员补投仅一200/一409、一次审计，原eventId/payload不变')
 lab.restart(True);lab.converge(1);time.sleep(2.1)
 result=lab.admin_call(409,f'/admin/async-events/{id}/replay',body={});assert result['code']=='EVENT_REPLAY_CONFLICT'
 assert lab.count('fg_quota_ledger')==1 and lab.total()==1 and lab.sql(f"SELECT remaining_units FROM fg_quota_balance WHERE application_id={app['id']}")==remaining
 lab.mark('F1补投后仅1消费/1聚合，Ledger/余额不改，已消费SENT不可再补投')
 lab.sql("INSERT INTO fg_principal(principal_type,username,password_hash,role,scopes_json,enabled) SELECT 'ADMIN',LOWER(r.role),p.password_hash,r.role,'[]',1 FROM fg_principal p JOIN (SELECT 'VIEWER' role UNION ALL SELECT 'OPERATOR') r WHERE p.username='block2-admin'")
 for role in ('operator','viewer'):
  token=lab.expect(200,'/admin/auth/login',body={'username':role,'password':lab.password})['token'];lab.expect(403,f'/admin/async-events/{id}/replay',body={},token=token)
 lab.mark('F1 OPERATOR/VIEWER补投403')
 details['sentReplay']={'adminResults':sorted(codes),'ledger':1,'consumed':1,'dailyGrantCount':1,'balanceUnchanged':True,'payloadUnchanged':True,'brokerRecordsAfterLoss':0}
 # F2：先让消费真正进入MySQL等待，再暂停/恢复DB，全程不重启Control或Listener。
 pid=lab.cp.pid
 lock=subprocess.Popen(['docker','exec','-i','-e','MYSQL_PWD='+lab.password,lab.mysql,'mysql','-uroot','-N','-B','--unbuffered','flowgate'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True);lab.processes.append(lock)
 lock.stdin.write("LOCK TABLES fg_consumed_event WRITE; SELECT 'LOCKED';\n");lock.stdin.flush();assert lock.stdout.readline().strip()=='LOCKED'
 try:
  assert lab.gateway(credential['token'])==200
  c.b.wait(lambda:int(lab.sql("SELECT COUNT(*) FROM information_schema.processlist WHERE ID<>CONNECTION_ID() AND STATE LIKE '%lock%' AND INFO LIKE '%fg_consumed_event%'"))>=1,'消费进入真实DB等待',15)
  c.b.command(['docker','pause',lab.mysql]);time.sleep(5)
 finally:
  subprocess.run(['docker','unpause',lab.mysql],capture_output=True)
  lock.stdin.write('UNLOCK TABLES;\n');lock.stdin.flush();lock.stdin.close();lock.wait(timeout=8)
 lab.converge(2);state=lab.admin_call(200,'/admin/async-events/summary')
 assert lab.cp.pid==pid and lab.cp.poll() is None and state['processCounters']['transientRetry']>=1 and state['processCounters']['poisonPause']==0
 details['transientRecovery']={'sameControlPid':True,'consumerNotRestarted':True,'transientRetries':state['processCounters']['transientRetry'],'ledger':2,'consumed':2,'dailyGrantCount':2}
 lab.mark('F2消费SQL途中实际pause/unpause MySQL，不重启Control或Listener自动同offset追平2笔')
 # F3：在独占库生成真实历史Grant，不把Ledger写入日冒充未知事件日。
 lab.historical=True;lab.restart(False);historical,history_token=lab.new_app('integrity-app',plan);assert lab.gateway(history_token['token'])==200
 lab.historical=False;lab.restart(False)
 day=lab.billing.date().isoformat();query=f'?from={day}&to={day}&applicationId={historical["id"]}'
 normal=lab.admin_call(200,'/admin/reconciliation'+query);assert normal['rows'][0]['status']=='PENDING' and not normal['integrityWarnings']
 source_id=int(lab.sql(f"SELECT id FROM fg_outbox_event WHERE JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.applicationId'))={historical['id']}"));source_event=lab.sql(f'SELECT event_id FROM fg_outbox_event WHERE id={source_id}');source_payload=lab.sql(f'SELECT payload_json FROM fg_outbox_event WHERE id={source_id}')
 balance=lab.sql(f"SELECT remaining_units FROM fg_quota_balance WHERE application_id={historical['id']}")
 lab.sql(f'DELETE FROM fg_outbox_event WHERE id={source_id}')
 # 基线INNER JOIN在此实际会漏成0；修复API必须报告未知日归属的完整性缺口。
 legacy_count=int(lab.sql(f"SELECT COUNT(*) FROM fg_quota_ledger l JOIN fg_outbox_event o ON JSON_UNQUOTE(JSON_EXTRACT(o.payload_json,'$.chargeId'))=l.charge_id WHERE l.application_id={historical['id']}"));assert legacy_count==0
 missing=lab.admin_call(200,'/admin/reconciliation'+query);assert missing['integrityStatus']=='INTEGRITY_GAP' and not missing['rows'] and any(w['code']=='LEDGER_WITHOUT_OUTBOX' for w in missing['integrityWarnings'])
 lab.mark('F3真实Ledger缺Outbox且无Daily：旧inner join为0，新API明确INTEGRITY_GAP且不伪造日期')
 literal='CONVERT(0x'+source_payload.encode().hex()+' USING utf8mb4)'
 lab.sql(f"INSERT INTO fg_outbox_event(id,event_id,event_type,payload_json,status) VALUES({source_id},'{source_event}','USAGE_GRANTED',{literal},'NEW')")
 lab.restart(True);lab.converge(3);matched=lab.admin_call(200,'/admin/reconciliation'+query);assert matched['rows'][0]['status']=='MATCH' and not matched['integrityWarnings']
 lab.mark('F3原事件恢复后真实Kafka消费，对账PENDING到MATCH，真实日仍按Outbox时间')
 lab.restart(False)
 # 即使Outbox后来恢复导致created_at在范围外，缺时间仍须通过关联Ledger候选检出，不伪造日归属。
 lab.sql(f"UPDATE fg_outbox_event SET payload_json=JSON_REMOVE(payload_json,'$.eventTime') WHERE id={source_id}")
 unknown_time=lab.admin_call(200,'/admin/reconciliation'+query);assert unknown_time['integrityStatus']=='INTEGRITY_GAP' and any(w['code']=='EVENT_TIME_MISSING' for w in unknown_time['integrityWarnings'])
 lab.sql(f'UPDATE fg_outbox_event SET payload_json={literal} WHERE id={source_id}')
 lab.mark('F3事件时间缺失且Outbox写入较晚，仍由关联Ledger候选明确诊断，不挪用其日期')
 fake_event=str(uuid.uuid4())
 fake=int(lab.sql(f"INSERT INTO fg_outbox_event(event_id,event_type,payload_json,status) SELECT '{fake_event}',event_type,JSON_SET(payload_json,'$.eventId','{fake_event}'),'SENT' FROM fg_outbox_event WHERE id={source_id}; SELECT LAST_INSERT_ID()"))
 duplicate=lab.admin_call(200,'/admin/reconciliation'+query);assert duplicate['integrityStatus']=='INTEGRITY_GAP' and duplicate['rows'][0]['expectedCount']==1 and duplicate['rows'][0]['status']=='INTEGRITY_GAP' and any(w['code']=='DUPLICATE_OUTBOX' and w['relatedCount']==2 for w in duplicate['integrityWarnings'])
 lab.sql(f'DELETE FROM fg_outbox_event WHERE id={fake}');lab.mark('F3同charge重复Outbox明确异常，1个Grant的expected仍1而非2')
 orphan_event=str(uuid.uuid4());orphan_charge=str(uuid.uuid4())
 orphan=int(lab.sql(f"INSERT INTO fg_outbox_event(event_id,event_type,payload_json,status) SELECT '{orphan_event}',event_type,JSON_SET(payload_json,'$.eventId','{orphan_event}','$.chargeId','{orphan_charge}'),'SENT' FROM fg_outbox_event WHERE id={source_id}; SELECT LAST_INSERT_ID()"))
 result=lab.admin_call(200,'/admin/reconciliation'+query);assert result['integrityStatus']=='INTEGRITY_GAP' and any(w['code']=='OUTBOX_WITHOUT_LEDGER' for w in result['integrityWarnings'])
 lab.sql(f'DELETE FROM fg_outbox_event WHERE id={orphan}')
 result=lab.admin_call(200,'/admin/reconciliation'+query);assert result['rows'][0]['status']=='MATCH' and not result['integrityWarnings']
 assert lab.sql(f"SELECT remaining_units FROM fg_quota_balance WHERE application_id={historical['id']}")==balance and lab.count('fg_quota_ledger')==3
 details['integrity']={'legacyInnerJoinWithMissingOutbox':0,'missingOutbox':'INTEGRITY_GAP','duplicateOutbox':'INTEGRITY_GAP','singleGrantExpectedCount':1,'orphanOutbox':'INTEGRITY_GAP','restored':'MATCH','balanceAndLedgerUnchanged':True,'missingEventTime':'INTEGRITY_GAP'}
 lab.mark('F3反向孤儿也被诊断，移除测试坏行后MATCH，余额Ledger未改')
 lab.restart(True)
 # 永久毒消息不能随数据库自动恢复路径吞掉；先提交分区2的合法checkpoint。
 checkpoint=lab.probe('valid-partition2');c.b.wait(lambda:lab.probe('offset')['offset']>checkpoint['offset'],'合法分区2checkpoint提交',40)
 fault=lab.probe('poison');c.b.wait(lambda:lab.admin_call(200,'/admin/async-events/summary')['processCounters']['poisonPause']>=1,'永久毒分区暂停')
 before=lab.probe('offset')['offset'];assert before==fault['offset']
 duplicates=lab.admin_call(200,'/admin/async-events/summary')['processCounters']['duplicate'];lab.probe('valid-partition1')
 c.b.wait(lambda:lab.admin_call(200,'/admin/async-events/summary')['processCounters']['duplicate']>duplicates,'毒分区之外仍可消费',40)
 lab.probe('conflict');c.b.wait(lambda:lab.admin_call(200,'/admin/async-events/summary')['processCounters']['poisonPause']>=2,'权威内容冲突暂停')
 after=lab.probe('offset')['offset'];assert before==after and lab.total()==3 and lab.count('fg_consumed_event')==3
 details['poisonIsolation']={'partition2OffsetBefore':before,'partition2OffsetAfter':after,'otherPartitionProcessed':True,'poisonAndConflictPaused':True}
 lab.mark('F2永久非法及内容冲突暂停不ACK，其他分区继续，毒offset不前进且不双计')
 passed=True
finally:
 lab.close();target=ROOT/('.local/acceptance/v11-c-review-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps({'passed':passed,'checks':lab.checks,'details':details,'isolated':True,'ownerBrowserAcceptance':'not executed'},ensure_ascii=False,indent=2));target.chmod(0o600);print('C复审本机证据',target,'passed=',passed,flush=True)

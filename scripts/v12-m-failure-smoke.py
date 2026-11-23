#!/usr/bin/env python3
"""M6仅对M5新owner做Rabbit/DB故障、持卷重建、COMMIT/ACK窗口和只读一致性验证。"""
import json,os,subprocess,time,uuid,zipfile,hashlib
from pathlib import Path
from datetime import datetime,timezone,timedelta
import v11_local_core as c
import v11_local_runtime as r
import importlib.util
spec=importlib.util.spec_from_file_location('cutover',c.ROOT/'scripts/v12-m-cutover.py');cut=importlib.util.module_from_spec(spec);spec.loader.exec_module(cut)

def main():
    m=json.loads((c.ROOT/'docs/progress/evidence/v12-m5-kafka-free-e2e.json').read_text());work=Path(m['localRuntime']);cleanup=json.loads((work/'cleanup.json').read_text());home=Path(cleanup['home']);s,e=r.load(home);br=json.loads((work/'browser.json').read_text());java=str(Path(e['JAVA_HOME'])/'bin/java');base=br['control'];checks=[];passed=False;result={}
    sql=lambda q,ro=True:c.mysql_query(e,q,s['project'],s['owner'],read_only=ro)
    def req(path,token=None,body=None,expected=200):
        code,value=c.http_json(base,path,token,body);assert code==expected,(path,code,value.get('code'));return value
    admin=req('/admin/auth/login',body={'username':br['adminLogin'],'password':br['password']})['token']
    libs=work/'m-libs';r.private_directory(libs)
    with zipfile.ZipFile(c.jar_path('flowgate-control')) as jar:
        assert not any('kafka' in name.lower() for name in jar.namelist())
        for name in jar.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):(libs/Path(name).name).write_bytes(jar.read(name))
    cp=os.pathsep.join([str(c.ROOT/'flowgate-control/target/classes'),str(c.ROOT/'flowgate-contract/target/classes'),str(libs/'*')])
    probe_env=dict(os.environ,**e);probe_env.update(CONTROL_PORT='0',FLOWGATE_ASYNC_ENABLED='false',FLOWGATE_V12_ASYNC_ENABLED='false',FLOWGATE_V12_COMMERCE_ASYNC_ENABLED='false')
    def probe(payloads,exchange='flowgate.usage.legacy',route='granted'):
        data='\n'.join([e['FLOWGATE_RABBITMQ_HOST'],e['FLOWGATE_RABBITMQ_PORT'],e['FLOWGATE_RABBITMQ_USERNAME'],e['FLOWGATE_RABBITMQ_PASSWORD'],*payloads])+'\n';c.command([java,'-cp',cp,str(c.ROOT/'scripts/V12RabbitPublishProbe.java'),exchange,route],data=data)
    rabbit=c.docker_target(s['rabbitPort'],'rabbitmq',s['project'],s['owner']);mysql=c.docker_target(s['ports']['mysql'],'mysql',s['project'],s['owner'])
    def queues():
        return json.loads(c.command(['docker','exec',rabbit,'rabbitmqctl','-q','list_queues','-p','/flowgate-v12','name','type','messages','consumers','arguments','--formatter','json']))
    def q(name,field='messages'):return next(x[field] for x in queues() if x['name']==name)
    count=lambda:int(sql('SELECT COUNT(*) FROM fg_consumed_event;'))
    def seed(status='SENT',yesterday=False):
        p=json.loads(sql('SELECT payload_json FROM fg_outbox_event ORDER BY id LIMIT 1;'));p.update(eventId=str(uuid.uuid4()),chargeId=str(uuid.uuid4()))
        if yesterday:p['eventTime']=(datetime.now(timezone.utc)-timedelta(days=1)).replace(hour=12,minute=0,second=0,microsecond=0).isoformat().replace('+00:00','Z');p['periodUtc']=datetime.fromisoformat(p['eventTime'].replace('Z','+00:00')).strftime('%Y%m')
        cols='request_fingerprint,application_id,principal_id,credential_generation,api_id,route_id,applied_version,policy_id,units,remaining_units_after'
        sql("START TRANSACTION;INSERT INTO fg_quota_ledger(charge_id,period_yyyymm,"+cols+") SELECT '"+p['chargeId']+"','"+p['periodUtc']+"',"+cols+" FROM fg_quota_ledger ORDER BY id LIMIT 1;INSERT INTO fg_outbox_event(event_id,event_type,payload_json,status) VALUES('"+p['eventId']+"','USAGE_GRANTED','"+json.dumps(p)+"','"+status+"');COMMIT;",False);return p
    try:
        # 1. 构建、环境、Compose边界，无Kafka容器或变量；新旧三种队列同Broker独立。
        assert all('KAFKA' not in k and k not in ('FLOWGATE_USAGE_TOPIC','FLOWGATE_USAGE_GROUP') for k in e)
        services=c.command(['docker','ps','-a','--filter','label=com.docker.compose.project='+s['project'],'--format','{{.Label "com.docker.compose.service"}}']).splitlines();assert sorted(services)==['mysql','rabbitmq','redis']
        original=sql('SELECT id,charge_id,request_fingerprint,units,remaining_units_after FROM fg_quota_ledger ORDER BY id;');initial_max=int(sql('SELECT MAX(id) FROM fg_quota_ledger;'))
        initial=count();qrows=queues();assert len([x for x in qrows if x['name'].startswith(('flowgate.usage.legacy.','flowgate.usage.v12.','flowgate.commerce.v12.'))])==9 and all(x['type']=='quorum' for x in qrows if x['name'].startswith(('flowgate.usage.legacy.','flowgate.usage.v12.','flowgate.commerce.v12.')))
        checks.append('Kafka-free Jar/env/Compose three services; nine isolated legacy/API/commerce quorum queues')
        # 2. 真Broker停机时Gateway新业务仍写旧Ledger/Outbox；恢复后原消息消费一次。
        dev=req('/admin/developers?afterId=0&limit=100',admin);dev=next(x for x in dev if x['code']=='legacy-dev')
        plan=next(x for x in req('/admin/plans?afterId=0&limit=100',admin) if x['code']=='legacy-free5')
        app=req('/admin/apps',admin,{'developerId':dev['id'],'code':'m-fault-'+uuid.uuid4().hex[:8],'name':'迁移故障验证应用'},201)
        now=datetime.now(timezone.utc);start=now.replace(day=1,hour=0,minute=0,second=0,microsecond=0);end=(start+timedelta(days=32)).replace(day=1)
        req('/admin/subscriptions',admin,{'applicationId':app['id'],'planId':plan['id'],'effectiveAt':start.isoformat().replace('+00:00','Z'),'expiresAt':end.isoformat().replace('+00:00','Z')},201);key=req('/admin/apps/'+str(app['id'])+'/credentials',admin,{'scopes':['demo:read']})['token']
        r.containers(s,e);r.compose(s,e,'stop','rabbitmq')
        code,_=c.http_json(br['gateway'],'/api/v11/legacy/1',key);assert code==200
        assert int(sql('SELECT COUNT(*) FROM fg_quota_ledger WHERE application_id='+str(app['id'])+';'))==1 and count()==initial
        r.compose(s,e,'up','-d','--wait','rabbitmq');r.wait_for(lambda:count()==initial+1,'Broker恢复原授权追平',70)
        checks.append('real Rabbit down Gateway200 persists old Ledger+Outbox; broker recovery consumes original event once')
        # 3. 真DB断开，Retry队列持久副本可见；恢复自动消费原消息，所有原始Ledger不变。
        p=seed();before=count();c.command(['docker','pause',mysql])
        try:
            probe([json.dumps(p)]);r.wait_for(lambda:q('flowgate.usage.legacy.retry')>=1,'DB故障持久TTL retry',35)
        finally:c.command(['docker','unpause',mysql])
        r.wait_for(lambda:count()==before+1,'TTL恢复唯一消费',70);checks.append('real DB pause → confirmed durable 5s retry visible → automatic original-event recovery')
        # 4. 永久错误Poison，100重复不双计。不同协议投到错误链也被拒绝。
        poison_before=q('flowgate.usage.legacy.poison');before=count();probe([json.dumps(p)]*100+['{broken',json.dumps({**p,'units':2}),json.dumps({**p,'version':12})]);r.wait_for(lambda:q('flowgate.usage.legacy.poison')>=poison_before+3,'坏JSON/权威冲突Poison',40);assert count()==before
        checks.append('100 actual legacy deliveries deduplicated; malformed JSON/source conflict/wrong schema isolated in durable Poison')
        # 5. 真mandatory return与容量拒绝产生NACK，确认封装均不能将其视为成功。
        out=c.command([java,'-cp',cp,str(c.ROOT/'scripts/LegacyRabbitFailureProbe.java'),'confirm'],env=probe_env,timeout=40);assert 'REAL_NACK_RETURN_PASS' in out;checks.append('real mandatory return and quorum overflow publisher NACK rejected by RabbitDelivery')
        # 6. 真COMMIT后丢ACK，Broker返回redelivered，重放后只有一条消费事实。
        ack_event=seed();before=count();proc=subprocess.run([java,'-cp',cp,str(c.ROOT/'scripts/LegacyRabbitFailureProbe.java'),'commit'],env=probe_env,input=json.dumps(ack_event)+'\n',text=True,capture_output=True,timeout=90);(work/'legacy-ack-probe.log').write_text(proc.stdout+proc.stderr)
        assert proc.returncode==0 and 'LEGACY_COMMIT_ACK_REDELIVERY_PASS' in proc.stdout,'legacy commit probe failed; private log retained'
        assert count()==before+1;checks.append('actual MySQL COMMIT then real Channel close before ACK; Broker redelivery; one Consumed and one Daily increment')
        # 7. 源冲突停止ADMIN恢复；只修本轮人工造的测试payload，再按原事件恢复。
        recover=seed('FAILED');event_id=int(sql("SELECT id FROM fg_outbox_event WHERE event_id='"+recover['eventId']+"';"))
        sql("UPDATE fg_outbox_event SET payload_json=JSON_SET(payload_json,'$.units',2) WHERE id="+str(event_id)+';',False);req('/admin/async-events/'+str(event_id)+'/retry',admin,{},409)
        sql("UPDATE fg_outbox_event SET payload_json='"+json.dumps(recover)+"' WHERE id="+str(event_id)+';',False);before=count();req('/admin/async-events/'+str(event_id)+'/retry',admin,{});r.wait_for(lambda:count()==before+1,'修复源后原事件恢复')
        checks.append('ADMIN recovery refuses authoritative conflict409; only own injected payload repaired, original event restored')
        # 8. 新封账日事实，走Rabbit统计后用原运营API对账MATCH。
        before=count();day_event=seed('NEW',True);r.wait_for(lambda:count()==before+1,'Rabbit封账日事件')
        day=day_event['eventTime'][:10];closed=req('/admin/reconciliation?from='+day+'&to='+day,admin);assert closed['integrityStatus']=='NO_GAP_IN_SCAN' and len(closed['rows'])==1 and all(x['status']=='MATCH' for x in closed['rows'])
        # 9. 固定hostname+独占命名卷重建；已隔离Poison与测试持久消息保留。
        persist_old=q('flowgate.b0.persist') if any(x['name']=='flowgate.b0.persist' for x in queues()) else 0
        probe(['{"persist":true}'],'','flowgate.b0.persist');r.wait_for(lambda:q('flowgate.b0.persist')>=persist_old+1,'持久消息计数采样稳定',30);persist_before=q('flowgate.b0.persist');poison_count=q('flowgate.usage.legacy.poison')
        volumes_before=r.volume_identities(s);r.containers(s,e);r.compose(s,e,'stop','rabbitmq');r.compose(s,e,'rm','-f','rabbitmq');r.compose(s,e,'up','-d','--wait','rabbitmq');rabbit=c.docker_target(s['rabbitPort'],'rabbitmq',s['project'],s['owner'])
        r.wait_for(lambda:q('flowgate.usage.legacy.aggregate','consumers')>=2,'重建后活跃消费者',70);r.wait_for(lambda:q('flowgate.b0.persist')==persist_before and q('flowgate.usage.legacy.poison')==poison_count,'重建后持久消息计数稳定',40);assert r.volume_identities(s)==volumes_before
        checks.append('real owner Rabbit container removal/recreation with same volume+hostname retains quorum persistent message and Poison; consumer reconnects')
        assert original==sql('SELECT id,charge_id,request_fingerprint,units,remaining_units_after FROM fg_quota_ledger WHERE id<='+str(initial_max)+' ORDER BY id;')
        cut.health(sql,int(sql('SELECT MAX(id) FROM fg_outbox_event;')))
        doctor=r.status(home);assert doctor['state']=='READY',doctor
        summary=req('/admin/async-events/summary',admin);assert summary['source']=='V11_APP' and summary['runtime']['consumer']['state']=='READY'
        result={'passed':True,'checks':checks,'services':services,'sourceCommit':m['sourceCommit'],'legacyCounts':sql('SELECT COUNT(*) FROM fg_quota_ledger;SELECT COUNT(*) FROM fg_outbox_event;SELECT COUNT(*) FROM fg_consumed_event;SELECT SUM(grant_count) FROM fg_usage_daily;').splitlines(),'closedDay':closed,'doctor':doctor,'rabbitQueues':queues(),'persistentQueueCountBefore':persist_before,'persistentQueueCountAfter':q('flowgate.b0.persist'),'poisonCountBeforeRebuild':poison_count,'poisonCountAfterRebuild':q('flowgate.usage.legacy.poison'),'rootEnvUnchanged':c.file_hash(c.ROOT/'.env')==json.loads((c.ROOT/'docs/progress/evidence/v12-m0-baseline.json').read_text())['rootEnvHash'],'oldLedgerUnchanged':True,'localRuntime':str(work),'ownerManual':'NOT_EXECUTED','notExecuted':['complete historical V11 B23/C26 fault matrix','multi-node Rabbit HA','D/C load tests','user persistent-environment migration']};passed=True
    finally:
        if not passed:result={'passed':False,'checks':checks,'localRuntime':str(work),'failure':'private log retained; failure does not count as acceptance'}
        path=c.ROOT/'docs/progress/evidence/v12-m6-failure-matrix.json';path.write_text(json.dumps(result,ensure_ascii=False,indent=2,default=str)+'\n')
    print('M6_PASS' if passed else 'M6_FAILED',flush=True);return 0 if passed else 1
if __name__=='__main__':raise SystemExit(main())

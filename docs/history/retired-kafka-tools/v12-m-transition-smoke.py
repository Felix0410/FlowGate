#!/usr/bin/env python3
"""M1/M2仅接管M0新建隔离实例。演练旧SENT/FAILED由原事件迁移，不修改历史Ledger。"""
import json,os,signal,subprocess,time,uuid,zipfile
from pathlib import Path
import v11_local_core as c
import v11_local_runtime as r
import importlib.util
spec=importlib.util.spec_from_file_location('cutover',c.ROOT/'scripts/v12-m-cutover.py');cut=importlib.util.module_from_spec(spec);spec.loader.exec_module(cut)

def main():
    baseline=json.loads((c.ROOT/'docs/progress/evidence/v12-m0-baseline.json').read_text());work=Path(baseline['bCurrentRun']['localRuntime']);cleanup=json.loads((work/'cleanup.json').read_text());home=Path(cleanup['home']);state,env=r.load(home);browser=json.loads((work/'browser.json').read_text());base=browser['control'];java=str(Path(env['JAVA_HOME'])/'bin/java');check=[]
    sql=lambda q,ro=True:c.mysql_query(env,q,state['project'],state['owner'],read_only=ro)
    def req(path,token=None,body=None,expected=200):
        code,value=c.http_json(base,path,token,body)
        assert code==expected,(path,code,value.get('code'));return value
    def login():return req('/admin/auth/login',body={'username':browser['adminLogin'],'password':browser['password']})['token']
    def stop_controls():
        for row in cut.controls(home):
            assert row['owned'],'未知Control禁止停止'
            identity=r.identity(row['pid']);assert identity and ('-Dflowgate.v11-demo.owner='+state['owner'] in identity[0] or '-Dflowgate.v12-test.owner='+state['owner'] in identity[0]);os.kill(row['pid'],signal.SIGTERM)
        r.wait_for(lambda:not cut.controls(home),'全部自有Control退出',30)
        r.wait_for(lambda:sql("SELECT COUNT(*) FROM fg_outbox_event WHERE status='LEASED' AND lease_until>=UTC_TIMESTAMP(3);")=='0','租约耗尽',40)
    def start_controls(compat):
        for index,port in enumerate([state['ports']['control'],baseline['bCurrentRun']['ports']['control2']]):
            jar=home/'artifacts/flowgate-control.jar';jar.write_bytes(c.jar_path('flowgate-control').read_bytes());cfg=dict(os.environ,**env);cfg.update(CONTROL_PORT=str(port),FLOWGATE_LEGACY_KAFKA_COMPAT_ENABLED=str(compat).lower())
            with (work/('m-control'+str(index)+'.log')).open('a') as log:proc=subprocess.Popen([java,'-Dflowgate.v11-demo.owner='+state['owner'],'-jar',str(jar)],env=cfg,cwd=c.ROOT,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            r.wait_for(lambda:c.http_json('http://127.0.0.1:'+str(port),'/status')[0]==200,'兼容Control启动',45)
            if index==0:state['processes']['flowgate-control']={'pid':proc.pid,'start':r.identity(proc.pid)[1]}
            else:cleanup['secondControlPid']=proc.pid
        state['artifactHashes']['flowgate-control']=c.file_hash(c.jar_path('flowgate-control'));r.save(home/'state.json',state);r.save(work/'cleanup.json',cleanup)
    def change(target,old,epoch,rollback=False):
        token_file=work/'m-admin-token';token_file.write_text(admin);os.chmod(token_file,0o600)
        args=['python3',str(c.ROOT/'scripts/v12-m-cutover.py'),'--home',str(home),'--target',target,'--expect-mode',old,'--expect-epoch',str(epoch),'--admin-token-file',str(token_file)]
        if rollback:args+=['--compat-rollback']
        p=subprocess.run(args,text=True,capture_output=True);assert p.returncode==0,p.stderr;return json.loads(p.stdout)
    def seed(status='NEW'):
        p=json.loads(sql('SELECT payload_json FROM fg_outbox_event ORDER BY id LIMIT 1;'));p.update(eventId=str(uuid.uuid4()),chargeId=str(uuid.uuid4()))
        cols='request_fingerprint,application_id,principal_id,credential_generation,api_id,route_id,applied_version,policy_id,period_yyyymm,units,remaining_units_after'
        sql("START TRANSACTION;INSERT INTO fg_quota_ledger(charge_id,"+cols+") SELECT '"+p['chargeId']+"',"+cols+" FROM fg_quota_ledger ORDER BY id LIMIT 1;INSERT INTO fg_outbox_event(event_id,event_type,payload_json,status,next_attempt_at) VALUES('"+p['eventId']+"','USAGE_GRANTED','"+json.dumps(p)+"','"+status+"',DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 301 SECOND));COMMIT;",False);return p
    def probe(payloads):
        data='\n'.join([env['FLOWGATE_RABBITMQ_HOST'],env['FLOWGATE_RABBITMQ_PORT'],env['FLOWGATE_RABBITMQ_USERNAME'],env['FLOWGATE_RABBITMQ_PASSWORD'],*payloads])+'\n'
        c.command([java,'-cp',os.pathsep.join(str(p) for p in (work/'libs').iterdir()),str(c.ROOT/'scripts/V12RabbitPublishProbe.java'),'flowgate.usage.legacy','granted'],data=data)
    success=False;result={}
    try:
        if not cut.controls(home):start_controls(True)
        admin=login();start_count=int(sql("SELECT COUNT(*) FROM fg_consumed_event;"));stop_controls()
        for _ in range(2):sql((c.ROOT/'sql/flowgate-v12-m.sql').read_text(),False)
        assert sql('SELECT mode,epoch FROM fg_legacy_mq_cutover;')=='KAFKA\t1';start_controls(True);admin=login()
        a=seed();r.wait_for(lambda:sql('SELECT COUNT(*) FROM fg_consumed_event;')==str(start_count+1),'兼容Kafka单路由')
        check.append('M1 compat DB KAFKA only route; two upgraded Controls old event consumed once; idempotent DDL')
        # 活着的Control直接切换必须拒绝，即便有ADMIN凭证。
        try:change('FROZEN','KAFKA',1);raise AssertionError('mixed live Control was accepted')
        except AssertionError as exc:
            if 'mixed live' in str(exc):raise
        assert sql('SELECT mode,epoch FROM fg_legacy_mq_cutover;')=='KAFKA\t1';check.append('live old/new Control inventory prevents cutover')
        stop_controls();admin=admin
        frozen=change('FROZEN','KAFKA',1);back=change('KAFKA','FROZEN',2,True);frozen2=change('FROZEN','KAFKA',3)
        # 全旧实例已停；seed代表旧Broker确认但消费者没处理、及已失败的源事件。
        sent=seed('SENT');failed=seed('FAILED');pending=seed('NEW');before=sql('SELECT charge_id,request_fingerprint,remaining_units_after FROM fg_quota_ledger ORDER BY id;')
        cutover=change('RABBIT','FROZEN',4);start_controls(False);admin=login()
        r.wait_for(lambda:sql('SELECT COUNT(*) FROM fg_consumed_event;')==str(start_count+2),'NEW由Rabbit领取')
        sent_id=int(sql("SELECT id FROM fg_outbox_event WHERE event_id='"+sent['eventId']+"';"));failed_id=int(sql("SELECT id FROM fg_outbox_event WHERE event_id='"+failed['eventId']+"';"))
        req('/admin/async-events/'+str(sent_id)+'/replay',admin,{},409)
        req('/admin/async-events/'+str(failed_id)+'/retry',admin,{})
        r.wait_for(lambda:sql('SELECT COUNT(*) FROM fg_consumed_event;')==str(start_count+3),'FAILED原事件恢复')
        probe([json.dumps(pending)]*100+['{broken',json.dumps({**pending,'units':2})]);time.sleep(4)
        assert sql('SELECT COUNT(*) FROM fg_consumed_event;')==str(start_count+3);assert sql('SELECT SUM(grant_count) FROM fg_usage_daily;')==str(start_count+3)
        check.append('Rabbit original event 100 duplicates no double count; bad JSON and source conflict isolated; NEW/FAILED recovered')
        # 等真实300秒进程冷却，不更改启动时间或配置等待门槛。
        print('M2等待真实300秒节点补投保护',flush=True)
        r.wait_for(lambda:req('/admin/async-events/summary',admin).get('replayGuardReady'),'300秒启动冷却',330)
        req('/admin/async-events/'+str(sent_id)+'/replay',admin,{})
        r.wait_for(lambda:sql('SELECT COUNT(*) FROM fg_consumed_event;')==str(start_count+4),'历史SENT原事件恢复')
        assert before==sql('SELECT charge_id,request_fingerprint,remaining_units_after FROM fg_quota_ledger ORDER BY id;')
        cut.health(sql,int(sql('SELECT MAX(id) FROM fg_outbox_event;')))
        summary=req('/admin/async-events/summary',admin)
        result={'passed':True,'checks':check,'frozen':frozen,'rollback':back,'refreeze':frozen2,'cutover':cutover,'ledgerUnchanged':True,'legacyCounts':sql('SELECT COUNT(*) FROM fg_quota_ledger;SELECT COUNT(*) FROM fg_outbox_event;SELECT COUNT(*) FROM fg_consumed_event;SELECT SUM(grant_count) FROM fg_usage_daily;').splitlines(),'coldReplayRejected':True,'real300SecondWarmup':True,'summary':summary,'localRuntime':str(work),'brokerDrain':'old Kafka controls fully stopped; authoritative MySQL source recovery; no Kafka offset used','ownerManual':'NOT_EXECUTED'}
        success=True
    finally:
        if not success:result={'passed':False,'checks':check,'localRuntime':str(work),'failure':'see private transition log; final Kafka removal forbidden'}
        (c.ROOT/'docs/progress/evidence/v12-m2-cutover.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        if success:(c.ROOT/'docs/progress/evidence/v12-m1-legacy-rabbit.json').write_text(json.dumps({'passed':True,'checks':check,'mode':'RABBIT','queues':['flowgate.usage.legacy.aggregate','flowgate.usage.legacy.retry','flowgate.usage.legacy.poison'],'source':'v12-m2-cutover.json'},ensure_ascii=False,indent=2)+'\n')
    return 0 if success else 1
if __name__=='__main__':raise SystemExit(main())

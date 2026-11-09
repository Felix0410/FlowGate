#!/usr/bin/env python3
"""E交付真实闭环；只对v11-local新建实例写业务，所有卷保留；管理员原始密码只在内存。"""
import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import json
import os
from pathlib import Path
import secrets
import shlex
import signal
import socket
import subprocess
import sys
import time
import zipfile
import v11_local_core as core
import v11_local_runtime as runtime


def wait(fn, label, seconds=75):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if fn(): return
        time.sleep(.5)
    raise AssertionError('等待超时：' + label)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--browser',action='store_true');args=parser.parse_args()
    run_id=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S').lower()+'-'+secrets.token_hex(2)
    work=core.ROOT/'.local'/'v11-e-acceptance'/run_id;runtime.private_directory(work.parent);runtime.private_directory(work)
    instance='e4-'+run_id;home=runtime.instance_home(instance)
    java_home=os.environ.get('JAVA_HOME','');java=str(Path(java_home)/'bin/java')
    password=secrets.token_urlsafe(24)
    libs=work/'hash-libs';runtime.private_directory(libs)
    with zipfile.ZipFile(core.jar_path('flowgate-control')) as jar:
        for name in jar.namelist():
            if name.startswith('BOOT-INF/lib/') and ('spring-security-crypto-' in name or 'commons-logging-' in name): (libs/Path(name).name).write_bytes(jar.read(name))
    hashed=core.command([java,'-cp',os.pathsep.join(str(p) for p in libs.iterdir()),str(core.ROOT/'scripts/PasswordHashTool.java')],data=password+'\n')
    config=work/'config.env'
    text={'JAVA_HOME':java_home,'FG_BOOTSTRAP_ADMIN_USERNAME':'e4-admin','FG_BOOTSTRAP_ADMIN_BCRYPT':hashed,'FLOWGATE_INTERNAL_KEY':secrets.token_hex(32)}
    config.write_text(''.join('export '+key+'='+shlex.quote(value)+'\n' for key,value in text.items()));os.chmod(config,0o600)
    ports={}
    for key in runtime.DEFAULT_PORTS:
        with socket.socket() as s: s.bind(('127.0.0.1',0));ports[key]=s.getsockname()[1]
    checks=[];requests=[];passed=False;paused=None;browser_result=None;main_result=None;historical=None;final_status=None
    original_env_hash=core.file_hash(core.ROOT/'.env')
    def cli(mode):
        cmd=[sys.executable,str(core.ROOT/'scripts/v11-local.py'),mode,'--isolated','--instance',instance,'--config',str(config),'--json']
        if mode=='up':
            for key,value in ports.items(): cmd+=['--'+key+'-port',str(value)]
        result=subprocess.run(cmd,capture_output=True,text=True)
        if result.returncode not in (0,2): raise AssertionError('E启动器工具失败，原始输出已隐藏')
        data=json.loads(result.stdout)
        if mode in ('up','stop') and result.returncode!=0: raise AssertionError('E实例启动/停止未通过：'+data.get('checks',[{}])[0].get('reason','未确认'))
        return result.returncode,data
    try:
        # 1. 使用真正交付入口建立全新Compose/数据库/卷，V0未就绪不能冒充发证资格。
        _,started=cli('up');state,env=runtime.load(home)
        control='http://127.0.0.1:'+str(ports['control']);gateway='http://127.0.0.1:'+str(ports['gateway'])
        def request(expected,path,token=None,body=None,base=control,method=None):
            status,value=core.http_json(base,path,token,body,method);requests.append({'path':path,'status':status})
            if status!=expected: raise AssertionError((path,expected,status,value.get('code') if isinstance(value,dict) else '响应省略'))
            return value
        admin=request(200,'/admin/auth/login',body={'username':'e4-admin','password':password})['token']
        request(401,'/admin/quota/status')
        sql=lambda text:core.mysql_query(env,text,state['project'],state['owner'])
        dev=request(201,'/admin/developers',admin,{'code':'e-customer','name':'E隔离客户'})
        plan=request(201,'/admin/plans',admin,{'code':'e-free5','name':'Free5','monthlyUnits':5})
        now=datetime.now(timezone.utc);today=now.date();past=(now-timedelta(days=1)).replace(hour=12,minute=0,second=0,microsecond=0)
        def create_app(code,clock=now):
            app=request(201,'/admin/apps',admin,{'developerId':dev['id'],'code':code,'name':code})
            start=clock.replace(day=1,hour=0,minute=0,second=0,microsecond=0);end=start.replace(year=start.year+1,month=1) if start.month==12 else start.replace(month=start.month+1)
            payload={'applicationId':app['id'],'planId':plan['id'],'effectiveAt':start.isoformat().replace('+00:00','Z'),'expiresAt':end.isoformat().replace('+00:00','Z')}
            request(201,'/admin/subscriptions',admin,payload)
            return app,payload
        app,subscription=create_app('e-main')
        request(409,f"/admin/apps/{app['id']}/credentials",admin,{'scopes':['demo:read']})
        service=request(201,'/admin/services',admin,{'code':'e-demo','name':'E演示服务','upstreamUri':env['FLOWGATE_ALLOWED_UPSTREAMS'],'enabled':True})
        def api_route(name,public):
            api=request(201,'/admin/apis',admin,{'serviceId':service['id'],'method':'GET','publicPath':'/api/e/'+name+'/{id}','upstreamPath':'/demo/fast/{id}','isPublic':public,'requiredScope':'' if public else 'demo:read','enabled':True})
            request(201,'/admin/routes',admin,{'apiId':api['id'],'routeId':'e-'+name,'priority':0,'enabled':True});return api
        api_route('public',True);api_route('legacy',False)
        old=request(201,'/admin/callers',admin,{'username':'e-legacy','scopes':['demo:read']})['token']
        def publish():
            current=request(200,'/admin/releases/current',admin)
            release=request(200,'/admin/releases/publish',admin,{'expectedVersion':current['version']})
            def applied():
                current=core.http_json(control,'/admin/releases/current',admin)[1];nodes=core.http_json(control,'/admin/nodes',admin)[1]
                return any(n['nodeId']==env['FLOWGATE_NODE_ID'] and n['freshness']=='ONLINE' and n['appliedVersion']==release['version'] and n['checksum']==current['checksum'] and 'USAGE_QUOTA_V1' in n['supportedFeatures'] for n in nodes)
            wait(applied,'节点版本/checksum/可信能力')
            wait(lambda:core.http_json(control,'/admin/quota/status',admin)[1].get('readiness')=='READY','真实发证门禁')
            return release['version']
        v1=publish();request(200,'/api/e/public/1',base=gateway);request(200,'/api/e/legacy/1',old,base=gateway)
        checks.append('V0发证409；真实非计量V1发布和节点能力后才可签发')
        credential=request(200,f"/admin/apps/{app['id']}/credentials",admin,{'scopes':['wrong:scope']})
        metered=api_route('metered',False)
        policy=request(201,'/admin/policies',admin,{'code':'e-quota','type':'USAGE_QUOTA','scope':'API','parametersJson':'{"unitsPerCall":1}','enabled':True})
        request(201,f"/admin/policies/{policy['id']}/bindings",admin,{'targetType':'API','targetId':metered['id']})
        v2=publish();request(401,'/api/e/metered/1',base=gateway);request(403,'/api/e/metered/1',credential['token'],base=gateway);request(401,'/api/e/metered/1',old,base=gateway)
        credential=request(200,f"/admin/apps/{app['id']}/credentials/rotate",admin,{'scopes':['demo:read']})
        statuses=[]
        for index in range(6):
            expected=200 if index<5 else 429;value=request(expected,'/api/e/metered/1',credential['token'],base=gateway);statuses.append(expected)
            if index==5: assert value['code']=='QUOTA_EXHAUSTED'
        counts=sql(f"SELECT total_units,remaining_units FROM fg_quota_balance WHERE application_id={app['id']}; SELECT COUNT(*) FROM fg_quota_ledger WHERE application_id={app['id']}; SELECT COUNT(*) FROM fg_outbox_event WHERE JSON_EXTRACT(payload_json,'$.applicationId')={app['id']};")
        assert counts.splitlines()==['5\t0','5','5'], 'B权威守恒不成立'
        wait(lambda:sql(f"SELECT COALESCE(SUM(grant_count),0) FROM fg_usage_daily WHERE application_id={app['id']};")=='5','真实Kafka日报5')
        main_result={'http':statuses,'total':5,'remaining':0,'ledger':5,'outbox':5,'daily':5,'v1':v1,'v2':v2}
        request(409,'/admin/subscriptions',admin,subscription)
        checks.append('B真实5个200+第6个429，余额5/0、Ledger/Outbox/日报各5；401/Scope403/旧Caller隔离/同月409')
        # 2. 仅暂停新实例Broker；另一个新App产生1个新Grant，不重放主业务以假装修复统计。
        outage,_=create_app('e-outage');outage_token=request(200,f"/admin/apps/{outage['id']}/credentials",admin,{'scopes':['demo:read']})['token']
        paused=core.docker_target(ports['kafka'],'kafka',state['project'],state['owner']);core.command(['docker','pause',paused])
        request(200,'/api/e/metered/1',outage_token,base=gateway)
        assert sql(f"SELECT COUNT(*) FROM fg_quota_ledger WHERE application_id={outage['id']};")=='1'
        assert sql(f"SELECT COUNT(*) FROM fg_consumed_event c JOIN fg_quota_ledger l ON l.charge_id=c.charge_id WHERE l.application_id={outage['id']};")=='0'
        core.command(['docker','unpause',paused]);paused=None
        wait(lambda:sql(f"SELECT COALESCE(SUM(grant_count),0) FROM fg_usage_daily WHERE application_id={outage['id']};")=='1','Broker恢复原事件追平')
        checks.append('自有Broker暂停时B授权不丢、Outbox积压；恢复后原事件追平1，未重做业务扣额')
        payload=sql(f"SELECT payload_json FROM fg_outbox_event WHERE JSON_EXTRACT(payload_json,'$.applicationId')={app['id']} ORDER BY id LIMIT 1;")
        kafka=core.docker_target(ports['kafka'],'kafka',state['project'],state['owner'])
        core.command(['docker','exec','-i',kafka,'/opt/kafka/bin/kafka-console-producer.sh','--bootstrap-server','localhost:9092','--topic','flowgate.usage.v1'],data=payload+'\n',timeout=20)
        wait(lambda:core.http_json(control,'/admin/async-events/summary',admin)[1]['processCounters']['duplicate']>=1,'真实重复事件幂等')
        assert sql(f"SELECT SUM(grant_count) FROM fg_usage_daily WHERE application_id={app['id']};")=='5'
        request(400,f'/admin/reconciliation?from={today}&to={today}',admin)
        checks.append('重复Kafka事件未双计；真实今日未封账查询400，不伪造今日MATCH')
        # 3. 测试专用：仅重启自有Control，以MySQL会话时钟创建新的过往事实，随后恢复再封账查询。
        def restart_clock(frozen):
            current,stored=runtime.load(home);pid=runtime.owned_pid(home,current,'flowgate-control');os.kill(pid,signal.SIGTERM)
            wait(lambda:runtime.owned_pid(home,current,'flowgate-control') is None,'自有Control停止',30)
            if frozen: stored['SPRING_APPLICATION_JSON']=json.dumps({'spring':{'datasource':{'hikari':{'connection-init-sql':"SET time_zone = '+00:00', timestamp = "+str(int(past.timestamp()))}}}})
            else: stored.pop('SPRING_APPLICATION_JSON',None)
            runtime.save(home/'env.json',stored);cli('up')
        restart_clock(True)
        historic,_=create_app('e-history',past)
        historical_token=request(200,f"/admin/apps/{historic['id']}/credentials",admin,{'scopes':['demo:read']})['token']
        request(200,'/api/e/metered/1',historical_token,base=gateway)
        wait(lambda:sql(f"SELECT COALESCE(SUM(grant_count),0) FROM fg_usage_daily WHERE application_id={historic['id']};")=='1','受控过往事件真实聚合')
        assert sql(f"SELECT DATE(created_at) FROM fg_quota_ledger WHERE application_id={historic['id']};")==str(past.date())
        restart_clock(False)
        day=str(past.date());reconciled=request(200,f"/admin/reconciliation?from={day}&to={day}&applicationId={historic['id']}",admin)
        assert len(reconciled['rows'])==1 and reconciled['rows'][0]['status']=='MATCH'
        historical={'day':day,'status':'MATCH','ledger':1,'daily':1,'method':'new Grant under isolated Control MySQL session clock; restored real clock before reconciliation; no Ledger/Outbox history rewritten'}
        request(200,f"/admin/apps/{app['id']}/credentials/revoke",admin,body={},method='POST')
        revoked,_=core.http_json(gateway,'/api/e/metered/1',credential['token']);assert revoked in (401,403)
        request(200,'/api/e/legacy/1',old,base=gateway);request(200,'/api/e/public/1',base=gateway)
        core.mysql_query(env,"INSERT INTO fg_principal(principal_type,username,password_hash,role,scopes_json,enabled) SELECT 'ADMIN',LOWER(r.role),p.password_hash,r.role,'[]',1 FROM fg_principal p JOIN (SELECT 'VIEWER' role UNION ALL SELECT 'OPERATOR') r WHERE p.username='e4-admin';",state['project'],state['owner'],read_only=False)
        viewer=request(200,'/admin/auth/login',body={'username':'viewer','password':password})['token']
        request(403,'/admin/developers',viewer,{'code':'denied','name':'denied'})
        checks.append('只对新Grant注入过往会话时钟，恢复真实时钟后MATCH；撤销旧代次拒绝，VIEWER写403，旧V1仍200')
        _,final_status=cli('doctor');assert final_status['state']=='READY', '隔离最终doctor未全部就绪'
        before=sql('SELECT COUNT(*) FROM fg_quota_ledger; SELECT COUNT(*) FROM fg_outbox_event; SELECT COUNT(*) FROM fg_consumed_event;')
        _,readonly=cli('doctor');assert before==sql('SELECT COUNT(*) FROM fg_quota_ledger; SELECT COUNT(*) FROM fg_outbox_event; SELECT COUNT(*) FROM fg_consumed_event;')
        assert core.file_hash(core.ROOT/'.env')==original_env_hash
        if args.browser:
            script=(core.ROOT/'scripts/v11-e-browser.mjs').read_text()
            browser_config={'url':control+'/console/','username':'e4-admin','password':password,'applicationId':app['id'],'gatewayOrigin':gateway,'runId':run_id,'screenshots':str(work)}
            child=subprocess.run(['ego-browser','nodejs'],input='const config = '+json.dumps(browser_config)+';\n'+script,capture_output=True,text=True,timeout=180)
            if child.returncode: raise AssertionError('E浏览器检查失败；不输出可能包含临时凭证的原始日志')
            browser_result=json.loads((work/'browser-result.json').read_text());assert browser_result['passed']
        passed=True
    finally:
        if paused:
            try: core.command(['docker','unpause',paused])
            except core.LocalFailure: pass
        retained=None
        if (home/'state.json').exists():
            try:
                cleanup_state,cleanup_env=runtime.load(home)
                cleanup_env.pop('SPRING_APPLICATION_JSON',None);cleanup_env.pop('SPRING_DATASOURCE_HIKARI_CONNECTION_INIT_SQL',None)
                runtime.save(home/'env.json',cleanup_env)
                _,retained=cli('stop')
            except Exception: checks.append('自有实例停机未确认，需手工使用stop --isolated检查；未删除卷')
        summary={'passed':passed,'instance':instance,'checks':checks,'main':main_result,'historical':historical,'requests':requests,'doctor':final_status,'browser':browser_result,'volumesRetained':retained.get('volumesRetained') if retained else None,'userEnvUnchanged':core.file_hash(core.ROOT/'.env')==original_env_hash,'ownerAcceptance':'not executed'}
        target=work/'result.json';target.write_text(json.dumps(summary,ensure_ascii=False,indent=2));os.chmod(target,0o600)
        print('E真实闭环证据：'+str(target)+' passed='+str(passed))
    return 0 if passed else 1


if __name__=='__main__':
    try: sys.exit(main())
    except Exception as error:
        print('E验收未通过：'+type(error).__name__+'；请检查脱敏结果，不显示凭证或原始响应')
        sys.exit(1)

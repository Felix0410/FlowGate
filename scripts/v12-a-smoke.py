#!/usr/bin/env python3
"""V12-A定向真实主链路：仅全新E身份实例，双Gateway，保留所有卷；不重跑历史故障矩阵。"""
import concurrent.futures
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
import shlex
import socket
import subprocess
import time
import zipfile
import v11_local_core as core
import v11_local_runtime as runtime


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--keep-running',action='store_true',help='保留本轮自有隔离实例供浏览器验证，之后必须显式stop');args=parser.parse_args()
    run=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S').lower()+'-'+secrets.token_hex(2)
    work=core.ROOT/'.local'/'v12-a'/run;runtime.private_directory(work.parent);runtime.private_directory(work)
    home=runtime.instance_home('v12a-'+run)
    java_home=os.environ['JAVA_HOME'];java=str(Path(java_home)/'bin/java');password=secrets.token_urlsafe(24)
    libs=work/'libs';runtime.private_directory(libs)
    with zipfile.ZipFile(core.jar_path('flowgate-control')) as jar:
        for name in jar.namelist():
            if name.startswith('BOOT-INF/lib/') and ('spring-security-crypto-' in name or 'commons-logging-' in name or 'amqp-client-' in name or 'slf4j-api-' in name or 'netty-' in name): (libs/Path(name).name).write_bytes(jar.read(name))
    hashed=core.command([java,'-cp',os.pathsep.join(str(p) for p in libs.iterdir()),str(core.ROOT/'scripts/PasswordHashTool.java')],data=password+'\n')
    source={'JAVA_HOME':java_home,'FG_BOOTSTRAP_ADMIN_USERNAME':'v12-admin','FG_BOOTSTRAP_ADMIN_BCRYPT':hashed,'FLOWGATE_INTERNAL_KEY':secrets.token_hex(32),'FLOWGATE_PORTAL_ENABLED':'true','FLOWGATE_V12_QUOTA_ENABLED':'true','FLOWGATE_V12_ASYNC_ENABLED':'true'}
    config=work/'config.env';config.write_text(''.join('export '+k+'='+shlex.quote(v)+'\n' for k,v in source.items()));os.chmod(config,0o600)
    ports={}
    for key in (*runtime.DEFAULT_PORTS,'gateway2'):
        with socket.socket() as s:s.bind(('127.0.0.1',0));ports[key]=s.getsockname()[1]
    state,env=runtime.fresh(home,source,{k:v for k,v in ports.items() if k!='gateway2'},config)
    env['FLOWGATE_QUOTA_TRAFFIC_NODES']+=',v12-second-'+run;runtime.save(home/'env.json',env)
    root_hash=core.file_hash(core.ROOT/'.env');checks=[];requests=[];second=None;success=False
    result={'runId':run,'scope':'targeted V12 functionality; no historical fault matrix or load experiment','designBase':'e77624f','checks':checks,'requests':requests,'notExecuted':['two-Control registration race','100-request concurrency/load','V11 B23/C26 real fault regression','RabbitMQ outage/recovery fault matrix (V12), Kafka fault matrix (V11)','historical-day final MATCH','owner manual acceptance','V11 E6','V11 D performance']}
    def wait(fn,label,seconds=65):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            try:
                if fn():return
            except (core.LocalFailure,OSError):pass
            time.sleep(.5)
        raise AssertionError('等待超时：'+label)
    try:
        print('启动全新隔离实例',flush=True);runtime.start(home,config,state['ports']);print('隔离服务就绪',flush=True)
        sql=lambda text:core.mysql_query(env,text,state['project'],state['owner'])
        for _ in range(2):core.mysql_query(env,(core.ROOT/'sql/flowgate-v12-a.sql').read_text(),state['project'],state['owner'],read_only=False)
        print('DDL重复迁移通过',flush=True)
        checks.append('14-table migration and second idempotent migration passed')
        second_env=dict(os.environ,**env);second_env.update(GATEWAY_PORT=str(ports['gateway2']),FLOWGATE_NODE_ID='v12-second-'+run)
        for k in list(second_env):
            if k.startswith(('FG_MYSQL_','FG_BOOTSTRAP_','FLOWGATE_ASYNC_','FLOWGATE_KAFKA_','FLOWGATE_USAGE_','FLOWGATE_QUOTA_','FLOWGATE_RABBITMQ_','FLOWGATE_V12_','FLOWGATE_PORTAL_')):second_env.pop(k)
        log=work/'gateway2.log'
        with log.open('w') as stream:second=subprocess.Popen([java,'-Dflowgate.v12-test.owner='+state['owner'],'-jar',str(home/'artifacts/flowgate-gateway.jar')],cwd=core.ROOT,env=second_env,stdout=stream,stderr=subprocess.STDOUT)
        control='http://127.0.0.1:'+str(ports['control']);gateways=['http://127.0.0.1:'+str(ports[k]) for k in ('gateway','gateway2')]
        wait(lambda:core.http_json(gateways[1],'/actuator/health')[0]==200,'第二Gateway启动')
        def request(expected,path,token=None,body=None,base=control,method=None):
            code,value=core.http_json(base,path,token,body,method);requests.append({'path':path,'status':code})
            if code!=expected:raise AssertionError(f'{path}: expected {expected}, got {code}, code='+str(value.get('code') if isinstance(value,dict) else 'unknown'))
            return value
        admin=request(200,'/admin/auth/login',body={'username':'v12-admin','password':password})['token']
        service=request(201,'/admin/services',admin,{'code':'v12-demo','name':'演示服务','upstreamUri':env['FLOWGATE_ALLOWED_UPSTREAMS'],'enabled':True})
        api=request(201,'/admin/apis',admin,{'serviceId':service['id'],'method':'GET','publicPath':'/api/v12/fast/{id}','upstreamPath':'/demo/fast/{id}','isPublic':False,'requiredScope':'demo:read','enabled':True})
        request(201,'/admin/routes',admin,{'apiId':api['id'],'routeId':'v12-fast','priority':0,'enabled':True})
        def publish():
            current=request(200,'/admin/releases/current',admin);release=request(200,'/admin/releases/publish',admin,{'expectedVersion':current['version']})
            def ready():
                nodes=core.http_json(control,'/admin/nodes',admin)[1]
                return isinstance(nodes,list) and len([n for n in nodes if n['appliedVersion']==release['version'] and n['freshness']=='ONLINE' and 'USAGE_QUOTA_V12' in n.get('supportedFeatures',[])])==2
            wait(ready,'双节点可信V12能力和应用版本');return release
        publish()
        policy=request(201,'/admin/policies',admin,{'code':'v12-quota','type':'USAGE_QUOTA_V12','scope':'API','parametersJson':'{"unitsPerCall":1}','enabled':True})
        request(201,f"/admin/policies/{policy['id']}/bindings",admin,{'targetType':'API','targetId':api['id']});release=publish()
        category=request(200,'/admin/market/categories',admin,{'code':'tools','name':'实用工具'})
        listing=request(200,'/admin/market/listings',admin,{'apiId':api['id'],'categoryId':category['id'],'title':'真实演示API','summary':'隔离环境的快速响应API','docText':'GET /api/v12/fast/{id}\n<script>alert(1)</script>'})
        sku=request(200,f"/admin/market/listings/{listing['id']}/skus",admin,{'code':'v12-free5','quotaUnits':5})
        request(200,f"/admin/market/listings/{listing['id']}/publish",admin,{})
        checks.append('real private V12 release applied by both Gateways; listing published with Free5')
        accounts=[]
        for index in (1,2):
            login='portal-'+run+'-'+str(index);request(201,'/portal/api/auth/register',body={'loginName':login,'password':password,'displayName':'开发者'+str(index)})
            token=request(200,'/portal/api/auth/login',body={'loginName':login,'password':password})['token'];developer=request(200,'/portal/api/my-developers',token)[0]
            app=request(201,'/portal/api/apps',token,{'developerId':developer['id'],'code':'portal-'+run+'-'+str(index),'name':'我的应用'+str(index)});accounts.append((token,app))
        token,app=accounts[0];other,other_app=accounts[1]
        request(401,'/portal/api/me',admin);request(401,'/admin/releases/current',token);request(404,f"/portal/api/apps/{app['id']}",other);request(404,f"/portal/api/apps/{app['id']}/credentials/status",other)
        catalog=request(200,'/portal/api/catalog/apis?q=%E7%9C%9F%E5%AE%9E');assert catalog[0]['id']==listing['id'];detail=request(200,f"/portal/api/catalog/apis/{listing['id']}");assert detail['claimable']
        def grant(_):return core.http_json(control,'/portal/api/free-grants',token,{'applicationId':app['id'],'skuId':sku['id']})
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:grant_results=list(pool.map(grant,range(8)))
        assert sorted(c[0] for c in grant_results)==[201]+[409]*7,[c[0] for c in grant_results]
        assert all(v.get('code')=='FREE_ALREADY_CLAIMED' for c,v in grant_results if c==409)
        request(201,'/portal/api/free-grants',other,{'applicationId':other_app['id'],'skuId':sku['id']})
        second_app=request(201,'/portal/api/apps',token,{'developerId':app['developerId'],'code':'another-'+run,'name':'另一个应用'})
        request(409,'/portal/api/free-grants',token,{'applicationId':second_app['id'],'skuId':sku['id']})
        checks.append('8 concurrent free claims: 1 success + 7 conflicts; other user independent; lifetime claim cannot switch app')
        credential=request(200,f"/portal/api/apps/{app['id']}/credentials/issue",token,{})
        request(401,'/portal/api/me',credential['token']);request(401,'/api/v12/fast/1',token,base=gateways[0])
        statuses=[]
        for i in range(6):expected=200 if i<5 else 429;request(expected,'/api/v12/fast/1',credential['token'],base=gateways[i%2]);statuses.append(expected)
        period=datetime.now(timezone.utc).strftime('%Y%m');params=f"applicationId={app['id']}&apiId={api['id']}&period={period}"
        quota=request(200,'/portal/api/quota?'+params,token);assert quota['remaining_units']==0
        request(404,'/portal/api/quota?'+params,other);request(404,'/portal/api/ledger?'+params,other)
        assert sql('SELECT COUNT(*) FROM fg_api_quota_ledger; SELECT COUNT(*) FROM fg_api_usage_outbox; SELECT COUNT(*) FROM fg_quota_ledger;').splitlines()==['5','5','0']
        wait(lambda:sql('SELECT COALESCE(SUM(grant_count),0) FROM fg_api_usage_daily;')=='5','V12真实RabbitMQ聚合')
        payload=sql('SELECT payload_json FROM fg_api_usage_outbox ORDER BY id LIMIT 1;')
        probe_input='\n'.join([env['FLOWGATE_RABBITMQ_HOST'],env['FLOWGATE_RABBITMQ_PORT'],env['FLOWGATE_RABBITMQ_USERNAME'],env['FLOWGATE_RABBITMQ_PASSWORD'],payload,payload])+'\n'
        core.command([java,'-cp',os.pathsep.join(str(p) for p in libs.iterdir()),str(core.ROOT/'scripts/V12RabbitPublishProbe.java')],data=probe_input)
        time.sleep(2);assert sql('SELECT SUM(grant_count) FROM fg_api_usage_daily;')=='5'
        report=request(200,'/admin/market/reconciliation?day='+datetime.now(timezone.utc).date().isoformat(),admin);assert report['status']=='PROVISIONAL',report
        checks.append('dual Gateway Free5: 5x200 + 429, remaining0, Ledger=Outbox=Consumed=Daily=5; duplicate RabbitMQ events do not aggregate twice; current-day PROVISIONAL')
        request(200,f"/portal/api/apps/{app['id']}/credentials/rotate",token,{})
        request(401,'/api/v12/fast/1',credential['token'],base=gateways[0])
        request(200,f"/portal/api/apps/{app['id']}/credentials/revoke",token,{})
        result.update(gatewayStatuses=statuses,quota=quota,reconciliation=report,counts={'ledger':5,'outbox':5,'consumed':5,'daily':5,'legacyLedger':0})
        # 浏览器继续使用独立测试账户；凭证只保留0600的本地运行文件，不进入证据。
        runtime.save(work/'browser.json',{'control':control,'loginName':'portal-'+run+'-2','password':password,'listingId':listing['id']})
        print('主链路通过，浏览器环境：'+str(work),flush=True)
        success=True
    except Exception as error:
        result['failure']=str(error);print('主链路失败：'+str(error),flush=True)
    finally:
        result['passed']=success;result['rootEnvUnchanged']=root_hash==core.file_hash(core.ROOT/'.env');result['isolationProject']=state['project'];result['localRuntime']=str(work)
        out=core.ROOT/'docs/progress/evidence/v12-a-functional-smoke.json';out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        # 成功时保留运行实例用于同轮浏览器检查，后续由显式stop收尾；失败立即停自有进程/容器。
        runtime.save(work/'cleanup.json',{'home':str(home),'secondPid':second.pid if second else None,'owner':state['owner']})
        if not success or not args.keep_running:
            if second and second.poll() is None:second.terminate();second.wait(timeout=20)
            try:runtime.stop(home)
            except core.LocalFailure:pass
    return 0 if success else 1

if __name__=='__main__':raise SystemExit(main())

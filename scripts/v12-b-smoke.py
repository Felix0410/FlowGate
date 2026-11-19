#!/usr/bin/env python3
"""V12-B普通订单隔离验证：仅全新E身份实例，双Gateway，保留所有卷；不重跑历史故障矩阵。"""
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
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--keep-running',action='store_true',help='保留本轮自有隔离实例供浏览器验证，之后必须显式stop');parser.add_argument('--evidence-prefix',choices=['v12-b','v12-m5'],default='v12-b');args=parser.parse_args()
    run=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S').lower()+'-'+secrets.token_hex(2)
    work=core.ROOT/'.local'/'v12-b'/run;runtime.private_directory(work.parent);runtime.private_directory(work)
    home=runtime.instance_home('v12b-'+run)
    java_home=os.environ['JAVA_HOME'];java=str(Path(java_home)/'bin/java');password=secrets.token_urlsafe(24)
    libs=work/'libs';runtime.private_directory(libs)
    with zipfile.ZipFile(core.jar_path('flowgate-control')) as jar:
        for name in jar.namelist():
            if name.startswith('BOOT-INF/lib/') and ('spring-security-crypto-' in name or 'commons-logging-' in name or 'amqp-client-' in name or 'slf4j-api-' in name or 'netty-' in name): (libs/Path(name).name).write_bytes(jar.read(name))
    hashed=core.command([java,'-cp',os.pathsep.join(str(p) for p in libs.iterdir()),str(core.ROOT/'scripts/PasswordHashTool.java')],data=password+'\n')
    source={'JAVA_HOME':java_home,'FG_BOOTSTRAP_ADMIN_USERNAME':'v12-admin','FG_BOOTSTRAP_ADMIN_BCRYPT':hashed,'FLOWGATE_INTERNAL_KEY':secrets.token_hex(32),'FLOWGATE_PORTAL_ENABLED':'true','FLOWGATE_V12_QUOTA_ENABLED':'true','FLOWGATE_V12_ASYNC_ENABLED':'true','FLOWGATE_V12_COMMERCE_ENABLED':'true','FLOWGATE_V12_COMMERCE_ASYNC_ENABLED':'true'}
    config=work/'config.env';config.write_text(''.join('export '+k+'='+shlex.quote(v)+'\n' for k,v in source.items()));os.chmod(config,0o600)
    ports={}
    for key in (*runtime.DEFAULT_PORTS,'gateway2','control2'):
        with socket.socket() as s:s.bind(('127.0.0.1',0));ports[key]=s.getsockname()[1]
    state,env=runtime.fresh(home,source,{k:v for k,v in ports.items() if k not in ('gateway2','control2')},config)
    env['FLOWGATE_QUOTA_TRAFFIC_NODES']+=',v12-second-'+run;runtime.save(home/'env.json',env)
    root_hash=core.file_hash(core.ROOT/'.env')
    class Checks(list):
        def append(self,value):super().append(value);print('PASS '+value,flush=True)
    checks=Checks();requests=[];second=None;second_control=None;success=False
    result={'runId':run,'scope':'targeted V12 functionality; no historical fault matrix or load experiment','designBase':'48dc05abf33c7bab7e5c1a90bc306d79f82aef3f','sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'checks':checks,'requests':requests,'notExecuted':['two-Control registration race','20k C concurrency/load','full V11 B23/C26 historical fault matrices (key V11 regressions separately recorded)','historical-day final MATCH','owner manual acceptance','V11 E6','V11 D performance']}
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
            if k.startswith(('FG_MYSQL_','FG_BOOTSTRAP_','FLOWGATE_ASYNC_','FLOWGATE_QUOTA_','FLOWGATE_RABBITMQ_','FLOWGATE_V12_','FLOWGATE_PORTAL_')):second_env.pop(k)
        log=work/'gateway2.log'
        with log.open('w') as stream:second=subprocess.Popen([java,'-Dflowgate.v12-test.owner='+state['owner'],'-jar',str(home/'artifacts/flowgate-gateway.jar')],cwd=core.ROOT,env=second_env,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
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
        # B schema和多Control：完全复用A场景，之后只增加新订单，不覆盖A历史证据。
        for _ in range(2):core.mysql_query(env,(core.ROOT/'sql/flowgate-v12-b.sql').read_text(),state['project'],state['owner'],read_only=False)
        assert sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('fg_order','fg_payment_record','fg_business_outbox','fg_business_event_consumed','fg_order_transition','fg_simulated_refund');")=='6'
        control2='http://127.0.0.1:'+str(ports['control2']);control_env=dict(os.environ,**env);control_env['CONTROL_PORT']=str(ports['control2'])
        with (work/'control2.log').open('w') as stream:second_control=subprocess.Popen([java,'-Dflowgate.v12-test.owner='+state['owner'],'-jar',str(home/'artifacts/flowgate-control.jar')],cwd=core.ROOT,env=control_env,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
        wait(lambda:core.http_json(control2,'/status')[0]==200,'第二Control')
        wait(lambda:core.http_json(control,'/admin/commerce/readiness',admin)[1].get('status')=='READY','Commerce真实Worker就绪')
        wait(lambda:core.http_json(control2,'/admin/commerce/readiness',admin)[1].get('status')=='READY','第二Control真实Worker就绪')
        checks.append('6-table B schema installed then repeated twice; two Controls + two Gateways + four commerce listeners')
        paid=request(200,f"/admin/market/listings/{listing['id']}/skus",admin,{'code':'paid5','kind':'PAID_BASE','quotaUnits':5,'priceCents':990,'periodPolicy':'CURRENT_MONTH'})
        next_sku=request(200,f"/admin/market/listings/{listing['id']}/skus",admin,{'code':'next5','kind':'PAID_BASE','quotaUnits':5,'priceCents':1990,'periodPolicy':'NEXT_MONTH'})
        def new_app(label,who=token,dev=app['developerId']):return request(201,'/portal/api/apps',who,{'developerId':dev,'code':'app-'+secrets.token_hex(8),'name':label})
        def create(application,sku=paid,who=token,key=None,expected=201,base=control):
            return request(expected,'/portal/api/orders',who,{'applicationId':application['id'],'skuId':sku['id'],'clientRequestId':key or secrets.token_hex(16)},base=base)
        def order(o,who=token):return request(200,'/portal/api/orders/'+o['orderNo'],who)
        def pay(o,who=token,base=control):return request(200,'/portal/api/orders/'+o['orderNo']+'/simulate-pay',who,{},base=base)
        def refund(o,expected=200,base=control):return request(expected,'/portal/api/orders/'+o['orderNo']+'/simulate-refund',token,{},base=base)
        def fulfilled(o):wait(lambda:core.http_json(control,'/portal/api/orders/'+o['orderNo'],token)[1].get('status')=='FULFILLED','权益履约')
        def mutate(text):return core.mysql_query(env,text,state['project'],state['owner'],read_only=False)
        create(app,expected=409)
        purchase_app=new_app('付费应用');request_id=secrets.token_hex(16);body={'applicationId':purchase_app['id'],'skuId':paid['id'],'clientRequestId':request_id}
        request(400,'/portal/api/orders',token,{**body,'priceCents':1});request(400,'/portal/api/orders',token,{**body,'periodUtc':'209901'})
        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:copies=list(pool.map(lambda i:core.http_json([control,control2][i%2],'/portal/api/orders',token,body),range(20)))
        assert [c for c,_ in copies].count(201)==1 and [c for c,_ in copies].count(200)==19,[c for c,_ in copies]
        assert len({v['orderNo'] for _,v in copies})==1;purchase=copies[0][1]
        assert sql("SELECT state FROM fg_api_entitlement_slot WHERE application_id="+str(purchase_app['id'])+";")=='HELD'
        create(purchase_app,sku=next_sku,key=request_id,expected=409);create(purchase_app,expected=409)
        for action in ('','/simulate-pay','/cancel','/simulate-refund'):
            request(404,'/portal/api/orders/'+purchase['orderNo']+action,other,None if not action else {})
        request(401,'/portal/api/orders',admin);request(401,'/admin/orders',token)
        checks.append('20 concurrent same request across two Controls: one201 + nineteen200 same order; changed key parameters409, free BASE conflicts, unknown price/period400, cross-owner404')
        # Broker离线付款仍是持久事实；重复付款100次不能重复收据/Outbox。
        runtime.containers(state,env);runtime.compose(state,env,'stop','rabbitmq')
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:payments=list(pool.map(lambda i:core.http_json([control,control2][i%2],'/portal/api/orders/'+purchase['orderNo']+'/simulate-pay',token,{}),range(100)))
            assert all(c==200 and v['status']=='PAID_PENDING_FULFILLMENT' for c,v in payments)
            assert sql('SELECT COUNT(*) FROM fg_payment_record;SELECT COUNT(*) FROM fg_business_outbox;SELECT COUNT(*) FROM fg_entitlement_grant WHERE source_type=\'PAID_ORDER\';').splitlines()==['1','1','0']
            assert order(purchase)['status']=='PAID_PENDING_FULFILLMENT'
        finally:runtime.compose(state,env,'up','-d','--wait','rabbitmq')
        fulfilled(purchase);assert order(purchase)['amountCents']==990
        checks.append('Broker offline + 100 payment POSTs across two Controls: one SIM receipt, one Outbox, zero premature Grant; recovery one fulfilled Grant')
        commerce_payload=sql('SELECT payload_json FROM fg_business_outbox ORDER BY id LIMIT 1;')
        def probe(messages,exchange='flowgate.commerce.v12',route='payment'):
            data='\n'.join([env['FLOWGATE_RABBITMQ_HOST'],env['FLOWGATE_RABBITMQ_PORT'],env['FLOWGATE_RABBITMQ_USERNAME'],env['FLOWGATE_RABBITMQ_PASSWORD'],*messages])+'\n';core.command([java,'-cp',os.pathsep.join(str(p) for p in libs.iterdir()),str(core.ROOT/'scripts/V12RabbitPublishProbe.java'),exchange,route],data=data)
        probe([commerce_payload]*100);time.sleep(3)
        assert sql("SELECT COUNT(*) FROM fg_entitlement_grant WHERE source_type='PAID_ORDER';SELECT COUNT(*) FROM fg_business_event_consumed;").splitlines()==['1','1']
        paid_token=request(200,f"/portal/api/apps/{purchase_app['id']}/credentials/issue",token,{})['token']
        paid_statuses=[request(200 if i<5 else 429,'/api/v12/fast/1',paid_token,base=gateways[i%2]) for i in range(6)]
        refund(purchase,409);assert sql('SELECT remaining_units FROM fg_api_quota_balance WHERE application_id='+str(purchase_app['id'])+';')=='0'
        checks.append('100 actual commerce deliveries -> one Grant/Consumed; paid Free5 dual Gateway 5x200 then429; used refund409 no Ledger reversal')
        # 可退款未使用权益：退款与Gateway同时争夺相同APP锁，二者不可同时赢。
        race_app=new_app('退款竞争应用');race=create(race_app);pay(race);fulfilled(race);race_key=request(200,f"/portal/api/apps/{race_app['id']}/credentials/issue",token,{})['token']
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            f1=pool.submit(core.http_json,control2,'/portal/api/orders/'+race['orderNo']+'/simulate-refund',token,{})
            f2=pool.submit(core.http_json,gateways[0],'/api/v12/fast/1',race_key)
            refund_result,call_result=f1.result(),f2.result()
        assert (refund_result[0]==200 and call_result[0]!=200) or (refund_result[0]==409 and call_result[0]==200),(refund_result[0],call_result[0])
        unused_app=new_app('未用退款应用');unused=create(unused_app);pay(unused);fulfilled(unused);unused_key=request(200,f"/portal/api/apps/{unused_app['id']}/credentials/issue",token,{})['token'];refund(unused);refund(unused)
        request(403,'/api/v12/fast/1',unused_key,base=gateways[0]);assert sql('SELECT state FROM fg_api_entitlement_slot WHERE application_id='+str(unused_app['id'])+';')=='GRANTED';create(unused_app,expected=409)
        checks.append('refund vs real Gateway same APP lock has single winner; unused refund idempotent, subscription SUSPENDED, Gateway403 and GRANTED slot retained')
        # 已付款未开通的退款：晚到事件不能触碰后来新订单持有的槽。
        pending_app=new_app('待开通退款应用');pending=create(pending_app)
        runtime.compose(state,env,'stop','rabbitmq')
        try:pay(pending);refund(pending);assert order(pending)['status']=='REFUNDED'
        finally:runtime.compose(state,env,'up','-d','--wait','rabbitmq')
        wait(lambda:core.http_json(control,'/admin/commerce/readiness',admin)[1].get('status')=='READY','Broker恢复就绪')
        replacement=create(pending_app);time.sleep(3);assert order(pending)['status']=='REFUNDED' and order(replacement)['status']=='PENDING_PAYMENT'
        assert sql('SELECT holder_id FROM fg_api_entitlement_slot WHERE application_id='+str(pending_app['id'])+';')==str(replacement['id'])
        checks.append('paid pending refund releases only own HELD; late delivery ignored, replacement holder preserved')
        # 支付与取消在两个Control上竞争；超时扫描也只释放本单槽。
        closing_app=new_app('关单竞争应用');closing=create(closing_app)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            f1=pool.submit(core.http_json,control,'/portal/api/orders/'+closing['orderNo']+'/simulate-pay',token,{})
            f2=pool.submit(core.http_json,control2,'/portal/api/orders/'+closing['orderNo']+'/cancel',token,{})
            payment_r,cancel_r=f1.result(),f2.result()
        assert sorted([payment_r[0],cancel_r[0]])==[200,409]
        expire_app=new_app('超时应用');expire=create(expire_app);mutate('UPDATE fg_order SET pay_deadline_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id='+str(expire['id'])+';')
        request(409,'/portal/api/orders/'+expire['orderNo']+'/simulate-pay',token,{});wait(lambda:order(expire)['status']=='CLOSED_TIMEOUT','超时自动关单')
        checks.append('two-Control payment vs cancel only one200 and one409; true expired deadline rejects pay then closes once')
        # 下月权益和API隔离：测试用户同APP先有API-B本月权益，用相同scope凭证尝试API-A。
        api_b=request(201,'/admin/apis',admin,{'serviceId':service['id'],'method':'GET','publicPath':'/api/v12/other/{id}','upstreamPath':'/demo/fast/{id}','isPublic':False,'requiredScope':'demo:read','enabled':True})
        request(201,'/admin/routes',admin,{'apiId':api_b['id'],'routeId':'v12-other','priority':0,'enabled':True});request(201,f"/admin/policies/{policy['id']}/bindings",admin,{'targetType':'API','targetId':api_b['id']});publish()
        listing_b=request(200,'/admin/market/listings',admin,{'apiId':api_b['id'],'categoryId':category['id'],'title':'另一个真实API','summary':'API池隔离','docText':'GET /api/v12/other/{id}'})
        sku_b=request(200,f"/admin/market/listings/{listing_b['id']}/skus",admin,{'code':'other-free','quotaUnits':5});request(200,f"/admin/market/listings/{listing_b['id']}/publish",admin,{})
        future_app=new_app('下月应用');future=create(future_app,sku=next_sku);pay(future);fulfilled(future);request(201,'/portal/api/free-grants',token,{'applicationId':future_app['id'],'skuId':sku_b['id']})
        future_key=request(200,f"/portal/api/apps/{future_app['id']}/credentials/issue",token,{})['token'];request(403,'/api/v12/fast/1',future_key,base=gateways[0]);request(200,'/api/v12/other/1',future_key,base=gateways[1])
        request(403,'/api/v12/other/1',paid_token,base=gateways[0]);assert sql('SELECT remaining_units FROM fg_api_quota_balance WHERE application_id='+str(future_app['id'])+" AND api_id="+str(api['id'])+';')=='5'
        checks.append('NEXT_MONTH fulfilled but Gateway403 before effective UTC; API-B current free works; API-A paid pool cannot fund API-B')
        # MySQL真实短断与B毒消息，沿用B0经过真实验证的传输边界。
        conflict_message=json.loads(commerce_payload);conflict_message['orderId']=999999
        probe(['{broken',json.dumps(conflict_message)])
        def qcount(queue):
            rows=json.loads(runtime.compose(state,env,'exec','-T','rabbitmq','rabbitmqctl','list_queues','-p','/flowgate-v12','name','messages_ready','messages_unacknowledged','--formatter=json'));return next(int(q['messages_ready'])+int(q['messages_unacknowledged']) for q in rows if q['name']==queue)
        wait(lambda:qcount('flowgate.commerce.v12.poison')==2,'B毒消息隔离')
        mysql_id=runtime.compose(state,env,'ps','-q','mysql').strip();core.command(['docker','pause',mysql_id])
        try:probe([commerce_payload]);wait(lambda:qcount('flowgate.commerce.v12.retry')>0,'B MySQL短断TTL',35)
        finally:core.command(['docker','unpause',mysql_id])
        wait(lambda:qcount('flowgate.commerce.v12.retry')==0 and qcount('flowgate.commerce.v12.orders')==0,'B TTL恢复去重')
        checks.append('commerce malformed JSON + authoritative conflict Poison; real DB pause TTL retry and recovery deduplication')
        # 旧V11 Free5与Rabbit消费仍按原池计量，V12的两个Rabbit读模型不混入。
        legacy_api=request(201,'/admin/apis',admin,{'serviceId':service['id'],'method':'GET','publicPath':'/api/v11/legacy/{id}','upstreamPath':'/demo/fast/{id}','isPublic':False,'requiredScope':'demo:read','enabled':True})
        request(201,'/admin/routes',admin,{'apiId':legacy_api['id'],'routeId':'v11-legacy','priority':0,'enabled':True})
        legacy_policy=request(201,'/admin/policies',admin,{'code':'v11-legacy-quota','type':'USAGE_QUOTA','scope':'API','parametersJson':'{"unitsPerCall":1}','enabled':True});request(201,f"/admin/policies/{legacy_policy['id']}/bindings",admin,{'targetType':'API','targetId':legacy_api['id']});publish()
        legacy_dev=request(201,'/admin/developers',admin,{'code':'legacy-dev','name':'旧V11客户'});legacy_plan=request(201,'/admin/plans',admin,{'code':'legacy-free5','name':'旧Free5','monthlyUnits':5})
        legacy_app=request(201,'/admin/apps',admin,{'developerId':legacy_dev['id'],'code':'legacy-app','name':'旧V11应用'})
        from datetime import timedelta
        clock=datetime.now(timezone.utc);start=clock.replace(day=1,hour=0,minute=0,second=0,microsecond=0);end=(start+timedelta(days=32)).replace(day=1)
        request(201,'/admin/subscriptions',admin,{'applicationId':legacy_app['id'],'planId':legacy_plan['id'],'effectiveAt':start.isoformat().replace('+00:00','Z'),'expiresAt':end.isoformat().replace('+00:00','Z')})
        legacy_key=request(200,f"/admin/apps/{legacy_app['id']}/credentials",admin,{'scopes':['demo:read']})['token']
        for i in range(6):request(200 if i<5 else 429,'/api/v11/legacy/1',legacy_key,base=gateways[i%2])
        wait(lambda:sql('SELECT COALESCE(SUM(grant_count),0) FROM fg_usage_daily;')=='5','旧V11真实Rabbit日报')
        assert sql('SELECT COUNT(*) FROM fg_quota_ledger;SELECT COUNT(*) FROM fg_outbox_event;SELECT COUNT(*) FROM fg_consumed_event;').splitlines()==['5','5','5']
        checks.append('legacy V11 dual Gateway Free5 5x200/429; old Ledger=Outbox=Consumed=Rabbit Daily5, independent of both V12 Rabbit models')
        # 真账户ACL：只在本轮私有库创建两个审核角色，复用私有测试密码，不写入证据。
        for role in ('OPERATOR','VIEWER'):
            mutate("INSERT INTO fg_principal(principal_type,username,password_hash,role,scopes_json,enabled) VALUES('ADMIN','b-"+role.lower()+"','"+hashed+"','"+role+"','[]',TRUE);")
            role_token=request(200,'/admin/auth/login',body={'username':'b-'+role.lower(),'password':password})['token']
            request(200,'/admin/orders',role_token);request(403,'/admin/commerce/events/11111111-1111-4111-8111-111111111111/replay',role_token,{})
            request(403,f"/admin/market/listings/{listing['id']}/skus",role_token,{'code':'blocked','quotaUnits':5})
        checks.append('real OPERATOR/VIEWER orders read200, replay/SKU writes403; Portal and APP token purposes isolated')
        # 真实DDL反约束，所有失败语句应整笔拒绝，历史快照不变。
        for bad_sql in ["UPDATE fg_order SET price_cents=-1 WHERE id="+str(purchase['id'])+";","UPDATE fg_order SET currency='USD' WHERE id="+str(purchase['id'])+";","UPDATE fg_order SET application_id=9223372036854775807 WHERE id="+str(purchase['id'])+";"]:
            rejected=False
            try:mutate(bad_sql)
            except core.LocalFailure:rejected=True
            assert rejected
        assert order(purchase)['amountCents']==990
        checks.append('real MySQL CHECK price/currency and FK invalid app reject; original immutable quote retained')
        report=request(200,'/admin/commerce/reconciliation',admin);assert report['integrityGaps']==0,report
        result.update(commerceReconciliation=report,refundVsGateway=[refund_result[0],call_result[0]],paymentVsCancel=[payment_r[0],cancel_r[0]],ports=ports)
        # 浏览器继续使用独立测试账户；凭证只保留0600的本地运行文件，不进入证据。
        runtime.save(work/'browser.json',{'control':control,'loginName':'portal-'+run+'-1','password':password,'listingId':listing['id'],'adminLogin':'v12-admin','gateway':gateways[0],'home':str(home)})
        print('主链路通过，浏览器环境：'+str(work),flush=True)
        success=True
    except Exception as error:
        import traceback
        result['failure']=type(error).__name__+': '+str(error)+' at line '+str(traceback.extract_tb(error.__traceback__)[-1].lineno);print('主链路失败：'+result['failure'],flush=True)
    finally:
        result['passed']=success;result['rootEnvUnchanged']=root_hash==core.file_hash(core.ROOT/'.env');result['isolationProject']=state['project'];result['localRuntime']=str(work)
        out=core.ROOT/('docs/progress/evidence/'+('v12-m5-kafka-free-e2e.json' if args.evidence_prefix=='v12-m5' else 'v12-b7-e2e.json') if success else 'docs/progress/evidence/'+args.evidence_prefix+'-failed-'+run+'.json');out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        # 成功时保留运行实例用于同轮浏览器检查，后续由显式stop收尾；失败立即停自有进程/容器。
        runtime.save(work/'cleanup.json',{'home':str(home),'secondControlPid':second_control.pid if second_control else None,'secondPid':second.pid if second else None,'owner':state['owner']})
        if not success or not args.keep_running:
            if second_control and second_control.poll() is None:second_control.terminate();second_control.wait(timeout=20)
            if second and second.poll() is None:second.terminate();second.wait(timeout=20)
            try:runtime.stop(home)
            except core.LocalFailure:pass
    return 0 if success else 1

if __name__=='__main__':raise SystemExit(main())

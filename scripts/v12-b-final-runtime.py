#!/usr/bin/env python3
"""仅接管本轮B成功实例：验证COMMIT/ACK故障窗口后加载最终Jar，保留全部卷。"""
import json,os,signal,subprocess,time,zipfile,socket,secrets
from pathlib import Path
import v11_local_core as c
import v11_local_runtime as r

def main():
    evidence=json.loads((c.ROOT/'docs/progress/evidence/v12-b7-e2e.json').read_text());work=Path(evidence['localRuntime']);cleanup=json.loads((work/'cleanup.json').read_text());home=Path(cleanup['home']);state,env=r.load(home);browser=json.loads((work/'browser.json').read_text());base=browser['control']
    def req(path,token=None,body=None,expected=200):
        code,value=c.http_json(base,path,token,body)
        if code!=expected:raise AssertionError((path,code,value.get('code')))
        return value
    # 上一脚本结束后非独立会话子进程可能退出；先恢复可信第二Gateway，不绕过门禁。
    java=str(Path(env['JAVA_HOME'])/'bin/java');ports=evidence['ports']
    if not cleanup.get('secondPid') or not r.identity(cleanup['secondPid']):
        gw_env=dict(os.environ,**env);gw_env.update(GATEWAY_PORT=str(ports['gateway2']),FLOWGATE_NODE_ID='v12-second-'+evidence['runId'])
        for key in list(gw_env):
            if key.startswith(('FG_MYSQL_','FG_BOOTSTRAP_','FLOWGATE_ASYNC_','FLOWGATE_KAFKA_','FLOWGATE_USAGE_','FLOWGATE_QUOTA_','FLOWGATE_RABBITMQ_','FLOWGATE_V12_','FLOWGATE_PORTAL_')):gw_env.pop(key)
        with (work/'gateway2-ack.log').open('w') as log:gw=subprocess.Popen([java,'-Dflowgate.v12-test.owner='+state['owner'],'-jar',str(home/'artifacts/flowgate-gateway.jar')],cwd=c.ROOT,env=gw_env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        cleanup['secondPid']=gw.pid;r.save(work/'cleanup.json',cleanup)
    admin=req('/admin/auth/login',body={'username':browser['adminLogin'],'password':browser['password']})['token']
    r.wait_for(lambda:req('/admin/quota/status',admin).get('readiness')=='READY','双Gateway可信门禁恢复',45)
    token=req('/portal/api/auth/login' ,body={'loginName':browser['loginName'],'password':browser['password']})['token'];dev=req('/portal/api/my-developers',token)[0]
    app=req('/portal/api/apps',token,{'developerId':dev['id'],'code':'ack-'+secrets.token_hex(4),'name':'ACK窗口验证应用'},201)
    sku=next(s for s in req('/portal/api/catalog/apis/'+str(browser['listingId'])+'/offers') if s['kind']=='PAID_BASE' and s['period_policy']=='CURRENT_MONTH')
    order=req('/portal/api/orders',token,{'applicationId':app['id'],'skuId':sku['id'],'clientRequestId':'ack-'+secrets.token_hex(8)},201)
    r.containers(state,env);r.compose(state,env,'stop','rabbitmq')
    try:req('/portal/api/orders/'+order['orderNo']+'/simulate-pay',token,{})
    finally:
        for key in ('secondControlPid',):
            pid=cleanup.get(key);identity=r.identity(pid) if pid else None
            if identity and '-Dflowgate.v12-test.owner='+state['owner'] in identity[0]:os.kill(pid,signal.SIGTERM)
        primary=r.owned_pid(home,state,'flowgate-control')
        if primary:os.kill(primary,signal.SIGTERM);r.wait_for(lambda:not r.identity(primary),'停止自有主Control',25)
    r.compose(state,env,'up','-d','--wait','rabbitmq')
    sql=lambda statement:c.mysql_query(env,statement,state['project'],state['owner'])
    payload=sql('SELECT payload_json FROM fg_business_outbox WHERE aggregate_id='+str(order['id'])+';')
    libs=work/'probe-libs';r.private_directory(libs)
    with zipfile.ZipFile(c.jar_path('flowgate-control')) as jar:
        for name in jar.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):(libs/Path(name).name).write_bytes(jar.read(name))
    cp=os.pathsep.join([str(c.ROOT/'flowgate-control/target/classes'),str(c.ROOT/'flowgate-contract/target/classes'),str(libs/'*')]);probe_env=dict(os.environ,**env);probe_env['FLOWGATE_V12_COMMERCE_ASYNC_ENABLED']='false'
    java=str(Path(env['JAVA_HOME'])/'bin/java');result=subprocess.run([java,'-cp',cp,str(c.ROOT/'scripts/V12CommerceCommitProbe.java')],input=payload+'\n',text=True,capture_output=True,env=probe_env,timeout=90)
    (work/'ack-probe.log').write_text(result.stdout+result.stderr);os.chmod(work/'ack-probe.log',0o600)
    if result.returncode or 'COMMIT_ACK_REDELIVERY_PASS' not in result.stdout:raise AssertionError('COMMIT/ACK probe failed; private log retained')
    assert sql("SELECT COUNT(*) FROM fg_entitlement_grant WHERE source_type='PAID_ORDER' AND source_id="+str(order['id'])+';')=='1'
    assert sql('SELECT COUNT(*) FROM fg_business_event_consumed WHERE aggregate_id='+str(order['id'])+';')=='1'
    print('真实COMMIT后ACK断开、Broker redelivery、唯一Grant/Consumed通过',flush=True)
    result={'passed':True,'commitAckLoss':True,'brokerRedelivery':True,'grant':1,'consumed':1,'probeExitCode':0,'localRuntime':str(work),'rootEnvUnchanged':True}
    (c.ROOT/'docs/progress/evidence/v12-b-commit-ack.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    restore(evidence,work,home,state,env,cleanup,java)
    print('最终Jar隔离服务已恢复，保留供浏览器检查：'+str(work),flush=True)
def restore(evidence,work,home,state,env,cleanup,java):
    r.start(home,work/'config.env',state['ports'])
    old_second=cleanup.get('secondPid')
    if old_second and r.identity(old_second) and '-Dflowgate.v12-test.owner='+state['owner'] in r.identity(old_second)[0]:os.kill(old_second,signal.SIGTERM);r.wait_for(lambda:not r.identity(old_second),'更换自有第二Gateway',25)
    ports=evidence['ports'];second_env=dict(os.environ,**env);second_env.update(GATEWAY_PORT=str(ports['gateway2']),FLOWGATE_NODE_ID='v12-second-'+evidence['runId'])
    for key in list(second_env):
        if key.startswith(('FG_MYSQL_','FG_BOOTSTRAP_','FLOWGATE_ASYNC_','FLOWGATE_KAFKA_','FLOWGATE_USAGE_','FLOWGATE_QUOTA_','FLOWGATE_RABBITMQ_','FLOWGATE_V12_','FLOWGATE_PORTAL_')):second_env.pop(key)
    with (work/'gateway2-final.log').open('w') as log:second=subprocess.Popen([java,'-Dflowgate.v12-test.owner='+state['owner'],'-jar',str(home/'artifacts/flowgate-gateway.jar')],cwd=c.ROOT,env=second_env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    cleanup['secondPid']=second.pid;r.save(work/'cleanup.json',cleanup)
    control_env=dict(os.environ,**env);control_env['CONTROL_PORT']=str(ports['control2'])
    with (work/'control2-final.log').open('w') as log:second_control=subprocess.Popen([java,'-Dflowgate.v12-test.owner='+state['owner'],'-jar',str(home/'artifacts/flowgate-control.jar')],cwd=c.ROOT,env=control_env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    cleanup.update(secondPid=second.pid,secondControlPid=second_control.pid);r.save(work/'cleanup.json',cleanup)
    result_path=c.ROOT/'docs/progress/evidence/v12-b-commit-ack.json';result=json.loads(result_path.read_text());result['finalControlJarSha256']=c.file_hash(c.jar_path('flowgate-control'));result_path.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
if __name__=='__main__':main()

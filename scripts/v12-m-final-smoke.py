#!/usr/bin/env python3
"""最终Jar：双ADMIN CAS、FROZEN真实Gateway持久授权、恢复及A/B主链轻量回归。"""
import json,os,signal,subprocess,concurrent.futures,uuid
from pathlib import Path
from datetime import datetime,timezone,timedelta
import v11_local_core as c
import v11_local_runtime as r
import importlib.util
spec=importlib.util.spec_from_file_location('cut',c.ROOT/'scripts/v12-m-cutover.py');cut=importlib.util.module_from_spec(spec);spec.loader.exec_module(cut)

def main():
    m=json.loads((c.ROOT/'docs/progress/evidence/v12-m5-kafka-free-e2e.json').read_text());work=Path(m['localRuntime']);cl=json.loads((work/'cleanup.json').read_text());home=Path(cl['home']);s,e=r.load(home);br=json.loads((work/'browser.json').read_text());base=br['control'];sql=lambda q:c.mysql_query(e,q,s['project'],s['owner']);checks=[]
    def req(path,token=None,body=None,expected=200,origin=base):
        code,value=c.http_json(origin,path,token,body);assert code==expected,(path,code,value.get('code'));return value
    def login():return req('/admin/auth/login',body={'username':br['adminLogin'],'password':br['password']})['token']
    def stop():
        for row in cut.controls(home):
            assert row['owned'];identity=r.identity(row['pid']);assert '-Dflowgate.v11-demo.owner='+s['owner'] in identity[0] or '-Dflowgate.v12-test.owner='+s['owner'] in identity[0];os.kill(row['pid'],signal.SIGTERM)
        r.wait_for(lambda:not cut.controls(home),'自有双Control退出',30)
        r.wait_for(lambda:sql("SELECT COUNT(*) FROM fg_outbox_event WHERE status='LEASED' AND lease_until>=UTC_TIMESTAMP(3);")=='0','租约耗尽',40)
    def start():
        jar=home/'artifacts/flowgate-control.jar';jar.write_bytes(c.jar_path('flowgate-control').read_bytes())
        for i,port in enumerate([s['ports']['control'],m['ports']['control2']]):
            cfg=dict(os.environ,**e);cfg['CONTROL_PORT']=str(port)
            with (work/('final-control'+str(i)+'.log')).open('a') as log:p=subprocess.Popen([str(Path(e['JAVA_HOME'])/'bin/java'),'-Dflowgate.v11-demo.owner='+s['owner'],'-jar',str(jar)],env=cfg,cwd=c.ROOT,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            r.wait_for(lambda:c.http_json('http://127.0.0.1:'+str(port),'/status')[0]==200,'最终Control就绪',45)
            if i==0:s['processes']['flowgate-control']={'pid':p.pid,'start':r.identity(p.pid)[1]}
            else:cl['secondControlPid']=p.pid
        s['artifactHashes']['flowgate-control']=c.file_hash(c.jar_path('flowgate-control'));r.save(home/'state.json',s);r.save(work/'cleanup.json',cl)
    def change(target,old,epoch):
        return subprocess.run(['python3',str(c.ROOT/'scripts/v12-m-cutover.py'),'--home',str(home),'--target',target,'--expect-mode',old,'--expect-epoch',str(epoch),'--admin-token-file',str(token_file)],capture_output=True,text=True)
    admin=login();token_file=work/'m-final-admin-token';token_file.write_text(admin);os.chmod(token_file,0o600)
    dev=next(x for x in req('/admin/developers?afterId=0&limit=100',admin) if x['code']=='legacy-dev');plan=next(x for x in req('/admin/plans?afterId=0&limit=100',admin) if x['code']=='legacy-free5')
    app=req('/admin/apps',admin,{'developerId':dev['id'],'code':'m-frozen-'+uuid.uuid4().hex[:8],'name':'维护窗口验证应用'},201);now=datetime.now(timezone.utc);start_at=now.replace(day=1,hour=0,minute=0,second=0,microsecond=0);end=(start_at+timedelta(days=32)).replace(day=1)
    req('/admin/subscriptions',admin,{'applicationId':app['id'],'planId':plan['id'],'effectiveAt':start_at.isoformat().replace('+00:00','Z'),'expiresAt':end.isoformat().replace('+00:00','Z')},201);key=req('/admin/apps/'+str(app['id'])+'/credentials',admin,{'scopes':['demo:read']})['token']
    before=int(sql('SELECT COUNT(*) FROM fg_consumed_event;'));mode,epoch=sql('SELECT mode,epoch FROM fg_legacy_mq_cutover;').split('\t');assert mode=='RABBIT';stop()
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:change('FROZEN','RABBIT',int(epoch)),range(2)))
    assert sorted(x.returncode for x in results)==[0,2];assert sql('SELECT mode,epoch FROM fg_legacy_mq_cutover;')=='FROZEN\t'+str(int(epoch)+1);start();admin=login()
    req('/api/v11/legacy/1',key,expected=200,origin=br['gateway']);assert int(sql('SELECT COUNT(*) FROM fg_consumed_event;'))==before
    assert sql('SELECT status FROM fg_outbox_event WHERE JSON_EXTRACT(payload_json,\'$.applicationId\')='+str(app['id'])+';')=='NEW'
    summary=req('/admin/async-events/summary',admin);assert summary['runtime']['consumer']['state']=='BLOCKED';checks.append('two concurrent ADMIN offline CAS: one success + one conflict; FROZEN final Controls keep Gateway200 and original Ledger/NEW Outbox with async PENDING')
    token_file.write_text(admin);stop();res=change('RABBIT','FROZEN',int(epoch)+1);assert res.returncode==0;start();admin=login();r.wait_for(lambda:int(sql('SELECT COUNT(*) FROM fg_consumed_event;'))==before+1,'RABBIT恢复维护期原事件',45)
    cut.health(sql,int(sql('SELECT MAX(id) FROM fg_outbox_event;')));checks.append('offline audited RABBIT resume; FROZEN original event consumed once; Ledger/Outbox/Consumed/Daily reconcile')
    # 最终源码下重做A/B关键动作，不重复100次历史矩阵。
    portal=req('/portal/api/auth/login',body={'loginName':br['loginName'],'password':br['password']})['token'];other=req('/portal/api/auth/login',body={'loginName':br['loginName'][:-1]+'2','password':br['password']})['token'];developer=req('/portal/api/my-developers',portal)[0]
    def new_app(name):return req('/portal/api/apps',portal,{'developerId':developer['id'],'code':'final-'+uuid.uuid4().hex[:8],'name':name},201)
    offers=req('/portal/api/catalog/apis/'+str(br['listingId'])+'/offers');paid=next(x for x in offers if x['kind']=='PAID_BASE' and x['period_policy']=='CURRENT_MONTH')
    def buy(application):
        order=req('/portal/api/orders',portal,{'applicationId':application['id'],'skuId':paid['id'],'clientRequestId':uuid.uuid4().hex},201);req('/portal/api/orders/'+order['orderNo']+'/simulate-pay',portal,{});r.wait_for(lambda:req('/portal/api/orders/'+order['orderNo'],portal)['status']=='FULFILLED','最终Commerce唯一Grant',45);return order
    used_app=new_app('最终使用验证应用');used=buy(used_app);app_key=req('/portal/api/apps/'+str(used_app['id'])+'/credentials/issue',portal,{})['token'];req('/api/v12/fast/1',app_key,expected=200,origin=br['gateway']);req('/portal/api/orders/'+used['orderNo']+'/simulate-refund',portal,{},409)
    req('/portal/api/orders/'+used['orderNo'],other,expected=404);unused=buy(new_app('最终未用退款应用'));req('/portal/api/orders/'+unused['orderNo']+'/simulate-refund',portal,{})
    other_app=req('/portal/api/apps',other)[0];other_key=req('/portal/api/apps/'+str(other_app['id'])+'/credentials/issue',other,{})['token'];req('/api/v12/fast/1',other_key,expected=200,origin=br['gateway']);r.wait_for(lambda:int(sql('SELECT SUM(grant_count) FROM fg_api_usage_daily;'))==int(sql('SELECT COUNT(*) FROM fg_api_quota_ledger;')),'最终API用量追平',45)
    for role in ('operator','viewer'):
        role_token=req('/admin/auth/login',body={'username':'b-'+role,'password':br['password']})['token'];req('/admin/async-events/summary',role_token);req('/admin/async-events/1/retry',role_token,{},403)
    checks.append('final Jar real Commerce order/pay/Grant/Gateway200/used-refund409/unused-refund200; independent API usage consumes; cross-user404; OPERATOR/VIEWER legacy read200/write403')
    doctor=r.status(home);assert doctor['state']=='READY';counts=sql('SELECT COUNT(*) FROM fg_quota_ledger;SELECT COUNT(*) FROM fg_outbox_event;SELECT COUNT(*) FROM fg_consumed_event;SELECT SUM(grant_count) FROM fg_usage_daily;').splitlines();assert len(set(counts))==1
    result={'passed':True,'checks':checks,'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'controlJarSha256':c.file_hash(c.jar_path('flowgate-control')),'legacyCounts':counts,'apiCounts':sql('SELECT COUNT(*) FROM fg_api_quota_ledger;SELECT COUNT(*) FROM fg_api_usage_outbox;SELECT COUNT(*) FROM fg_api_consumed_event;SELECT SUM(grant_count) FROM fg_api_usage_daily;').splitlines(),'commerceIntegrity':req('/admin/commerce/reconciliation',admin),'concurrentCasExitCodes':[x.returncode for x in results],'finalFence':sql('SELECT mode,epoch FROM fg_legacy_mq_cutover;'),'doctor':doctor,'localRuntime':str(work),'rootEnvUnchanged':c.file_hash(c.ROOT/'.env')==json.loads((c.ROOT/'docs/progress/evidence/v12-m0-baseline.json').read_text())['rootEnvHash']}
    (c.ROOT/'docs/progress/evidence/v12-m-final-runtime.json').write_text(json.dumps(result,ensure_ascii=False,indent=2,default=str)+'\n');print('FINAL_RUNTIME_PASS',flush=True)
if __name__=='__main__':main()

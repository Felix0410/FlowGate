#!/usr/bin/env python3
"""V11-A独占MySQL/Redis和随机端口实测；不迁移用户数据库、不启用应用凭证。"""
import concurrent.futures
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import threading

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('v11_lab',ROOT/'scripts/block2-smoke.py')
b2=importlib.util.module_from_spec(spec);spec.loader.exec_module(b2)

class Lab(b2.Lab):
    def __init__(self):super().__init__();self.evidence=[]
    def request(self,path,**kwargs):
        status,data,headers=super().request(path,**kwargs)
        row={'path':path,'status':status}
        if status>=400 and isinstance(data,dict):row['code']=data.get('code')
        self.evidence.append(row);return status,data,headers
    def held(self,app):
        # 1. 独立数据库连接先持应用行锁，再启动HTTP竞争；不用sleep猜测交错。
        proc=subprocess.Popen(['docker','exec','-i','-e','MYSQL_PWD='+self.password,self.mysql,'mysql','-uroot','-N','-B','--unbuffered','flowgate'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        proc.stdin.write(f"START TRANSACTION;SELECT id FROM fg_application WHERE id={app} FOR UPDATE;SELECT 'LOCKED';\n");proc.stdin.flush()
        assert proc.stdout.readline().strip()==str(app)
        assert proc.stdout.readline().strip()=='LOCKED'
        return proc
    def release(self,proc):
        proc.stdin.write('COMMIT;\n');proc.stdin.flush();proc.stdin.close();proc.wait(timeout=8)
        assert proc.returncode==0
    def waiters(self):
        return int(self.sql("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA='flowgate' AND l.OBJECT_NAME='fg_application'"))

def run(lab,checks):
    lab.start()
    def oldfacts():return {table:lab.sql('SELECT COUNT(*) FROM '+table) for table in ('fg_principal','fg_route','fg_config_release')}|{'snapshotsSha256':hashlib.sha256(lab.sql('SELECT snapshot_json FROM fg_config_release ORDER BY version').encode()).hexdigest()}
    before=oldfacts();ddl=(ROOT/'sql/flowgate-v11.sql').read_text();lab.sql(ddl)
    columns=lab.sql("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='flowgate' ORDER BY TABLE_NAME,ORDINAL_POSITION")
    indexes=lab.sql("SELECT TABLE_NAME,INDEX_NAME,COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='flowgate' ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX")
    lab.sql(ddl);assert oldfacts()==before
    assert columns==lab.sql("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='flowgate' ORDER BY TABLE_NAME,ORDINAL_POSITION")
    assert indexes==lab.sql("SELECT TABLE_NAME,INDEX_NAME,COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='flowgate' ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX")
    checks['migration']={'passed':True,'oldFacts':before,'columnsSha256':hashlib.sha256(columns.encode()).hexdigest(),'indexesSha256':hashlib.sha256(indexes.encode()).hexdigest()}
    print('PASS V1→V11重复迁移两次、列/索引及旧主体/路由/快照不变',flush=True)
    lab.sql("INSERT INTO fg_principal(principal_type,username,password_hash,role,scopes_json,enabled) SELECT 'ADMIN',LOWER(r.role),p.password_hash,r.role,'[]',1 FROM fg_principal p JOIN (SELECT 'VIEWER' role UNION ALL SELECT 'OPERATOR') r WHERE p.username='block2-admin'")
    tokens={r:lab.expect(200,'/admin/auth/login',body={'username':r,'password':lab.password})['token'] for r in ('viewer','operator')}
    dev=[];apps=[];plans=[]
    for i in range(2):
        dev.append(lab.admin_call(201,'/admin/developers',body={'code':'customer-'+str(i),'name':'客户'+str(i),'status':'ACTIVE'}))
        apps.append(lab.admin_call(201,'/admin/apps',body={'developerId':dev[-1]['id'],'code':'app-'+str(i),'name':'应用'+str(i),'status':'ACTIVE'}))
        plans.append(lab.admin_call(201,'/admin/plans',body={'code':['free','pro'][i],'name':['Free','Pro'][i],'monthlyUnits':[5,20][i],'status':'ACTIVE'}))
    for kind,row in [('developers',dev[0]),('apps',apps[0]),('plans',plans[0])]:
        assert lab.admin_call(200,f"/admin/{kind}/{row['id']}")['id']==row['id']
        assert lab.admin_call(200,f'/admin/{kind}?afterId=0&limit=1')[0]['id']==row['id']
        assert len(lab.admin_call(200,f"/admin/{kind}?afterId={row['id']}&limit=1"))==1
        assert lab.admin_call(200,f"/admin/{kind}/{row['id']}",method='PATCH',body={'name':'改名-'+kind})['name']=='改名-'+kind
        lab.admin_call(404,f'/admin/{kind}/999999')
        lab.admin_call(400,f'/admin/{kind}?limit=0');lab.admin_call(400,f'/admin/{kind}?afterId=-1')
    for kind,body in [('developers',{'code':'customer-0','name':'重复'}),('apps',{'developerId':dev[0]['id'],'code':'app-0','name':'重复'}),('plans',{'code':'free','name':'重复','monthlyUnits':5})]:
        assert lab.admin_call(409,'/admin/'+kind,body=body)['code']=='DUPLICATE_CODE'
    for units in (0,1000001,1.5):lab.admin_call(400,'/admin/plans',body={'code':'bad','name':'非法','monthlyUnits':units})
    lab.admin_call(400,'/admin/developers',body={'code':'bad/../code','name':'非法'})
    lab.admin_call(400,f"/admin/plans/{plans[0]['id']}",method='PATCH',body={'monthlyUnits':5})
    lab.admin_call(400,f"/admin/apps/{apps[0]['id']}",method='PATCH',body={'developerId':dev[1]['id']})
    lab.admin_call(404,'/admin/apps',body={'developerId':999999,'code':'missing','name':'缺失'})
    assert len(lab.admin_call(200,f"/admin/apps?developerId={dev[0]['id']}&limit=100"))==1
    caller=lab.admin_call(201,'/admin/callers',body={'username':'old-caller','scopes':['demo:read']})
    # 2. 新域完整角色矩阵；旧V1 OPERATOR写与旧Caller仍保持。
    for kind in ('developers','apps','plans','subscriptions'):
        lab.expect(401,'/admin/'+kind)
        lab.expect(401,'/admin/'+kind,token=caller['token'])
        for token in tokens.values():
            lab.expect(200,'/admin/'+kind,token=token)
            for method in ('POST','PATCH','PUT','DELETE'):
                path='/admin/'+kind if method=='POST' else '/admin/'+kind+'/1'
                lab.expect(403,path,token=token,method=method,body={})
    for path in ('/admin/apps;x=1','/admin/%61pps','/admin/apps/1;v=1/credentials'):
        lab.expect(403,path,token=tokens['operator'],body={})
    lab.expect(201,'/admin/services',token=tokens['operator'],body={'code':'v1-operator','name':'旧权限','upstreamUri':f'http://127.0.0.1:{lab.demo_port}','enabled':True})
    lab.expect(403,'/admin/services',token=tokens['viewer'],body={})
    namespace_before=lab.redis('KEYS','fg:auth:caller:*');principal_before=lab.sql('SELECT COUNT(*) FROM fg_principal');audit_before=lab.sql('SELECT COUNT(*) FROM fg_audit_log')
    for app in apps:
        path=f"/admin/apps/{app['id']}/credentials";lab.expect(401,path,method='POST',body={})
        for token in tokens.values():lab.expect(403,path,method='POST',token=token,body={})
        result=lab.admin_call(409,path,body={});assert result['code']=='FEATURE_NOT_READY'
        lab.admin_call(405,path,method='GET')
    assert lab.redis('KEYS','fg:auth:caller:*')==namespace_before and lab.redis('KEYS','fg:auth:app:*')==''
    assert lab.redis('KEYS','fg:auth:app-index:*')=='' and lab.sql('SELECT COUNT(*) FROM fg_app_principal')=='0'
    assert lab.sql('SELECT COUNT(*) FROM fg_principal')==principal_before and lab.sql('SELECT COUNT(*) FROM fg_audit_log')==audit_before
    checks['rbacAndNoCredentialSideEffects']=True
    print('PASS 新域ADMIN写/OPERATOR和VIEWER只读/Caller401/旧OPERATOR兼容/凭证409且DB和Redis无副作用',flush=True)
    dbnow=datetime.fromisoformat(lab.sql('SELECT DATE_FORMAT(UTC_TIMESTAMP(3),\'%Y-%m-%dT%H:%i:%s.%f\')')).replace(tzinfo=timezone.utc)
    start=dbnow.replace(day=1,hour=0,minute=0,second=0,microsecond=0)
    def nextmonth(dt):return dt.replace(year=dt.year+(dt.month==12),month=1 if dt.month==12 else dt.month+1,day=1)
    end=nextmonth(start);later=nextmonth(end)
    iso=lambda d:d.isoformat(timespec='milliseconds').replace('+00:00','Z')
    def sub(app,plan=0,s=start,e=end):return {'applicationId':app,'planId':plans[plan]['id'],'effectiveAt':iso(s),'expiresAt':iso(e)}
    # 3. 外部控制连接持锁，确认两HTTP事务都进入MySQL锁等待，释放后恰好一201一409。
    held=lab.held(apps[0]['id']);barrier=threading.Barrier(3)
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            def create():barrier.wait();return lab.request('/admin/subscriptions',body=sub(apps[0]['id']),token=lab.admin)
            futures=[pool.submit(create) for _ in range(2)];barrier.wait()
            b2.wait(lambda:lab.waiters()>=2,'两HTTP订阅事务真实等待应用行锁',seconds=5)
            lab.release(held);held=None
            results=[future.result() for future in futures]
    finally:
        if held:lab.release(held)
    assert sorted(r[0] for r in results)==[201,409]
    assert next(r[1] for r in results if r[0]==409)['code']=='SUBSCRIPTION_CONFLICT'
    current=next(r[1] for r in results if r[0]==201);assert datetime.fromisoformat(current['effectiveAt'].replace('Z','+00:00'))==start and current['planVersionSnapshot']==1 and current['periodState']=='CURRENT'
    queued=lab.admin_call(201,'/admin/subscriptions',body=sub(apps[0]['id'],1,end,later));assert queued['periodState']=='SCHEDULED'
    lab.admin_call(409,'/admin/subscriptions',body=sub(apps[0]['id'],1,end,later))
    lab.admin_call(400,'/admin/subscriptions',body=sub(apps[1]['id'],0,end.replace(hour=1),later))
    lab.admin_call(400,'/admin/subscriptions',body=sub(apps[1]['id'],0,start.replace(year=start.year-1),end))
    invalid=sub(apps[1]['id']);invalid['effectiveAt']='2026-11-01T08:00:00+08:00';lab.admin_call(400,'/admin/subscriptions',body=invalid)
    lab.admin_call(400,'/admin/subscriptions',body=sub(apps[1]['id'],0,end,start))
    lab.admin_call(404,'/admin/subscriptions',body={**sub(apps[1]['id']),'planId':999999})
    for kind,row,code in [('apps',apps[1],403),('developers',dev[1],403),('plans',plans[0],400)]:
        lab.admin_call(200,f"/admin/{kind}/{row['id']}",method='PATCH',body={'status':'SUSPENDED'})
        lab.admin_call(code,'/admin/subscriptions',body=sub(apps[1]['id']))
        lab.admin_call(200,f"/admin/{kind}/{row['id']}",method='PATCH',body={'status':'ACTIVE'})
    lab.admin_call(201,'/admin/subscriptions',body=sub(apps[1]['id'],1))
    lab.admin_call(405,f"/admin/subscriptions/{current['id']}",method='PATCH',body={'effectiveAt':iso(end)})
    assert datetime.fromisoformat(lab.admin_call(200,f"/admin/subscriptions/{current['id']}")['effectiveAt'].replace('Z','+00:00'))==start
    assert len(lab.admin_call(200,f"/admin/subscriptions?applicationId={apps[0]['id']}&limit=100"))==2
    # 不同应用同一客户和套餐可并行：一应用人为持锁，另一应用必须在释放前完成。
    extra=[lab.admin_call(201,'/admin/apps',body={'developerId':dev[0]['id'],'code':'parallel-'+str(n),'name':'并行'+str(n)}) for n in range(2)]
    held=lab.held(extra[0]['id'])
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            waiting=pool.submit(lab.request,'/admin/subscriptions',body=sub(extra[0]['id']),token=lab.admin)
            b2.wait(lambda:lab.waiters()>=1,'单应用真实等待',seconds=4)
            other=pool.submit(lab.request,'/admin/subscriptions',body=sub(extra[1]['id']),token=lab.admin)
            assert other.result(timeout=4)[0]==201 and not waiting.done()
            lab.release(held);held=None;assert waiting.result()[0]==201
    finally:
        if held:lab.release(held)
    assert lab.sql("SELECT COUNT(*) FROM fg_audit_log WHERE action='CREATE_SUBSCRIPTION'")=='5'
    checks['subscriptionRace']={'sameApplication':[201,409],'bothWaitedOnMysqlLock':True,'differentApplicationsProgressIndependently':True,'currentAndQueuedAdjacent':True,'planVersionSnapshot':1}
    print('PASS 真实MySQL同app行锁一201一409/异app独立前进/UTC相邻期间/父级停用与不可变套餐/失败无孤立审计',flush=True)
    # 4. 旧V1实际Caller授权/撤销和公开路由、产品入口均回归；不触碰计量/发布协议。
    gw=lab.gateways[0];lab.expect(200,'/api/demo/fast/123',base=gw)
    lab.expect(200,'/api/private/fast/123',base=gw,token=caller['token'])
    lab.admin_call(204,f"/admin/callers/{caller['id']}/revoke",body={});lab.expect(401,'/api/private/fast/123',base=gw,token=caller['token'])
    for path in ('/console/','/console/openapi-business.js','/console/guide.html','/test-console/'):
        lab.expect(200,path)
    assert lab.sql('SELECT snapshot_json FROM fg_config_release ORDER BY version') and oldfacts()['snapshotsSha256']==before['snapshotsSha256']
    checks['v1CallerAndSnapshotCompatibility']=True

def main():
    lab=Lab();checks={};passed=False
    target=ROOT/('.local/acceptance/v11-a-smoke-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%f')+'.json')
    try:run(lab,checks);passed=True
    finally:
        lab.close();target.parent.mkdir(parents=True,exist_ok=True)
        with target.open('x') as stream:
            os.chmod(target,0o600);json.dump({'passed':passed,'recordedAt':datetime.now(timezone.utc).isoformat(),'gitSha':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'checks':checks,'requests':lab.evidence},stream,ensure_ascii=False,indent=2)
        print('本机脱敏证据：',target,'passed=',passed,flush=True)
if __name__=='__main__':main()

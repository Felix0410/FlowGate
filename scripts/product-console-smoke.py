#!/usr/bin/env python3
"""产品页定向HTTP：复用独占Lab；用真实产品JS模块操作真实Control，不控制浏览器。"""
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('product_lab', ROOT / 'scripts/block2-smoke.py')
b2 = importlib.util.module_from_spec(spec); spec.loader.exec_module(b2)

class Lab(b2.Lab):
    def __init__(self): super().__init__(); self.evidence = []
    def request(self, path, **kwargs):
        result = super().request(path, **kwargs)
        self.evidence.append({'path': path, 'status': result[0]})
        return result

NODE = r'''
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {createApi} from './flowgate-control/src/main/resources/static/console/api.js';
import {saveCatalog} from './flowgate-control/src/main/resources/static/console/catalog.js';
import {savePolicy} from './flowgate-control/src/main/resources/static/console/governance.js';
import {publishConfiguration,createCaller,revokeCaller} from './flowgate-control/src/main/resources/static/console/operations.js';
const config = JSON.parse(fs.readFileSync(0,'utf8')), records=[];
const transport=async(path,options)=>{const response=await fetch(config.control+path,options);records.push({path,status:response.status});return response;};
const api=createApi(transport);await api.login('block2-admin',config.password);
const service=await saveCatalog(api,'ADMIN','services',{code:'product-service',name:'产品测试',upstreamUri:config.demo,enabled:'true'},null);
const updated=await saveCatalog(api,'ADMIN','services',{name:'产品更新'},service);
assert.equal(updated.code,service.code);assert.equal(updated.upstreamUri,service.upstreamUri);assert.equal(updated.enabled,true);
const entry=await saveCatalog(api,'ADMIN','apis',{serviceId:service.id,method:'GET',publicPath:'/api/product/fast/{id}',upstreamPath:'/demo/fast/{id}',isPublic:'false',requiredScope:'demo:read',enabled:'true'},null);
const edited=await saveCatalog(api,'ADMIN','apis',{upstreamPath:'/demo/fast/{id}'},entry);assert.equal(edited.requiredScope,'demo:read');assert.equal(edited.isPublic,false);
const route=await saveCatalog(api,'ADMIN','routes',{routeId:'product-fast',apiId:entry.id,priority:'0',enabled:'true'},null);
const policy=await savePolicy(api,'ADMIN',{code:'product-timeout',type:'TIMEOUT',scope:'ROUTE',targetId:route.id,timeoutMs:500,enabled:'true'},null);
const bindings=await api.request('/admin/policies/'+policy.id+'/bindings');assert.equal(bindings[0].targetLabel,'product-fast');assert.equal(bindings[0].targetRouteId,'product-fast');
await savePolicy(api,'ADMIN',{code:policy.code,type:policy.type,scope:'GLOBAL',targetId:999999,timeoutMs:200,enabled:'true'},policy,bindings);
assert.deepEqual(await api.request('/admin/policies/'+policy.id+'/bindings'),bindings);
for(const role of ['viewer','operator']){
 const other=createApi(transport);await other.login(role,config.password);assert.equal((await other.request('/admin/policies/'+policy.id+'/bindings')).length,1);
 const backend=await fetch(config.control+'/admin/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:role,password:config.password})});
 const login=await backend.json();
 const denied=await fetch(config.control+(role==='viewer'?'/admin/services':'/admin/callers'),{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+login.token},body:'{}'});assert.equal(denied.status,403);records.push({path:role==='viewer'?'/admin/services':'/admin/callers',status:denied.status});
 await other.logout();
}
const current=await api.request('/admin/releases/current');const published=await publishConfiguration(api,'ADMIN',current);
async function applied(version){for(let n=0;n<75;n++){const nodes=await api.request('/admin/nodes');if(nodes.length===2&&nodes.every(n=>n.appliedVersion===version&&n.freshness==='ONLINE'))return;await new Promise(r=>setTimeout(r,200));}throw new Error('节点应用超时');}
await applied(published.version);
const caller=await createCaller(api,'ADMIN',{username:'product-caller',scopes:'demo:read'});
let response=await fetch(config.gateway+'/api/product/fast/123',{headers:{Authorization:'Bearer '+caller.token}});assert.equal(response.status,200);assert.equal((await response.json()).id,'123');records.push({path:'/api/product/fast/123',status:response.status});
await revokeCaller(api,'ADMIN',caller.id);response=await fetch(config.gateway+'/api/product/fast/123',{headers:{Authorization:'Bearer '+caller.token}});assert.equal(response.status,401);records.push({path:'/api/product/fast/123',status:response.status});
await assert.rejects(publishConfiguration(api,'ADMIN',current),error=>error.status===409);
const rolled=await publishConfiguration(api,'ADMIN',{version:published.version},1);await applied(rolled.version);
response=await fetch(config.gateway+'/api/product/fast/123');assert.equal(response.status,404);records.push({path:'/api/product/fast/123',status:response.status});
await api.logout();
process.stdout.write(JSON.stringify({passed:true,records,publishedVersion:published.version,rolledVersion:rolled.version}));
'''

def main():
    lab=Lab(); passed=False; result=None
    target=ROOT/('.local/acceptance/product-console-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%f')+'.json')
    try:
        # 1. 仅创建随机端口容器与自有JVM；角色夹具只写本Lab临时库。
        lab.start()
        lab.expect(200,'/')
        lab.sql("INSERT INTO fg_principal(principal_type,username,password_hash,role,scopes_json,enabled) SELECT 'ADMIN',LOWER(r.role),p.password_hash,r.role,'[]',1 FROM fg_principal p JOIN (SELECT 'VIEWER' role UNION ALL SELECT 'OPERATOR') r WHERE p.username='block2-admin'")
        for asset in ('','style.css','api.js','ui.js','navigation.js','report-context.js','app.js','catalog.js','governance.js','operations.js','guide.html'):
            lab.expect(200,'/console/'+asset)
        lab.expect(200,'/test-console/');lab.expect(200,'/test-console/guide.html')
        lab.expect(401,'/admin/policies/1/bindings');lab.admin_call(404,'/admin/policies/99999/bindings')
        # 2. 凭证通过stdin交给子进程闭包；公开证据只保存路径/状态与版本。
        child=subprocess.run(['node','--input-type=module','-e',NODE],cwd=ROOT,input=json.dumps({'control':lab.control,'gateway':lab.gateways[0],'demo':f'http://127.0.0.1:{lab.demo_port}','password':lab.password}),text=True,capture_output=True)
        if child.returncode:
            print(child.stderr);raise AssertionError('产品真实JS模块HTTP验证失败，退出码'+str(child.returncode))
        result=json.loads(child.stdout);assert result['passed'];passed=True
        print('PASS /console/业务页面资源/旧开发入口；真实产品JS登录/完整创建编辑/绑定只读/RBAC/发布双节点/Caller吊销/409/回滚',flush=True)
    finally:
        lab.close();target.parent.mkdir(parents=True,exist_ok=True)
        with target.open('x') as stream:
            os.chmod(target,0o600);json.dump({'passed':passed,'recordedAt':datetime.now(timezone.utc).isoformat(),'testedGitSha':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'requests':lab.evidence,'productModuleResult':result},stream,ensure_ascii=False,indent=2)
        print('本机脱敏证据：',target,'passed=',passed,flush=True)

if __name__=='__main__':main()

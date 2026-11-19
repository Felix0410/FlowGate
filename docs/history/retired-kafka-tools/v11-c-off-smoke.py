#!/usr/bin/env python3
"""C最终默认OFF与V1旧Caller/OPERATOR权限实际HTTP兼容，只用隔离资源。"""
import importlib.util,json
from pathlib import Path
from datetime import datetime,timezone
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('base',ROOT/'scripts/block2-smoke.py');b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
lab=b.Lab();passed=False
try:
 lab.start();lab.sql((ROOT/'sql/flowgate-v11.sql').read_text());lab.sql((ROOT/'sql/flowgate-v11-c.sql').read_text())
 lab.sql("INSERT INTO fg_principal(principal_type,username,password_hash,role,scopes_json,enabled) SELECT 'ADMIN',LOWER(r.role),p.password_hash,r.role,'[]',1 FROM fg_principal p JOIN (SELECT 'VIEWER' role UNION ALL SELECT 'OPERATOR') r WHERE p.username='block2-admin'")
 tokens={role:lab.expect(200,'/admin/auth/login',body={'username':role,'password':lab.password})['token'] for role in ('operator','viewer')}
 for role,token in tokens.items():
  for path in ('/admin/usage','/admin/async-events','/admin/async-events/summary','/admin/reconciliation'):lab.expect(200,path,token=token)
 lab.expect(201,'/admin/services',token=tokens['operator'],body={'code':'off-operator','name':'旧权限','upstreamUri':f'http://127.0.0.1:{lab.demo_port}','enabled':True})
 lab.expect(403,'/admin/services',token=tokens['viewer'],body={'code':'off-viewer','name':'只读','upstreamUri':f'http://127.0.0.1:{lab.demo_port}','enabled':True})
 result=lab.expect(200,'/admin/releases/publish',token=tokens['operator'],body={'expectedVersion':lab.version});lab.version=result['version'];lab.applied()
 old=lab.admin_call(201,'/admin/callers',body={'username':'off-caller','scopes':['demo:read']});lab.expect(200,'/api/private/fast/1',base=lab.gateways[0],token=old['token']);lab.admin_call(204,f"/admin/callers/{old['id']}/revoke",body={});lab.expect(401,'/api/private/fast/1',base=lab.gateways[1],token=old['token'])
 summary=lab.admin_call(200,'/admin/async-events/summary');assert summary['asyncEnabled'] is False and summary['broker']['state']=='ASYNC_DISABLED'
 for path in ('/console/','/console/usage.js','/console/guide.html'):lab.expect(200,path)
 passed=True;print('PASS C最终无Kafka默认OFF/旧OPERATOR写与发布/VIEWER只读/旧Caller授权撤销/C只读资源',flush=True)
finally:
 lab.close();target=ROOT/('.local/acceptance/v11-c-off-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps({'passed':passed,'scope':'default OFF without Kafka, V1 operator publish, viewer read-only, caller authorize/revoke, C read APIs and assets'},ensure_ascii=False,indent=2));target.chmod(0o600);print('C OFF证据',target,'passed=',passed)

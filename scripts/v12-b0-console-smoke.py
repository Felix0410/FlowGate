#!/usr/bin/env python3
"""V12-B0继承A修复与真实Rabbit故障：仅全新E身份实例，双Gateway，保留所有卷；不重跑历史故障矩阵。"""
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
    work=core.ROOT/'.local'/'v12-b0-off'/run;runtime.private_directory(work.parent);runtime.private_directory(work)
    home=runtime.instance_home('v12b0off-'+run)
    java_home=os.environ['JAVA_HOME'];java=str(Path(java_home)/'bin/java');password=secrets.token_urlsafe(24)
    libs=work/'libs';runtime.private_directory(libs)
    with zipfile.ZipFile(core.jar_path('flowgate-control')) as jar:
        for name in jar.namelist():
            if name.startswith('BOOT-INF/lib/') and ('spring-security-crypto-' in name or 'commons-logging-' in name or 'amqp-client-' in name or 'slf4j-api-' in name or 'netty-' in name): (libs/Path(name).name).write_bytes(jar.read(name))
    hashed=core.command([java,'-cp',os.pathsep.join(str(p) for p in libs.iterdir()),str(core.ROOT/'scripts/PasswordHashTool.java')],data=password+'\n')
    source={'JAVA_HOME':java_home,'FG_BOOTSTRAP_ADMIN_USERNAME':'v12-admin','FG_BOOTSTRAP_ADMIN_BCRYPT':hashed,'FLOWGATE_INTERNAL_KEY':secrets.token_hex(32),'FLOWGATE_PORTAL_ENABLED':'false'}
    config=work/'config.env';config.write_text(''.join('export '+k+'='+shlex.quote(v)+'\n' for k,v in source.items()));os.chmod(config,0o600)
    ports={}
    for key in (*runtime.DEFAULT_PORTS,'gateway2'):
        with socket.socket() as s:s.bind(('127.0.0.1',0));ports[key]=s.getsockname()[1]
    state,env=runtime.fresh(home,source,{k:v for k,v in ports.items() if k!='gateway2'},config)
    runtime.save(home/'env.json',env)
    root_hash=core.file_hash(core.ROOT/'.env');checks=[];requests=[];second=None;success=False
    result={'runId':run,'scope':'legacy V11 zero V12 schema default OFF safety','designBase':'e77624f','checks':checks,'requests':requests,'notExecuted':['two-Control registration race','100-request concurrency/load','V11 B23/C26 real fault regression','Kafka full fault matrix (V11)','historical-day final MATCH','owner manual acceptance','V11 E6','V11 D performance']}
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
        control='http://127.0.0.1:'+str(ports['control'])
        code,value=core.http_json(control,'/admin/auth/login',body={'username':'v12-admin','password':password});assert code==200
        token=value['token'];code,ready=core.http_json(control,'/admin/market/readiness',token);assert code==200 and ready['status']=='DISABLED'
        assert sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='fg_api_listing';")=='0'
        assert core.http_json(control,'/admin/market/categories',token)[0]==503
        assert core.http_json(control,'/admin/market/categories',token,{'code':'blocked','name':'关闭时不可创建'})[0]==503
        assert core.http_json(control,'/admin/services',token)[0]==200
        assert core.http_json(control,'/admin/releases/current',token)[0]==200
        checks.extend(['legacy V11 with zero V12 tables: readiness DISABLED HTTP200','market read/write fail-closed503, no missing-table500','legacy services and releases HTTP200'])
        runtime.save(work/'browser.json',{'control':control,'loginName':'v12-admin','password':password})
        result.update(readiness=ready,v12Tables=0);success=True;print('旧V11默认OFF检查通过：'+str(work),flush=True)
    except Exception as error:result['failure']=str(error);print('旧V11检查失败：'+str(error),flush=True)
    finally:
        result.update(passed=success,rootEnvUnchanged=root_hash==core.file_hash(core.ROOT/'.env'),isolationProject=state['project'],localRuntime=str(work))
        (core.ROOT/'docs/progress/evidence/v12-b0-console.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        runtime.save(work/'cleanup.json',{'home':str(home),'owner':state['owner']})
        if not success or not args.keep_running:
            try:runtime.stop(home)
            except core.LocalFailure:pass
    return 0 if success else 1
if __name__=='__main__':raise SystemExit(main())

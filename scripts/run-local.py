#!/usr/bin/env python3
"""根.env常驻运行入口：只读静态配置，启动Rabbit依赖与本工具自己管理的三个JVM。"""
import json,os,signal,socket,subprocess,sys,time,uuid
from pathlib import Path
import v11_local_core as core

ROOT=Path(__file__).resolve().parents[1]
RUN=ROOT/'.local'/'run'
MODULES=('flowgate-demo','flowgate-control','flowgate-gateway')


def environment():
    # 1. 不source配置，不执行命令替换；敏感值只进入子进程环境，不打印到日志。
    values=core.read_environment(ROOT/'.env');env=dict(os.environ,**values)
    for key in list(env):
        if 'KAFKA' in key or key in ('FLOWGATE_USAGE_TOPIC','FLOWGATE_USAGE_GROUP'):env.pop(key)
    return env


def private_write(path,value):
    if path.is_symlink():raise RuntimeError('拒绝覆盖符号链接')
    path.parent.mkdir(parents=True,exist_ok=True,mode=0o700);os.chmod(path.parent,0o700)
    temporary=path.with_suffix(path.suffix+'.new');fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,'w') as f:json.dump(value,f)
    temporary.replace(path)


def state(create=False):
    file=RUN/'managed.json'
    if file.is_symlink():raise RuntimeError('运行清单不能为符号链接')
    if file.exists():
        if file.stat().st_mode&0o077:raise RuntimeError('运行清单须0600')
        value=json.loads(file.read_text())
        if len(value.get('owner',''))!=32:raise RuntimeError('运行清单身份不合法')
        return value
    value={'owner':uuid.uuid4().hex,'processes':{}}
    if create:private_write(file,value)
    return value


def identity(pid):
    result=subprocess.run(['ps','-p',str(pid),'-o','command='],capture_output=True,text=True)
    if result.returncode and result.stderr.strip():raise PermissionError('无法检查进程身份')
    command=result.stdout.strip()
    if not command or '<defunct>' in command:return None
    start=subprocess.run(['ps','-p',str(pid),'-o','lstart='],capture_output=True,text=True,check=True).stdout.strip()
    return command,start


def owned(module):
    saved=state();row=saved['processes'].get(module)
    if not row:return None
    actual=identity(row['pid'])
    if actual is None:return None
    jar=str(RUN/'artifacts'/(module+'.jar'))
    if jar not in actual[0] or '-Dflowgate.local.owner='+saved['owner'] not in actual[0] or actual[1]!=row['start']:raise RuntimeError('进程归属变化，拒绝接管或停止')
    return row['pid']


def endpoints(env):
    return {'flowgate-demo':(int(env.get('DEMO_PORT','8085')),'/demo/fast/ready'),'flowgate-control':(int(env.get('CONTROL_PORT','8081')),'/status'),'flowgate-gateway':(int(env.get('GATEWAY_PORT','8080')),'/actuator/health')}


def wait(fn,label,seconds=50):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            if fn():return
        except (core.LocalFailure,OSError):pass
        time.sleep(.3)
    raise RuntimeError(label+'未就绪，检查.local/run下私有日志；数据卷保留')


def stop():
    # 2. 全部身份先核对，之后才发信号；IDE进程、复用PID及数据库不被停止。
    pids=[owned(module) for module in MODULES]
    for pid in reversed(pids):
        if pid:os.kill(pid,signal.SIGTERM)
    wait(lambda:all(owned(module) is None for module in MODULES),'本工具JVM退出',25)
    saved=state();saved['processes']={};private_write(RUN/'managed.json',saved)
    print('本工具JVM已停止；MySQL、Redis、Rabbit及全部数据卷保持运行。')


def start(env):
    RUN.mkdir(parents=True,exist_ok=True,mode=0o700);os.chmod(RUN,0o700)
    checks=endpoints(env);saved=state(True)
    for module,(port,_) in checks.items():
        if owned(module):
            if saved['processes'][module]['sha256']!=core.file_hash(core.jar_path(module)):raise RuntimeError('新构建与运行Jar不同，请使用restart，不覆盖运行文件')
            continue
        with socket.socket() as test:
            test.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);test.bind(('127.0.0.1',port))
    java=str(Path(env['JAVA_HOME'])/'bin/java');version=subprocess.run([java,'-version'],capture_output=True,text=True,check=True)
    if 'version "17.' not in version.stderr+version.stdout:raise RuntimeError('请使用Java17')
    for module in MODULES:
        if not core.jar_path(module).is_file():raise RuntimeError('缺少Jar，请先Java17 mvn package')
    # 3. 显式列出Rabbit依赖；不重建已有MySQL/Redis，不使用remove-orphans或删卷。
    dependency=['docker','compose','-f','compose.local.yml','--profile','mq','up','-d','--no-recreate','--wait','--wait-timeout','180','mysql','redis','rabbitmq']
    with (RUN/'dependencies.log').open('a') as log:
        os.chmod(RUN/'dependencies.log',0o600)
        result=subprocess.run(dependency,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode:raise RuntimeError('Docker依赖未就绪，查看.local/run/dependencies.log')
    if env.get('FLOWGATE_ASYNC_ENABLED')=='true':
        if core.mysql_query(env,'SELECT mode FROM fg_legacy_mq_cutover WHERE id=1;','flowgate-local')!='RABBIT':raise RuntimeError('旧用量尚未完成RABBIT切换；本启动器不会自动迁移已有库')
        if not core.rabbit_probe(env,'flowgate-local'):raise RuntimeError('Rabbit协议未就绪')
    artifacts=RUN/'artifacts';artifacts.mkdir(exist_ok=True,mode=0o700)
    for module in MODULES:
        if not owned(module):
            jar=artifacts/(module+'.jar');jar.write_bytes(core.jar_path(module).read_bytes());os.chmod(jar,0o600)
            child=dict(env)
            if module!='flowgate-control':
                for key in list(child):
                    if key.startswith(('FG_MYSQL_','FG_BOOTSTRAP_','FLOWGATE_RABBITMQ_','FLOWGATE_ASYNC_','FLOWGATE_V12_','FLOWGATE_PORTAL_','FLOWGATE_QUOTA_')):child.pop(key)
            log_path=RUN/(module+'.log')
            with log_path.open('a') as log:
                os.chmod(log_path,0o600);process=subprocess.Popen([java,'-Dflowgate.local.owner='+saved['owner'],'-jar',str(jar)],cwd=ROOT,env=child,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            actual=identity(process.pid);saved['processes'][module]={'pid':process.pid,'start':actual[1],'sha256':core.file_hash(jar)};private_write(RUN/'managed.json',saved)
        port,path=checks[module]
        def ready(module=module,port=port,path=path):
            if not owned(module):raise RuntimeError(module+'提前退出')
            code,value=core.http_json('http://127.0.0.1:'+str(port),path)
            return code==200 and (module!='flowgate-control' or all(value.get(k)=='UP' for k in ['mysql','redis','demo']))
        wait(ready,module)
        print(module+' 已就绪：127.0.0.1:'+str(port),flush=True)
    origin='http://127.0.0.1:'+env.get('CONTROL_PORT','8081')
    print('Console：'+origin+'/console/\nPortal：'+origin+'/portal/\n开发测试台：'+origin+'/test-console/')


def main():
    mode=sys.argv[1] if len(sys.argv)>1 else 'start';env=environment()
    if mode=='start':start(env)
    elif mode=='stop':stop()
    elif mode=='restart':stop();start(env)
    elif mode=='status':
        for module,(port,path) in endpoints(env).items():
            try:print(module,'UP' if owned(module) and core.http_json('http://127.0.0.1:'+str(port),path)[0]==200 else 'UNKNOWN')
            except core.LocalFailure:print(module,'UNREACHABLE')
        print('Rabbit', 'UP' if core.rabbit_probe(env,'flowgate-local',consumer=env.get('FLOWGATE_ASYNC_ENABLED')=='true') else 'UNKNOWN')
    else:raise RuntimeError('用法：python3 scripts/run-local.py [start|stop|restart|status]')
if __name__=='__main__':
    try:main()
    except (RuntimeError,core.LocalFailure,KeyError,OSError):raise SystemExit('本地运行未完成；请检查私有日志、端口和配置。没有删除任何数据卷。')

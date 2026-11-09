#!/usr/bin/env python3
"""UX定向浏览器隔离Lab：随机端口/自有容器JVM，Ctrl-C或退出标记只清理自身资源。"""
import importlib.util
import json
import os
from pathlib import Path
import signal
import time

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('workbench_base', ROOT / 'scripts/block2-smoke.py')
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def main():
    # 1. 强制隔离异步与计量，不继承用户.env开关，更不访问常驻Kafka或用户数据库。
    os.environ['FLOWGATE_ASYNC_ENABLED'] = 'false'
    os.environ['FLOWGATE_QUOTA_ACTIVATION_ENABLED'] = 'false'
    os.environ['SPRING_WEB_RESOURCES_STATIC_LOCATIONS'] = (ROOT / 'flowgate-control/src/main/resources/static').as_uri() + '/'
    lab = base.Lab()
    config = lab.temp / 'browser-local.json'
    stop = lab.temp / 'browser-stop'
    signal.signal(signal.SIGTERM, lambda *_: stop.touch())
    signal.signal(signal.SIGINT, lambda *_: stop.touch())
    try:
        lab.start()
        for script in ('flowgate-v11.sql', 'flowgate-v11-c.sql'):
            lab.sql((ROOT / 'sql' / script).read_text())
        # 2. 仅创建本Lab业务样本与测试角色；不把Token或密码写进输出、截图或公共证据。
        lab.sql("INSERT INTO fg_principal(principal_type,username,password_hash,role,scopes_json,enabled) SELECT 'ADMIN',LOWER(r.role),p.password_hash,r.role,'[]',1 FROM fg_principal p JOIN (SELECT 'VIEWER' role UNION ALL SELECT 'OPERATOR') r WHERE p.username='block2-admin'")
        customer = lab.admin_call(201, '/admin/developers', body={'code': 'ux-customer', 'name': '工作台隔离客户', 'status': 'ACTIVE'})
        app = lab.admin_call(201, '/admin/apps', body={'developerId': customer['id'], 'code': 'ux-main', 'name': '主业务应用', 'status': 'ACTIVE'})
        lab.admin_call(201, '/admin/plans', body={'code': 'ux-plan', 'name': '隔离月度套餐', 'status': 'ACTIVE', 'monthlyUnits': 20})
        for index in range(20):
            lab.admin_call(201, '/admin/apps', body={'developerId': customer['id'], 'code': f'ux-page-{index}', 'name': f'分页测试应用{index:02}', 'status': 'ACTIVE'})
        data = {'url': lab.control + '/console/', 'username': 'block2-admin', 'password': lab.password, 'customerId': customer['id'], 'applicationId': app['id'], 'stopFile': str(stop)}
        with config.open('x') as handle:
            os.chmod(config, 0o600)
            json.dump(data, handle)
        print('ISOLATED_BROWSER_CONFIG=' + str(config), flush=True)
        # 3. 有限期保留自有Lab给浏览器使用；finally和信号均只处理本脚本创建的资源。
        deadline = time.monotonic() + 1800
        while not stop.exists() and time.monotonic() < deadline:
            time.sleep(1)
    finally:
        config.unlink(missing_ok=True)
        lab.close()


if __name__ == '__main__':
    main()

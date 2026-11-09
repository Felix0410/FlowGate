#!/usr/bin/env python3
"""V11本地只读诊断与显式隔离演示入口，不操作用户现有进程/数据卷。"""
import argparse
import json
from pathlib import Path
import sys
from v11_local_core import ROOT, LocalFailure, doctor, read_environment, report, check


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['doctor', 'up', 'status', 'stop'])
    parser.add_argument('--json', action='store_true')
    parser.add_argument('--profile', choices=['v1', 'v11'], default='v11')
    parser.add_argument('--config', type=Path, default=ROOT / '.env', help='只读静态环境文件，不执行Shell')
    parser.add_argument('--isolated', action='store_true', help='显式管理本工具的新隔离环境')
    parser.add_argument('--instance', default='default')
    for kind in ('control', 'gateway', 'demo', 'mysql', 'redis', 'kafka'): parser.add_argument('--' + kind + '-port', type=int)
    args = parser.parse_args()
    try:
        if args.isolated:
            import v11_local_runtime as runtime
            home = runtime.instance_home(args.instance)
            if args.mode == 'up':
                overrides = {kind: getattr(args, kind + '_port') for kind in runtime.DEFAULT_PORTS if getattr(args, kind + '_port') is not None}
                ports = dict(runtime.DEFAULT_PORTS, **overrides) if overrides else None
                with runtime.mutation_lock(home): result, code = runtime.start(home, args.config, ports)
                result['startup'] = 'RUNNING'  # 启动成功仍可能V0阻塞，绝不把业务门禁标绿。
            elif args.mode == 'stop':
                with runtime.mutation_lock(home): result, code = runtime.stop(home), 0
            else:
                result = runtime.status(home, args.profile)
                code = 0 if result['state'] == 'READY' else 2
        elif args.mode == 'doctor':
            env = read_environment(args.config)
            result = doctor(env, args.profile)
            code = 0 if result['state'] == 'READY' else 2
        else:
            raise LocalFailure('up/status/stop必须显式传--isolated，不能接管已有环境')
    except PermissionError:
        result = report([check('permission', 'UNKNOWN', '权限不足，无法验证运行身份；未接管进程', '检查当前用户的进程和Docker读取权限')], args.profile)
        code = 2
    except LocalFailure as error:
        result = report([check('configuration', 'BLOCKED', str(error), '参考.env.example手工配置；不会覆盖文件')], args.profile)
        code = 2
    except Exception:
        result = report([check('tool', 'UNKNOWN', '诊断工具失败，未输出原始异常', '检查工具版本和权限')], args.profile)
        code = 1
    if args.json:
        print(json.dumps(result, ensure_ascii=False))
    else:
        print('V11本地交付：' + result['state'])
        if result.get('startup'): print('隔离服务启动完成，业务门禁状态如上；V0需先发布非计量版本')
        if result.get('console'): print('工作台：' + result['console'])
        for item in result.get('checks', []):
            print(item['state'] + ' ' + item['id'] + '：' + item['reason'] + ('；下一步：' + item['action'] if item['action'] else ''))
    return code


if __name__ == '__main__':
    sys.exit(main())

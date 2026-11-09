#!/usr/bin/env python3
"""V11本地只读诊断与显式隔离演示入口，不操作用户现有进程/数据卷。"""
import argparse
import json
from pathlib import Path
import sys
from v11_local_core import ROOT, LocalFailure, doctor, read_environment, report, check


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['doctor'])
    parser.add_argument('--json', action='store_true')
    parser.add_argument('--profile', choices=['v1', 'v11'], default='v11')
    parser.add_argument('--config', type=Path, default=ROOT / '.env', help='只读静态环境文件，不执行Shell')
    args = parser.parse_args()
    try:
        env = read_environment(args.config)
        result = doctor(env, args.profile)
        code = 0 if result['state'] == 'READY' else 2
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
        for item in result['checks']:
            print(item['state'] + ' ' + item['id'] + '：' + item['reason'] + ('；下一步：' + item['action'] if item['action'] else ''))
    return code


if __name__ == '__main__':
    sys.exit(main())

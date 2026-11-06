#!/usr/bin/env python3
"""功能块4：使用已有隔离Lab，对比直连、仅代理、启用治理三组固定到达负载。"""
import argparse
import concurrent.futures
from datetime import datetime, timezone
import importlib.util
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[1]


def load_smoke():
    # 1. 复用Block2现成隔离MySQL/Redis/两Gateway Lab，不接触用户容器和数据卷。
    spec = importlib.util.spec_from_file_location("block2_smoke", ROOT / "scripts/block2-smoke.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def percentile(numbers, fraction):
    if not numbers:
        return None
    values = sorted(numbers)
    return round(values[max(0, math.ceil(len(values) * fraction) - 1)], 3)


def cpu_seconds(value):
    """解析macOS/Linux ps累计CPU时间；只用前后差值，不把累计百分比当稳态负载。"""
    days, clock = value.split('-') if '-' in value else ('0', value)
    parts = [float(part) for part in clock.split(':')]
    return int(days) * 86400 + sum(part * 60 ** index for index, part in enumerate(reversed(parts)))


def resources(lab):
    # 1. 仅采样本Lab自己的PID与Redis；失败明确记录未采集，不影响请求统计。
    result = {'jvms': {}, 'redis': {}}
    for role, process in zip(('demo', 'control', 'gateway0', 'gateway1'), lab.processes):
        try:
            fields = subprocess.check_output(['ps', '-p', str(process.pid), '-o', 'time=,rss='], text=True).split()
            result['jvms'][role] = {'cpuSeconds': cpu_seconds(fields[0]), 'rssKiB': int(fields[1])}
        except (OSError, ValueError, subprocess.CalledProcessError, IndexError):
            result['jvms'][role] = {'unavailable': True}
    try:
        info = dict(line.split(':', 1) for line in lab.redis('INFO').splitlines() if ':' in line)
        result['redis'] = {key: float(info[key]) for key in
                           ('used_cpu_sys', 'used_cpu_user', 'used_memory', 'used_memory_rss')}
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError):
        result['redis'] = {'unavailable': True}
    return result


def resource_delta(before, after, elapsed):
    result = {'before': before, 'after': after, 'cpuWindowSeconds': round(elapsed, 3), 'jvmMeanCpuOneCorePercent': {}}
    for role, end in after['jvms'].items():
        start = before['jvms'].get(role, {})
        if 'cpuSeconds' in start and 'cpuSeconds' in end:
            result['jvmMeanCpuOneCorePercent'][role] = round(100 * (end['cpuSeconds'] - start['cpuSeconds']) / elapsed, 3)
    if 'used_cpu_sys' in before['redis'] and 'used_cpu_sys' in after['redis']:
        result['redisMeanCpuOneCorePercent'] = round(100 * sum(after['redis'][key] - before['redis'][key]
            for key in ('used_cpu_sys', 'used_cpu_user')) / elapsed, 3)
    return result


def summarize(records, seconds):
    # 2. 成功RPS与HTTP总完成数、429/503以及客户端未发送的负载明确分开。
    completed = [r for r in records if isinstance(r.get("status"), int)]
    success = [r for r in completed if 200 <= r["status"] < 300]
    counts = {}
    for record in records:
        key = record["kind"] + ":" + str(record.get("status", "CLIENT_SHED"))
        counts[key] = counts.get(key, 0) + 1
    result = {"scheduled": len(records), "httpCompleted": len(completed),
              "businessSuccess": len(success), "businessSuccessRps": round(len(success) / seconds, 3),
              "businessSuccessRateScheduled": round(len(success) / len(records), 5) if records else None,
              "clientShed": sum(r.get("status") == "CLIENT_SHED" for r in records),
              "networkErrors": sum(r.get("status") == "NETWORK_ERROR" for r in records),
              "statusCounts": counts}
    for key, sample in (("fastSuccess", [r for r in success if r["kind"] == "fast"]),
                        ("allSuccess", success), ("allHttp", completed)):
        latencies = [r["latencyMs"] for r in sample]
        result[key] = {"samples": len(latencies), "meanMs": round(sum(latencies) / len(latencies), 3) if latencies else None, "p50Ms": percentile(latencies, .50),
                       "p95Ms": percentile(latencies, .95), "p99Ms": percentile(latencies, .99)}
    return result


def scheduled_requests(seconds, fast_rps, slow_rps):
    # 3. 三组完全相同的预定请求时间表；不能让快速429缩短闭环间隔后制造额外请求。
    items = []
    for kind, rate in (("fast", fast_rps), ("slow", slow_rps)):
        items.extend((n / rate, kind) for n in range(seconds * rate))
    return sorted(items, key=lambda item: (item[0], item[1]))


def run_group(lab, group, args, repetition, warmup=False):
    seconds = args.warmup if warmup else args.seconds
    if not seconds:
        return {"summary": {}, "requests": [], "observations": []}
    direct = "http://127.0.0.1:" + str(lab.demo_port)
    base = direct if group == "DIRECT" else lab.gateways[0]
    slots = threading.BoundedSemaphore(args.max_inflight)
    records, observations = [], []
    resource_started = time.monotonic()
    before = resources(lab)
    started = time.monotonic()
    deadline = started + seconds

    def invoke(offset, kind):
        path = ("/demo/fast/123" if kind == "fast" else "/demo/slow?delayMs=1000") if group == "DIRECT" else (
            "/api/demo/fast/123" if kind == "fast" else "/api/demo/slow?delayMs=1000")
        began = time.monotonic()
        try:
            status, _, _ = lab.request(path, base=base)
        except Exception:
            status = "NETWORK_ERROR"
        finally:
            slots.release()
        # 1. 不保存请求Body、Token、Header、用户地址；仅保存预定与实际时刻/状态。
        return {"kind": kind, "status": status, "plannedAtSeconds": round(offset, 4),
                "startedAtSeconds": round(began - started, 4),
                "latencyMs": round((time.monotonic() - began) * 1000, 3)}

    def observe():
        while time.monotonic() < deadline:
            try:
                status, body, _ = lab.request("/demo/observation", base=direct)
                if status == 200:
                    observations.append({"atSeconds": round(time.monotonic() - started, 3),
                                         "inFlight": body["inFlight"], "peak": body["peak"]})
            except Exception:
                pass
            time.sleep(.5)

    futures = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.max_inflight + 1) as pool:
        watcher = pool.submit(observe)
        for offset, kind in scheduled_requests(seconds, args.fast_rps, args.slow_rps):
            remaining = started + offset - time.monotonic()
            if remaining > 0:
                time.sleep(remaining)
            if not slots.acquire(blocking=False):
                records.append({"kind": kind, "status": "CLIENT_SHED", "plannedAtSeconds": round(offset, 4)})
                continue
            futures.append(pool.submit(invoke, offset, kind))
        for future in futures:
            records.append(future.result())
        watcher.result()

    elapsed = time.monotonic() - started
    after = resources(lab)
    resource_elapsed = time.monotonic() - resource_started
    summary = summarize(records, seconds)
    summary.update({"group": group, "repetition": repetition,
                    "elapsedIncludingDrainSeconds": round(elapsed, 3),
                    "businessSuccessRpsIncludingDrain": round(summary['businessSuccess'] / elapsed, 3),
                    "phase": "warmup" if warmup else "measurement", "durationSeconds": seconds,
                    "demoPeakInFlightObserved": max((o["inFlight"] for o in observations), default=None)})
    return {"summary": summary,
            "requests": sorted(records, key=lambda r: (r["plannedAtSeconds"], r["kind"])),
            "observations": observations, 'resources': resource_delta(before, after, resource_elapsed)}


def set_governance(lab, policies, enabled):
    # 2. 正式管理API保存草稿后发布，等待两个Gateway实际applied再开始采样。
    for policy in policies:
        lab.update(policy, enabled=enabled)
    lab.publish()


def main():
    parser = argparse.ArgumentParser(description="FlowGate三组真实HTTP对照；仅使用临时隔离依赖")
    parser.add_argument("--seconds", type=int, default=30, help="每组采样5..120秒")
    parser.add_argument("--warmup", type=int, default=3, help="每组预热0..20秒")
    parser.add_argument("--repeats", type=int, default=2, help="完整三组重复1..5次")
    parser.add_argument("--fast-rps", type=int, default=8)
    parser.add_argument("--slow-rps", type=int, default=16)
    parser.add_argument("--max-inflight", type=int, default=48)
    parser.add_argument("--output", default=".local/acceptance/block4-performance.json")
    args = parser.parse_args()
    bounds = {"seconds": (5, 120), "warmup": (0, 20), "repeats": (1, 5),
              "fast_rps": (1, 30), "slow_rps": (1, 40), "max_inflight": (4, 128)}
    for name, (minimum, maximum) in bounds.items():
        if not minimum <= getattr(args, name) <= maximum:
            parser.error(name + "超出范围：" + str(minimum) + ".." + str(maximum))
    output = (ROOT / args.output).resolve()
    safe_dir = (ROOT / ".local/acceptance").resolve()
    if not output.is_relative_to(safe_dir) or output == safe_dir or output.suffix != ".json":
        parser.error("输出必须是.local/acceptance/目录下的JSON，不能修改其他文件")
    if output.exists():
        parser.error("目标证据已存在；请使用新文件名，绝不覆盖旧实测结果")
    if "JAVA_HOME" not in os.environ:
        parser.error("请先设置JAVA_HOME为JDK17并完成Maven package")
    smoke = load_smoke()
    lab = smoke.Lab()
    results = []
    started_at = datetime.now(timezone.utc).isoformat()
    passed = False
    try:
        lab.start(hotspot=True)
        rate = lab.policy("block4-slow-rate", "RATE_LIMIT", "ROUTE", lab.routes["slow"]["id"],
                          {"capacity": 2, "refillPerSecond": 2})
        concurrence = lab.policy("block4-slow-concurrency", "CONCURRENCY", "ROUTE",
                                 lab.routes["slow"]["id"], {"maxConcurrent": 2})
        policies = [rate, concurrence]
        for rep in range(args.repeats):
            set_governance(lab, policies, False)
            for group in ("DIRECT", "PROXY"):
                if args.warmup:
                    run_group(lab, group, args, rep, warmup=True)
                result = run_group(lab, group, args, rep)
                results.append(result)
                print(json.dumps(result["summary"], ensure_ascii=False), flush=True)
            set_governance(lab, policies, True)
            if args.warmup:
                run_group(lab, "GOVERNED", args, rep, warmup=True)
            result = run_group(lab, "GOVERNED", args, rep)
            results.append(result)
            print(json.dumps(result["summary"], ensure_ascii=False), flush=True)
        # 3. 完整执行仍可能被客户端丢负载/网络错误破坏公平性；不能只因脚本没抛异常标通过。
        passed = all(result['summary']['clientShed'] == 0 and result['summary']['networkErrors'] == 0 for result in results)
    finally:
        lab.close()
        # 3. 中途失败仍保留已取得的样本、标记passed=false；不将局部样本冒充完整结果。
        output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        payload = {"schemaVersion": 1, "passed": passed, "startedAt": started_at,
                   "finishedAt": datetime.now(timezone.utc).isoformat(),
                   "environment": {"platform": platform.platform(),
                                   "sourceGitSha": subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                                   "scriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                                   "javaVersion": subprocess.run([str(Path(os.environ['JAVA_HOME']) / 'bin/java'), '-version'], capture_output=True, text=True, check=True).stderr.strip(),
                                   "demoTomcatThreads": 8, "gatewayInstances": 2,
                                   "requestedConfig": {name: getattr(args, name) for name in bounds},
                                   "loadModel": "fixed-arrival open-loop; bounded client in-flight"},
                   "limitations": [
                       "直连无Gateway，代理组为公开路由；协议栈开销需独立解释",
                       "CLIENT_SHED是客户端未送达，绝不能视为网关429/503",
                       "Demo观测请求也占用共享Tomcat线程；此处仅为开发机对照",
                       "businessSuccessRps按发送窗口归一，包含窗口后的收尾；同时报告含收尾耗时的RPS",
                       "CPU取累计CPU秒差/采样墙钟；RSS仅前后采样，不等同峰值；预热不计正式样本",
                       "P99样本少时不稳；总HTTP与业务成功P95分别计算",
                       "本机并发限制不是两个Gateway共享的总上限"], "runs": results}
        with output.open("x", encoding="utf-8") as stream:
            os.chmod(output, 0o600)
            json.dump(payload, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        print("原始结果（不自动提交Git）：", output, "passed=", passed, flush=True)


if __name__ == "__main__":
    main()

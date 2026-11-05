#!/usr/bin/env python3
"""功能块 0 定向冒烟：启动本仓库两个 Jar，核对 HTTP 后清理自己启动的进程。"""
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
GATEWAY_PORT = int(os.environ.get("GATEWAY_PORT", "8080"))
DEMO_PORT = int(os.environ.get("DEMO_PORT", "8085"))
JAVA = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
GATEWAY = f"http://127.0.0.1:{GATEWAY_PORT}"
DEMO = f"http://127.0.0.1:{DEMO_PORT}"
# 不继承系统代理；本脚本只访问回环地址。
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(base, path, body=None, headers=None):
    req = urllib.request.Request(base + path, data=body, headers=headers or {})
    try:
        response = HTTP.open(req, timeout=8)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.status, response.headers, json.loads(raw) if raw else None


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def ready(process, base, path):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"应用提前退出: {base}")
        try:
            if request(base, path)[0] == 200:
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.2)
    raise RuntimeError(f"应用未就绪: {base}")


def main():
    # 1. 检查端口与产物，禁止停止或借用用户已有服务冒充本次验证。
    assert GATEWAY_PORT != DEMO_PORT, "两个应用必须使用不同端口"
    for port in (GATEWAY_PORT, DEMO_PORT):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    jars = {name: ROOT / name / "target" / f"{name}-0.0.1-SNAPSHOT.jar"
            for name in ("flowgate-demo", "flowgate-gateway")}
    for jar in jars.values():
        assert jar.is_file(), f"请先 package: {jar}"
    logs = Path(tempfile.mkdtemp(prefix="flowgate-block0-"))
    processes = []
    handles = []
    try:
        # 2. 使用独立 JVM 和真实 TCP 连接，分别等待两个服务就绪。
        env = dict(os.environ, DEMO_PORT=str(DEMO_PORT), GATEWAY_PORT=str(GATEWAY_PORT),
                   DEMO_UPSTREAM_URI=DEMO)
        for name, jar in jars.items():
            handle = open(logs / f"{name}.log", "w")
            handles.append(handle)
            processes.append(subprocess.Popen([JAVA, "-jar", str(jar)], env=env,
                                               stdout=handle, stderr=subprocess.STDOUT))
        ready(processes[0], DEMO, "/demo/fast/ready")
        ready(processes[1], GATEWAY, "/actuator/health")
        print("PASS 双 JVM 启动，监听 127.0.0.1:", GATEWAY_PORT, DEMO_PORT)

        # 3. 核对业务字段和协议；伪造多值标识必须被单个新 UUID 覆盖。
        status, headers, fast = request(GATEWAY, "/api/demo/fast/123?tag=abc",
                                       headers={"X-Request-Id": "forged,duplicate"})
        trace = headers["X-Request-Id"]
        uuid.UUID(trace)
        assert len(headers.get_all("X-Request-Id")) == 1
        assert status == 200 and fast == {"id": "123", "tag": "abc", "requestId": trace}
        direct = request(DEMO, "/demo/fast/123?tag=abc")[2]
        assert (direct["id"], direct["tag"]) == (fast["id"], fast["tag"])
        print("PASS GET Query/路径/直连对照/伪造 ID 覆盖:", fast)
        body = {"name": "flowgate", "中文": "透传", "nested": {"items": [1, True, None]}}
        status, headers, echo = request(GATEWAY, "/api/demo/echo", json.dumps(body).encode(),
                                       {"Content-Type": "application/json", "X-Demo-Header": "hello"})
        assert status == 201 and headers.get_content_type() == "application/json"
        assert echo == {"body": body, "demoHeader": "hello", "requestId": headers["X-Request-Id"]}
        assert trace != echo["requestId"]
        print("PASS POST 方法/JSON/自定义 Header/201/唯一 ID:", echo)
        for invalid in (b"[]", b"null", b"{"):
            assert request(GATEWAY, "/api/demo/echo", invalid, {"Content-Type": "application/json"})[0] == 400
        for delay, expected in (("0", 200), ("100", 200), ("2000", 200),
                                ("-1", 400), ("2001", 400), ("abc", 400)):
            status, headers, result = request(GATEWAY, "/api/demo/slow?delayMs=" + delay)
            assert status == expected, (delay, status)
            if expected == 200:
                assert result == {"delayMs": int(delay), "requestId": headers["X-Request-Id"]}
        assert request(GATEWAY, "/api/demo/slow")[2]["delayMs"] == 100
        print("PASS 非法 JSON 400；慢请求默认值、上下边界及非数值")
        for path in ("/unknown", "/admin/routes", "/demo/fast/123", "/actuator/env",
                     "/actuator/beans", "/actuator/heapdump", "/actuator/gateway/routes"):
            status, headers, _ = request(GATEWAY, path)
            assert status == 404 and headers["X-Request-Id"], (path, status)
        print("PASS 404 带 ID，非代理路径及敏感 Actuator 不暴露")

        # 4. 检查框架指标及低基数标签，再停止本脚本的 Demo 验证断联语义。
        assert request(GATEWAY, "/actuator/health")[2]["status"] == "UP"
        metrics = request(GATEWAY, "/actuator/metrics")[2]
        assert "spring.cloud.gateway.requests" in metrics["names"]
        metric = request(GATEWAY, "/actuator/metrics/spring.cloud.gateway.requests")[2]
        assert next(m["value"] for m in metric["measurements"] if m["statistic"] == "COUNT") > 0
        assert "path" not in {tag["tag"] for tag in metric["availableTags"]}
        print("PASS health/metrics，网关计时器:", json.dumps(metric, ensure_ascii=False))
        stop(processes[0])
        status, headers, _ = request(GATEWAY, "/api/demo/fast/123")
        assert 500 <= status <= 599 and headers["X-Request-Id"]
        print("PASS 下游停止后状态码:", status, "响应带 ID")
    finally:
        for process in reversed(processes):
            stop(process)
        for handle in handles:
            handle.close()
        print("应用日志:", logs)


if __name__ == "__main__":
    main()

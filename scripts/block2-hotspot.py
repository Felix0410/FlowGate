#!/usr/bin/env python3
"""15秒+15秒可复现热点小对照；独立资源，保留不含凭证的逐请求原始数据。"""
import concurrent.futures
import importlib.util
import json
import math
import platform
from pathlib import Path
import subprocess
import threading
import time

spec=importlib.util.spec_from_file_location('block2_smoke',Path(__file__).with_name('block2-smoke.py'))
smoke=importlib.util.module_from_spec(spec); spec.loader.exec_module(smoke)

def percentile(values,p):
    return sorted(values)[max(0,math.ceil(len(values)*p)-1)] if values else None

def experiment(lab,label,seconds=15):
    start=time.monotonic(); end=start+seconds; records=[]; observations=[]
    def worker(kind):
        path='/api/demo/slow?delayMs=1000' if kind=='slow' else '/api/demo/fast/123'
        period=.2 if kind=='slow' else .1
        own=[]
        while time.monotonic()<end:
            began=time.monotonic()
            try: status=lab.request(path,base=lab.gateways[0])[0]
            except Exception: status='network-error'
            own.append({'kind':kind,'atSeconds':round(began-start,3),'status':status,'latencyMs':round((time.monotonic()-began)*1000,3)})
            remaining=period-(time.monotonic()-began)
            if remaining>0: time.sleep(remaining)
        return own
    def observe():
        while time.monotonic()<end:
            status,data,_=lab.request('/demo/observation',base=f'http://127.0.0.1:{lab.demo_port}')
            if status==200: observations.append({'atSeconds':round(time.monotonic()-start,3),**data})
            time.sleep(.2)
    demo_pid=lab.processes[0].pid
    def cpu():
        return smoke.command(['ps','-p',str(demo_pid),'-o','%cpu=']).strip()
    before=cpu()
    with concurrent.futures.ThreadPoolExecutor(max_workers=18) as pool:
        watcher=pool.submit(observe)
        jobs=[pool.submit(worker,'slow') for _ in range(16)]+[pool.submit(worker,'fast')]
        for job in jobs: records.extend(job.result())
        watcher.result()
    fast=[r['latencyMs'] for r in records if r['kind']=='fast' and r['status']==200]
    counts={}
    for row in records:
        key=row['kind']+':'+str(row['status']); counts[key]=counts.get(key,0)+1
    return {'label':label,'durationSeconds':seconds,'requestCounts':counts,'fastP95Ms':percentile(fast,.95),'fastP99Ms':percentile(fast,.99),
        'fastSamples':len(fast),'demoCpuPercentBefore':before,'demoCpuPercentAfter':cpu(),'observations':observations,'requests':records}

def main():
    lab=smoke.Lab()
    try:
        # 1. 同一次Demo启动，8个共享Tomcat线程；两组采用同样客户端数和闭环发送节奏。
        lab.start(hotspot=True)
        before=experiment(lab,'A_NO_GOVERNANCE')
        # 2. 服务总在途6、慢路由在途2及每秒2令牌，留下fast可使用的处理资源。
        lab.policy('service-inflight','CONCURRENCY','SERVICE',lab.service['id'],{'maxConcurrent':6})
        lab.policy('slow-inflight','CONCURRENCY','ROUTE',lab.routes['slow']['id'],{'maxConcurrent':2})
        lab.policy('slow-rate','RATE_LIMIT','ROUTE',lab.routes['slow']['id'],{'capacity':2,'refillPerSecond':2})
        lab.publish()
        after=experiment(lab,'B_SLOW_GOVERNED')
        # 3. 原始样本不含地址、密码、Token和请求体；性能结果不预设改善百分比。
        output={'environment':{'platform':platform.platform(),'java':subprocess.run([smoke.JAVA,'-version'],capture_output=True,text=True,check=True).stderr.strip().splitlines()[0],
            'demoSharedTomcatThreads':8,'slowClientWorkers':16,'fastClientWorkers':1,'slowMinimumCycleMs':200,'fastMinimumCycleMs':100,
            'loadModel':'bounded closed loop; no unbounded client queue; same workers and pacing in both groups'},
            'limitations':['15秒样本，P99不稳定；开发机其他负载未隔离','CPU为ps进程生命周期平均值，不是区间采样','Demo观测请求也竞争Tomcat线程','仅一个Gateway承载本次热点负载，本机并发非分布式总量'],
            'runs':[before,after]}
        target=smoke.ROOT/'docs/progress/evidence/block2-hotspot.json'; target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps([{k:v for k,v in run.items() if k not in ('requests','observations')} for run in output['runs']],ensure_ascii=False,indent=2),flush=True)
        print('原始结果：',target,flush=True)
    finally: lab.close()

if __name__=='__main__': main()

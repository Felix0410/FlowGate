#!/usr/bin/env python3
"""复用M离线切换前先完整检查全机Control；绝不部分停止后才发现非自有实例。"""
import importlib.util,json,subprocess,sys
from datetime import datetime,timezone
from pathlib import Path
from v12_d_lab import Lab,c
root=Path(__file__).resolve().parents[1]
l=Lab(authenticate=False)
spec=importlib.util.spec_from_file_location('cut',root/'scripts/v12-m-cutover.py')
cut=importlib.util.module_from_spec(spec);spec.loader.exec_module(cut)
rows=cut.controls(l.home)
if any(not row['owned'] for row in rows):
 result={'status':'NOT_EXECUTED','reason':'原M离线切换要求全机Control退出；存在受保护的非D常驻Control，本轮无停机授权。前置扫描拒绝执行，不停止任何进程。','owner':l.state['owner'],'foreignControlCount':sum(not x['owned'] for x in rows),'priorAttempt':'旧包装器在逐项检查时已停止部分D Control后才触发保护断言；未执行切换CAS，D已由owner安全预览入口恢复。','observedAt':datetime.now(timezone.utc).isoformat()}
 (root/'docs/progress/evidence/v12-d2-cutover-safety.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(result,ensure_ascii=False));sys.exit(2)
source=(root/'scripts/v12-m-final-smoke.py').read_text()
source=source.replace('v12-m5-kafka-free-e2e.json','v12-d0-e2e.json').replace('v12-m0-baseline.json','v12-d0-preflight.json').replace('v12-m-final-runtime.json','v12-d2-cutover-b-runtime.json')
try:
 exec(compile(source,str(root/'scripts/v12-m-final-smoke.py'),'exec'),{'__name__':'__main__'})
finally:
 # 1. 即使故障断言中止，也只通过已有owner核验入口恢复本轮D进程。
 subprocess.run([sys.executable,str(root/'scripts/v12-d-preview.py'),'start'],check=True)

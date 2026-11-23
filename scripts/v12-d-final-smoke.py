#!/usr/bin/env python3
"""D隔离完整业务/恢复演练；--init才创建全新D owner，绝不复用C旧owner或flowgate-local。"""
import argparse,subprocess,sys,json
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--init',action='store_true');p.add_argument('--core',action='store_true');p.add_argument('--ack-only',action='store_true');args=p.parse_args();root=Path(__file__).resolve().parents[1]
if args.init:
 subprocess.run([sys.executable,str(root/'scripts/v12-b-smoke.py'),'--evidence-prefix','v12-d0','--keep-running'],check=True)
 subprocess.run([sys.executable,str(root/'scripts/v12-d-prepare.py')],check=True)
if args.core or args.ack_only:
 from v12_d_lab import Lab
 # Readable existing scenario sources are executed against D Lab, with separate evidence targets.
 scenarios=[('v12-c-promotion-smoke.py',{'v12-c4-c6-promotion-e2e.json':'v12-d7-promotion-e2e.json'}),('v12-c-recovery-smoke.py',{'v12-c3-c7-recovery.json':'v12-d7-redis-db-rabbit-recovery.json','v12-c8-performance.json':'v12-d5-catalog-promo-load.json',"load['promotionId']":"load['stages'][-1]['promotionId']"}),('v12-c-rabbit-smoke.py',{'v12-c5-rabbit-intent.json':'v12-d7-commit-ack.json'})]
 if args.ack_only:scenarios=scenarios[-1:]
 for file,replacements in scenarios:
  source=(root/'scripts'/file).read_text().replace('from v12_c_lab import','from v12_d_lab import')
  for before,after in replacements.items():source=source.replace(before,after)
  if file=='v12-c-rabbit-smoke.py':
   source=source.replace('l=Lab();classpath=',"l=Lab();poison_before=l.request('/admin/promotions/'+l.sql('SELECT MIN(id) FROM fg_promotion'),l.admin)['rabbit']['poison'];classpath=")
   source=source.replace("line.startswith('flowgate.commerce.v12.promo.poison\\t1\\t')","line.startswith('flowgate.commerce.v12.promo.poison\\t'+str(poison_before+1)+'\\t')")
  exec(compile(source,str(root/'scripts'/file),'exec'),{'__name__':'__main__'})
 print('D_FINAL_CORE_SMOKE_PASS',flush=True)

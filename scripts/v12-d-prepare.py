#!/usr/bin/env python3
"""D0后给本轮新owner安装C；先核对默认OFF/缺表，停止双Control后才DDL。"""
import importlib.util,json
from v12_d_lab import Lab,c
l=Lab();assert l.request('/admin/promotions/readiness',l.admin)['status']=='DISABLED';assert l.request('/portal/api/promotions')==[]
l.stop_controls();spec=importlib.util.spec_from_file_location('migration',c.ROOT/'scripts/v12-c-migrate.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);result=m.migrate(l.home);assert m.migrate(l.home)==result
l.restart(cache=True,promo=True);assert l.request('/admin/promotions/readiness',l.admin)['status']=='READY'
l.save('v12-d0-schema.json',{'passed':True,'migration':result,'defaultOffWithoutC500':False,'defaultOffWithoutCTested':True,'freshOwner':True,'exitCode':0});print('D_SCHEMA_READY',flush=True)

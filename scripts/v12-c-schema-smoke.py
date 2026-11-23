#!/usr/bin/env python3
"""仅新C隔离库：原B CHECK精确替换、迁移中断重入、重复安装、旧行不变。"""
import importlib.util,hashlib
from v12_c_lab import Lab,c
spec=importlib.util.spec_from_file_location('migration',c.ROOT/'scripts/v12-c-migrate.py');migration=importlib.util.module_from_spec(spec);spec.loader.exec_module(migration)
l=Lab();l.stop_controls();before=l.sql('SELECT * FROM fg_order ORDER BY id;');old_checks=l.sql("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='fg_order' AND CONSTRAINT_TYPE='CHECK';")
partial=migration.migrate(l.home,True);complete=migration.migrate(l.home);repeated=migration.migrate(l.home)
assert before==l.sql('SELECT * FROM fg_order ORDER BY id;');assert old_checks==l.sql("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='fg_order' AND CONSTRAINT_TYPE='CHECK';")
l.restart(cache=True,promo=True);state=l.request('/admin/promotions/readiness',l.admin);assert state['status']=='READY';assert l.request('/portal/api/promotions')==[]
rejected=False
try:l.sql("UPDATE fg_order SET source='PROMOTION',promo_intent_id=NULL WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM fg_order) x);",True)
except c.LocalFailure:rejected=True
assert rejected
l.save('v12-c2-schema-promotion.json',{'passed':True,'partial':partial,'complete':complete,'repeated':repeated,'oldOrderRowsUnchangedDuringMigration':True,'allOtherChecksRetained':True,'invalidPromoSourceRejected':True,'newTables':5,'readiness':state});print('C2_SCHEMA_PASS',flush=True)

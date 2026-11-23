#!/usr/bin/env python3
"""C五表及B订单CHECK兼容扩展；只针对显式指定、已核验owner的新隔离实例。"""
import argparse,re,sys
from pathlib import Path
import v11_local_core as c
import v11_local_runtime as r

def migrate(home,stop_after_check=False):
 s,e=r.load(home);r.containers(s,e);r.volume_identities(s)
 if s['project']=='flowgate-local':raise c.LocalFailure('禁止自动迁移常驻实例')
 if r.owned_pid(home,s,'flowgate-control'):raise c.LocalFailure('先停止本轮Control再执行DDL')
 sql=lambda q,ro=True:c.mysql_query(e,q,s['project'],s['owner'],read_only=ro)
 sql((c.ROOT/'sql/flowgate-v12-c.sql').read_text(),False)
 checks=[]
 raw=sql("SELECT tc.CONSTRAINT_NAME,cc.CHECK_CLAUSE FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='fg_order' AND tc.CONSTRAINT_TYPE='CHECK';")
 for line in raw.splitlines():
  name,clause=line.split('\t',1)
  if all(key in clause.lower() for key in ['source','promo_intent_id','sku_kind']):checks.append((name,clause))
 if len(checks)!=1:raise c.LocalFailure('无法唯一确认B订单来源CHECK，未修改其他约束')
 name,clause=checks[0]
 if name!='ck_fg_order_source_c':
  if not re.fullmatch(r'[A-Za-z0-9_]+',name) or 'PROMOTION' in clause:raise c.LocalFailure('旧来源约束不符合预期')
  # MySQL一次原子ALTER替换来源CHECK，不留下先DROP再ADD的无约束窗口。
  sql("ALTER TABLE fg_order DROP CHECK `"+name+"`, ADD CONSTRAINT ck_fg_order_source_c CHECK(sku_kind='PAID_BASE' AND ((source='NORMAL' AND promo_intent_id IS NULL) OR (source='PROMOTION' AND promo_intent_id IS NOT NULL)));",False)
 elif 'PROMOTION' not in clause:raise c.LocalFailure('同名C约束定义错误')
 if stop_after_check:return {'interruptedAfter':'atomic source CHECK replacement','resumable':True}
 fk=sql("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='fg_order' AND COLUMN_NAME='promo_intent_id' AND REFERENCED_TABLE_NAME='fg_promo_purchase_intent';")
 if fk=='0':sql('ALTER TABLE fg_order ADD CONSTRAINT fk_fg_order_promo_intent_c FOREIGN KEY(promo_intent_id) REFERENCES fg_promo_purchase_intent(id);',False)
 return {'tables':5,'sourceCheck':'ck_fg_order_source_c','intentForeignKey':True,'repeatable':True}
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--instance-home',type=Path,required=True);parser.add_argument('--stop-after-check',action='store_true');args=parser.parse_args()
 try:print(migrate(args.instance_home,args.stop_after_check))
 except c.LocalFailure as error:raise SystemExit(str(error))

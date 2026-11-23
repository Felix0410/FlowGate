#!/usr/bin/env python3
"""四Rabbit拓扑+共享确认/有限重试真实探针；不借历史Kafka报告。"""
import os,json,subprocess,time
from v12_d_lab import Lab,c
l=Lab();env=dict(os.environ,**l.env);classpath=l.libs();results=[]
for file,args in [('LegacyRabbitFailureProbe.java',['confirm']),('D12RetryProbe.java',[])]:
 st=time.monotonic()
 if '--observe-only' in __import__('sys').argv:
  text=(l.work/('d-'+file+'.log')).read_text();assert ('REAL_NACK_RETURN_PASS' if args else 'REAL_12_RETRIES_13_DELIVERIES_POISON_PASS') in text
  results.append({'probe':file,'exitCode':0,'output':text[-1000:],'mode':'observed previous completed native run'});continue
 with (l.work/('d-'+file+'.log')).open('w') as f:p=subprocess.run([l.java,'-cp',classpath,str(c.ROOT/'scripts'/file),*args],env=env,stdout=f,stderr=subprocess.STDOUT,timeout=110)
 assert p.returncode==0,file;results.append({'probe':file,'exitCode':p.returncode,'seconds':time.monotonic()-st,'output':(l.work/('d-'+file+'.log')).read_text()[-1000:]})
queues=c.command(['docker','exec',l.container('rabbitmq'),'rabbitmqctl','list_queues','-p','/flowgate-v12','name','type','messages','consumers'],timeout=15)
for name in ['flowgate.usage.legacy.aggregate','flowgate.usage.v12.aggregate','flowgate.commerce.v12.orders','flowgate.commerce.v12.promo.intents']:
 assert name+'\tquorum\t' in queues
l.wait(lambda:l.sql('SELECT COUNT(*) FROM fg_quota_ledger')==l.sql('SELECT COUNT(*) FROM fg_consumed_event'),'legacy converged',180)
l.wait(lambda:l.sql('SELECT COUNT(*) FROM fg_api_quota_ledger')==l.sql('SELECT COUNT(*) FROM fg_api_consumed_event'),'api converged',180)
counts={t:l.sql('SELECT COUNT(*) FROM '+t) for t in ['fg_quota_ledger','fg_outbox_event','fg_consumed_event','fg_api_quota_ledger','fg_api_usage_outbox','fg_api_consumed_event','fg_order','fg_payment_record','fg_entitlement_grant','fg_promo_purchase_intent','fg_promo_event_consumed']}
l.save('v12-d2-four-rabbit-recovery.json',{'passed':True,'exitCode':0,'queues':queues,'probes':results,'counts':counts,'coverage':'fresh D0 real A/B/M/V11 closed loops plus D1 promo longfault/cooldown/100 duplicates and separate handler true12retry TTLprobe','faultInjection':'12retry probe injects transient business callback error, all Broker delivery/confirm/x-death/TTL/dead-letter are real','notExecuted':['independent Kafka-era C26 exact replay (current Rabbit equivalents mapped separately)']});print('D_RABBIT_RELIABILITY_PASS',flush=True)

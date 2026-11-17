#!/usr/bin/env python3
"""仅在既有B自有隔离实例验证真实300秒原事件补投和Worker/退款竞态，保留卷。"""
import concurrent.futures,json,secrets,time
from pathlib import Path
import v11_local_core as c
import v11_local_runtime as r

def main():
    evidence=json.loads((c.ROOT/'docs/progress/evidence/v12-b7-e2e.json').read_text());work=Path(evidence['localRuntime']);home=Path(json.loads((work/'cleanup.json').read_text())['home']);state,env=r.load(home);cfg=json.loads((work/'browser.json').read_text());base=cfg['control']
    def req(path,token=None,body=None,expected=200):
        code,value=c.http_json(base,path,token,body)
        assert code==expected,(path,code,value.get('code') if isinstance(value,dict) else None)
        return value
    def sql(text,write=False):return c.mysql_query(env,text,state['project'],state['owner'],read_only=not write)
    def wait(fn,label,timeout=50):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            if fn():return
            time.sleep(.5)
        raise AssertionError(label)
    admin=req('/admin/auth/login',body={'username':cfg['adminLogin'],'password':cfg['password']})['token'];user=req('/portal/api/auth/login',body={'loginName':cfg['loginName'],'password':cfg['password']})['token'];dev=req('/portal/api/my-developers',user)[0]
    sku=next(s for s in req('/portal/api/catalog/apis/'+str(cfg['listingId'])+'/offers') if s['kind']=='PAID_BASE' and s['period_policy']=='CURRENT_MONTH')
    def create(label):
        app=req('/portal/api/apps',user,{'developerId':dev['id'],'code':'replay-'+secrets.token_hex(5),'name':label},201)
        return req('/portal/api/orders',user,{'applicationId':app['id'],'skuId':sku['id'],'clientRequestId':secrets.token_hex(16)},201)
    order=create('受控补投验证应用');path='/portal/api/orders/'+order['orderNo'];r.containers(state,env);r.compose(state,env,'stop','rabbitmq')
    try:
        req(path+'/simulate-pay',user,{})
        # 1. 只改本轮新订单模拟收据制造可修复权威冲突；没有修改或反转任何授权Ledger。
        sql('UPDATE fg_payment_record SET amount_cents=amount_cents+1 WHERE order_id='+str(order['id'])+';',True)
    finally:r.compose(state,env,'up','-d','--wait','rabbitmq')
    event=sql('SELECT event_id FROM fg_business_outbox WHERE aggregate_id='+str(order['id'])+';')
    wait(lambda:sql('SELECT status FROM fg_business_outbox WHERE aggregate_id='+str(order['id'])+';')=='SENT','publisher SENT')
    wait(lambda:req('/admin/commerce/readiness',admin)['status']=='READY','Worker ready')
    time.sleep(3)
    assert sql('SELECT COUNT(*) FROM fg_business_event_consumed WHERE aggregate_id='+str(order['id'])+';')=='0'
    rejected=req('/admin/commerce/events/'+event+'/replay',admin,{},409);assert rejected['code']=='REPLAY_NOT_ELIGIBLE',rejected['code']
    # 2. 修复隔离夹具原始收据，真实等待数据库next_attempt_at满300秒，不回拨时钟或改时间字段。
    sql('UPDATE fg_payment_record SET amount_cents='+str(order['amountCents'])+' WHERE order_id='+str(order['id'])+';',True)
    print('真实SENT未消费且不足300秒补投409；开始自然等待300秒',flush=True)
    races=[]
    for _ in range(3):
        race=create('Worker退款并发应用');rp='/portal/api/orders/'+race['orderNo'];req(rp+'/simulate-pay',user,{})
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            a=pool.submit(c.http_json,base,rp+'/simulate-refund',user,{})
            b=pool.submit(c.http_json,base,rp,user)
            ac,av=a.result();bc,bv=b.result()
        assert ac==200 and bc==200
        wait(lambda:req(rp,user)['status']=='REFUNDED','refund terminal')
        wait(lambda:sql('SELECT COUNT(*) FROM fg_business_event_consumed WHERE aggregate_id='+str(race['id'])+';')=='1','late/competing worker dedup')
        races.append({'refundStatus':ac,'observedState':bv['status'],'finalState':'REFUNDED','grantCount':int(sql("SELECT COUNT(*) FROM fg_entitlement_grant WHERE source_type='PAID_ORDER' AND source_id="+str(race['id'])+';'))})
    wait(lambda:sql('SELECT next_attempt_at<=UTC_TIMESTAMP(3) FROM fg_business_outbox WHERE aggregate_id='+str(order['id'])+';')=='1','自然等待SENT300秒',330)
    result=req('/admin/commerce/events/'+event+'/replay',admin,{});assert result['eventId']==event
    wait(lambda:req(path,user)['status']=='FULFILLED','replay fulfilled')
    req('/admin/commerce/events/'+event+'/replay',admin,{},409)
    assert sql("SELECT COUNT(*) FROM fg_audit_log WHERE action='REPLAY_COMMERCE_EVENT' AND target_id='"+event+"';")=='1'
    result={'passed':True,'realSent300SecondWait':True,'earlyReplayStatus':409,'adminReplayStatus':200,'consumedReplayStatus':409,'sameEventId':True,'auditRows':1,'grantCount':int(sql("SELECT COUNT(*) FROM fg_entitlement_grant WHERE source_type='PAID_ORDER' AND source_id="+str(order['id'])+';')),'refundWorkerRaces':races,'fixture':'new task-owned simulated receipt conflict repaired; no Ledger edits','startupCooldown':'covered by unit contract; independent live cold-start refusal NOT_EXECUTED','controlJarSha256':c.file_hash(c.jar_path('flowgate-control'))}
    (c.ROOT/'docs/progress/evidence/v12-b-replay.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('受控补投与退款Worker竞态通过',flush=True)
if __name__=='__main__':main()

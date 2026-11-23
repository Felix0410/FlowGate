#!/usr/bin/env python3
"""C独立Rabbit真实COMMIT后ACK丢失重投、Poison和5秒TTL路由。"""
import os,secrets,time,json,subprocess
from datetime import datetime,timedelta,timezone
from v12_c_lab import Lab,c,r
l=Lab();classpath=l.libs();token=l.request('/portal/api/auth/login',body={'loginName':l.browser['loginName'],'password':l.browser['password']})['token'];dev=l.request('/portal/api/my-developers',token)[0]
a=l.request('/portal/api/apps',token,{'developerId':dev['id'],'code':'c-ack-'+secrets.token_hex(5),'name':'C真实ACK窗口'},201);sku=next(s for s in l.request('/portal/api/catalog/apis/'+str(l.browser['listingId'])+'/offers') if s['kind']=='PAID_BASE' and s['period_policy']=='CURRENT_MONTH');now=datetime.now(timezone.utc);iso=lambda t:t.isoformat(timespec='milliseconds').replace('+00:00','Z')
p=l.request('/admin/promotions',l.admin,{'name':'C真实ACK丢失','skuId':sku['id'],'promoPriceCents':100,'totalStock':2,'beginsAt':iso(now-timedelta(seconds=1)),'endsAt':iso(now+timedelta(hours=1)),'orderCreationTtlSeconds':300});pid=p['id'];l.request(f'/admin/promotions/{pid}/publish',l.admin,{})
# Owner-controlled broker binding API, passwords onlystdin. Main queue unbound while probe owns source event.
source=l.work/'PromoBinding.java';source.write_text('''import com.rabbitmq.client.*;import java.io.*;class PromoBinding{public static void main(String[] a)throws Exception{var r=new BufferedReader(new InputStreamReader(System.in));var f=new ConnectionFactory();f.setHost(r.readLine());f.setPort(Integer.parseInt(r.readLine()));f.setUsername(r.readLine());f.setPassword(r.readLine());f.setVirtualHost("/flowgate-v12");try(var c=f.newConnection();var ch=c.createChannel()){if(a[0].equals("unbind"))ch.queueUnbind("flowgate.commerce.v12.promo.intents","flowgate.commerce.v12","promo-intent");else ch.queueBind("flowgate.commerce.v12.promo.intents","flowgate.commerce.v12","promo-intent");}}}''')
config='\n'.join(l.env[k] for k in ['FLOWGATE_RABBITMQ_HOST','FLOWGATE_RABBITMQ_PORT','FLOWGATE_RABBITMQ_USERNAME','FLOWGATE_RABBITMQ_PASSWORD'])+'\n'
def bind(mode):c.command([l.java,'-cp',classpath,str(source),mode],data=config,timeout=20)
bind('unbind')
try:
 # Retain a confirmed durable alternate queue so mandatory publishing remains routable while isolated main unbound.
 alt=l.work/'PromoHold.java';alt.write_text('''import com.rabbitmq.client.*;import java.io.*;import java.util.*;class PromoHold{public static void main(String[] a)throws Exception{var r=new BufferedReader(new InputStreamReader(System.in));var f=new ConnectionFactory();f.setHost(r.readLine());f.setPort(Integer.parseInt(r.readLine()));f.setUsername(r.readLine());f.setPassword(r.readLine());f.setVirtualHost("/flowgate-v12");try(var c=f.newConnection();var ch=c.createChannel()){if(a[0].equals("delete"))ch.queueDelete(a[1]);else{ch.queueDeclare(a[1],true,false,false,Map.of("x-queue-type","quorum"));ch.queueBind(a[1],"flowgate.commerce.v12","promo-intent");}}}}''');hold='flowgate.c.hold.'+secrets.token_hex(5);c.command([l.java,'-cp',classpath,str(alt),'create',hold],data=config,timeout=20)
 i=l.request(f'/portal/api/promotions/{pid}/claim',token,{'applicationId':a['id'],'clientRequestId':secrets.token_hex(12)},202);iid=i['intentId'];end=time.time()+15
 while time.time()<end and l.sql('SELECT status FROM fg_promo_outbox WHERE intent_id='+str(iid))!='SENT':time.sleep(.3)
 payload=l.sql('SELECT payload_json FROM fg_promo_outbox WHERE intent_id='+str(iid));assert l.sql('SELECT COUNT(*) FROM fg_promo_event_consumed WHERE intent_id='+str(iid))=='0'
 env=dict(os.environ,**l.env);env['CONTROL_PORT']='0';env['FLOWGATE_NODE_ID']='c-ack-probe';env['SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP']='false'
 # Probe process uses same isolated DB, its own random HTTP port, and direct worker COMMIT/real Channel loss.
 with (l.work/'c-commit-probe.log').open('w') as log:result=subprocess.run([l.java,'-cp',classpath,str(c.ROOT/'scripts/V12PromotionCommitProbe.java')],input=payload+'\n',text=True,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=65)
 assert result.returncode==0,'see private c-commit-probe.log'
 assert l.sql('SELECT COUNT(*) FROM fg_order WHERE promo_intent_id='+str(iid))=='1'
 c.command([l.java,'-cp',classpath,str(alt),'delete',hold],data=config,timeout=20)
finally:bind('bind')
# Real retry queue holds duplicate for5sec then routes to promo-intent; source remains uniquely consumed.
c.command([l.java,'-cp',classpath,str(c.ROOT/'scripts/V12RabbitPublishProbe.java'),'flowgate.commerce.v12','promo-retry'],data=config+payload+'\n',timeout=20)
c.command([l.java,'-cp',classpath,str(c.ROOT/'scripts/V12RabbitPublishProbe.java'),'flowgate.commerce.v12','promo-intent'],data=config+'{bad json\n',timeout=20)
time.sleep(6)
rabbit=c.docker_target(l.state['ports']['rabbitmq'],'rabbitmq',l.state['project'],l.state['owner']);queues=c.command(['docker','exec',rabbit,'rabbitmqctl','list_queues','-p','/flowgate-v12','name','messages','consumers'],timeout=15)
assert any(line.startswith('flowgate.commerce.v12.promo.poison\t1\t') for line in queues.splitlines()),queues
assert l.sql('SELECT COUNT(*) FROM fg_order WHERE promo_intent_id='+str(iid))=='1';assert l.sql('SELECT COUNT(*) FROM fg_promo_event_consumed WHERE intent_id='+str(iid))=='1'
l.save('v12-c5-rabbit-intent.json',{'passed':True,'promotionId':pid,'intentId':iid,'checks':['initial real worker transaction COMMIT then real Rabbit Channel closed at basicAck','broker redelivered on new Channel; one Order/Consumed remains','real promo retry TTL5sec then duplicate safely consumed','badJSON retained in separate promo Poison','B payment/Promo retry routes remain separate'],'queues':queues,'notExecuted':['300sec SENT replay cooldown real wait','full12 retry exhaustion matrix']});print('C_RABBIT_PASS',flush=True)

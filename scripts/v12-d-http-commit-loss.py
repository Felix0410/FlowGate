#!/usr/bin/env python3
"""Drop actual quota HTTP200 after DB commit; Gateway must reuse chargeId and forward POST once."""
import os,json,time,secrets,socket,signal,threading,http.server,subprocess,urllib.request,urllib.error
from datetime import datetime,timedelta,timezone
from v12_d_lab import Lab,c,r
l=Lab();tag=secrets.token_hex(4);node='d-loss-'+tag;demo='http://127.0.0.1:'+str(l.state['ports']['demo'])
# Enable existing observation on the owned Demo only; no new business endpoint or user process.
pid=r.owned_pid(l.home,l.state,'flowgate-demo');os.kill(pid,signal.SIGTERM);r.wait_for(lambda:not r.identity(pid),'owned Demo stopped',20);l.env['FLOWGATE_DEMO_OBSERVATION']='true';r.save(l.home/'env.json',l.env)
with (l.work/'d-observed-demo.log').open('a') as f:p=subprocess.Popen([l.java,'-Dflowgate.v11-demo.owner='+l.state['owner'],'-jar',str(l.home/'artifacts/flowgate-demo.jar')],env=dict(os.environ,**l.env),cwd=c.ROOT,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
l.state['processes']['flowgate-demo']={'pid':p.pid,'start':r.identity(p.pid)[1]};r.save(l.home/'state.json',l.state);l.wait(lambda:c.http_json(demo,'/demo/observation')[0]==200,'Demo observation')
service=l.request('/admin/services',l.admin)[0];api=l.request('/admin/apis',l.admin,{'serviceId':service['id'],'method':'POST','publicPath':'/api/d-loss/'+tag,'upstreamPath':'/demo/echo','isPublic':False,'requiredScope':'demo:read','enabled':True},201);route=l.request('/admin/routes',l.admin,{'apiId':api['id'],'routeId':'d-loss-'+tag,'priority':0,'enabled':True},201);pol=l.request('/admin/policies',l.admin,{'code':'d-loss-'+tag,'type':'USAGE_QUOTA','scope':'API','parametersJson':'{"unitsPerCall":1}','enabled':True},201);l.request('/admin/policies/'+str(pol['id'])+'/bindings',l.admin,{'targetType':'API','targetId':api['id']},201)
cur=l.request('/admin/releases/current',l.admin)['version'];release=l.request('/admin/releases/publish',l.admin,{'expectedVersion':cur});l.wait(lambda:sum(n['appliedVersion']==release['version'] for n in l.request('/admin/nodes',l.admin))==2,'normal Gateways applied')
dev=l.request('/admin/developers',l.admin,{'code':'d-loss-'+tag,'name':'D HTTP loss'},201);plan=l.request('/admin/plans',l.admin,{'code':'d-loss-'+tag,'name':'D HTTP loss','monthlyUnits':1000},201);app=l.request('/admin/apps',l.admin,{'developerId':dev['id'],'code':'d-loss-'+tag,'name':'D HTTP loss'},201);now=datetime.now(timezone.utc);start=now.replace(day=1,hour=0,minute=0,second=0,microsecond=0);end=(start+timedelta(days=32)).replace(day=1);iso=lambda d:d.isoformat().replace('+00:00','Z');l.request('/admin/subscriptions',l.admin,{'applicationId':app['id'],'planId':plan['id'],'effectiveAt':iso(start),'expiresAt':iso(end)},201);credential=l.request('/admin/apps/'+str(app['id'])+'/credentials',l.admin,{'scopes':['demo:read']})['token']
class Proxy(http.server.BaseHTTPRequestHandler):
 charges=[]
 def log_message(self,*args):pass
 def do_GET(self):self.forward()
 def do_POST(self):self.forward()
 def forward(self):
  body=self.rfile.read(int(self.headers.get('Content-Length','0'))) if self.command=='POST' else None
  if self.path=='/internal/quota/charge':Proxy.charges.append(json.loads(body)['chargeId'])
  request=urllib.request.Request(l.base+self.path,data=body,headers={k:v for k,v in self.headers.items() if k.lower() not in ['host','connection','content-length']},method=self.command)
  try:response=c.HTTP.open(request,timeout=6)
  except urllib.error.HTTPError as e:response=e
  with response:raw=response.read();status=response.status;headers=dict(response.headers)
  if self.path=='/internal/quota/charge' and len(Proxy.charges)==1 and status==200:self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
  self.send_response(status)
  for k,v in headers.items():
   if k.lower() not in ['connection','content-length','transfer-encoding']:self.send_header(k,v)
  self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Proxy);threading.Thread(target=server.serve_forever,daemon=True).start()
with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
env=dict(os.environ,**l.env);env.update(GATEWAY_PORT=str(port),FLOWGATE_NODE_ID=node,FLOWGATE_CONTROL_URI='http://127.0.0.1:'+str(server.server_address[1]))
for k in list(env):
 if k.startswith(('FG_MYSQL_','FG_BOOTSTRAP_','FLOWGATE_ASYNC_','FLOWGATE_QUOTA_','FLOWGATE_RABBITMQ_','FLOWGATE_V12_','FLOWGATE_PORTAL_')):env.pop(k)
jar=l.home/'artifacts/d-loss-gateway.jar';jar.write_bytes(c.jar_path('flowgate-gateway').read_bytes());before=c.http_json(demo,'/demo/observation')[1]['echo']
with (l.work/'d-loss-gateway.log').open('w') as f:gw=subprocess.Popen([l.java,'-Dflowgate.v12-test.owner='+l.state['owner'],'-jar',str(jar)],env=env,cwd=c.ROOT,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
try:
 l.wait(lambda:c.http_json('http://127.0.0.1:'+str(port),'/actuator/health')[0]==200,'loss Gateway health');l.wait(lambda:any(n['nodeId']==node and n['appliedVersion']==release['version'] for n in l.request('/admin/nodes',l.admin)),'loss Gateway snapshot')
 body={'marker':'actual POST after HTTP commit loss'};status,value=c.http_json('http://127.0.0.1:'+str(port),'/api/d-loss/'+tag,credential,body);assert status==201 and value['body']==body
 assert len(Proxy.charges)==2 and len(set(Proxy.charges))==1,Proxy.charges
 assert l.sql('SELECT COUNT(*) FROM fg_quota_ledger WHERE application_id='+str(app['id']))=='1';assert c.http_json(demo,'/demo/observation')[1]['echo']==before+1
finally:
 actual=r.identity(gw.pid)
 if actual:assert str(jar) in actual[0] and l.state['owner'] in actual[0];os.kill(gw.pid,signal.SIGTERM);gw.wait(20)
 server.shutdown();l.sql("DELETE FROM fg_release_node WHERE node_id='"+node+"';DELETE FROM fg_node_capability WHERE node_id='"+node+"';",True)
l.save('v12-d2-http-commit-loss.json',{'passed':True,'exitCode':0,'realHttpAttempts':2,'sameChargeId':True,'ledgerRows':1,'downstreamPostCalls':1,'businessStatus':201,'bodyPreserved':True,'extraGatewayStopped':True,'extraNodeMetadataRemoved':True,'mode':'actual proxy discards first successful quota HTTP response after server commit; no mocked transport'});print('D_HTTP_COMMIT_LOSS_PASS')

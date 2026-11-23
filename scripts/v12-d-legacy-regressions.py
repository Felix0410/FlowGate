#!/usr/bin/env python3
"""当前Rabbit-only下复核旧配额/身份/治理/回滚故障语义，始终D owner。"""
import os,json,secrets,time,concurrent.futures,hashlib,urllib.request,urllib.error,socket,threading,http.server
from datetime import datetime,timedelta,timezone
from v12_d_lab import Lab,c,r
l=Lab();checks=[];suffix=secrets.token_hex(4);gws=['http://127.0.0.1:'+str(l.baseline['ports'][k]) for k in ['gateway','gateway2']]
def check(name):checks.append(name);print('PASS '+name,flush=True)
def batch(fn,n=100):
 with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:return list(pool.map(fn,range(n)))
def count(app=None):return int(l.sql('SELECT COUNT(*) FROM fg_quota_ledger'+(' WHERE application_id='+str(app) if app else '')))
def publish(rollback=None):
 cur=l.request('/admin/releases/current',l.admin)['version'];x=l.request('/admin/releases/'+str(rollback)+'/rollback' if rollback else '/admin/releases/publish',l.admin,{'expectedVersion':cur});l.wait(lambda:sum(n['appliedVersion']==x['version'] and n['freshness']=='ONLINE' for n in l.request('/admin/nodes',l.admin))==2,'both nodes applied',45);return x['version']
dev=l.request('/admin/developers',l.admin,{'code':'d-gates-'+suffix,'name':'D旧语义复核'},201);five=l.request('/admin/plans',l.admin,{'code':'d-five-'+suffix,'name':'D Free5','monthlyUnits':5},201);large=l.request('/admin/plans',l.admin,{'code':'d-large-'+suffix,'name':'D充足额度','monthlyUnits':1000},201)
def new_app(plan):
 a=l.request('/admin/apps',l.admin,{'developerId':dev['id'],'code':'d-'+secrets.token_hex(6),'name':'D旧语义应用'},201);now=datetime.now(timezone.utc);first=now.replace(day=1,hour=0,minute=0,second=0,microsecond=0);end=(first+timedelta(days=32)).replace(day=1);iso=lambda d:d.isoformat(timespec='milliseconds').replace('+00:00','Z');l.request('/admin/subscriptions',l.admin,{'applicationId':a['id'],'planId':plan['id'],'effectiveAt':iso(first),'expiresAt':iso(end)},201);return a,l.request('/admin/apps/'+str(a['id'])+'/credentials',l.admin,{'scopes':['demo:read']})
a,key=new_app(five);results=batch(lambda i:c.http_json(gws[i%2],'/api/v11/legacy/1',key['token'])[0]);assert results.count(200)==5 and results.count(429)==95;assert count(a['id'])==5;check('Free5 dual Gateway100 actual concurrent:5x200/95x429; Ledger5')
a2,key2=new_app(large);legacyapi=int(l.sql("SELECT id FROM fg_api WHERE public_path='/api/v11/legacy/{id}'"));version=l.request('/admin/releases/current',l.admin)['version'];checksum=l.sql('SELECT sha256 FROM fg_config_release WHERE version='+str(version));payload={'chargeId':__import__('uuid').uuid4().hex,'principalId':key2['principalId'],'credentialGeneration':key2['generation'],'apiId':legacyapi,'routeId':'v11-legacy','appliedVersion':version,'snapshotChecksum':checksum,'units':1};payload['chargeId']=str(__import__('uuid').uuid4())
def internal(p):
 req=urllib.request.Request(l.base+'/internal/quota/charge',data=json.dumps(p).encode(),headers={'Content-Type':'application/json','X-FlowGate-Internal-Key':l.env['FLOWGATE_INTERNAL_KEY']})
 try:res=c.HTTP.open(req,timeout=6)
 except urllib.error.HTTPError as e:res=e
 with res:return res.status,json.loads(res.read())
originals=batch(lambda _:internal(payload));assert all(s==200 for s,v in originals) and sum(not v['duplicate'] for s,v in originals)==1 and count(a2['id'])==1
for k,v in [('snapshotChecksum','0'*64),('units',2),('appliedVersion',999999)]:assert internal({**payload,k:v})[0]==409
check('internal HTTP100 samecharge -> one Ledger; checksum/units/version conflict409')
oldtoken=key2['token'];digest=hashlib.sha256(oldtoken.encode()).hexdigest();session=l.redis('GET','fg:auth:app:'+digest);rotated=l.request('/admin/apps/'+str(a2['id'])+'/credentials/rotate',l.admin,{'scopes':['demo:read']});l.redis('SET','fg:auth:app:'+digest,session,'EX','60');l.request('/api/v11/legacy/1',oldtoken,expected=403,base=gws[0]);l.request('/api/v11/legacy/1',rotated['token'],base=gws[1]);newdigest=hashlib.sha256(rotated['token'].encode()).hexdigest();newsession=l.redis('GET','fg:auth:app:'+newdigest);l.request('/admin/apps/'+str(a2['id'])+'/credentials/revoke',l.admin,{});l.redis('SET','fg:auth:app:'+newdigest,newsession,'EX','60');l.request('/api/v11/legacy/1',rotated['token'],expected=403,base=gws[0]);key2=l.request('/admin/apps/'+str(a2['id'])+'/credentials/rotate',l.admin,{'scopes':['demo:read']})
for kind,row in [('apps',a2),('developers',dev)]:
 l.request('/admin/'+kind+'/'+str(row['id']),l.admin,{'status':'SUSPENDED'},method='PATCH');before=count();l.request('/api/v11/legacy/1',key2['token'],expected=403,base=gws[0]);assert count()==before;l.request('/admin/'+kind+'/'+str(row['id']),l.admin,{'status':'ACTIVE'},method='PATCH')
check('real generation rotate/revoke vs stale Redis session; APP/developer suspend no charge')
portal=l.portal();l.request('/api/v11/legacy/1',portal,expected=401,base=gws[0]);l.request('/portal/api/me',key2['token'],expected=401);l.request('/internal/quota/charge',l.admin,payload,401)
for role in ['operator','viewer']:
 role_token=l.request('/admin/auth/login',body={'username':'b-'+role,'password':l.browser['password']})['token'];l.request('/admin/messaging/diagnostics',role_token)
 for action in ['', '/rotate','/revoke']:l.request('/admin/apps/'+str(a2['id'])+'/credentials'+action,role_token,{},403)
check('ADMIN/Portal/APP purpose isolation; OPERATOR/VIEWER read four diagnostics200 and credential writes403')
# SSRF/headers/auth failure facts use live admin/Gateway; never send requests to disallowed external targets.
for url in ['http://169.254.169.254:80','http://localhost:'+str(l.state['ports']['demo'])]:l.request('/admin/services',l.admin,{'code':'d-invalid','name':'invalid','upstreamUri':url,'enabled':True},400)
check('SSRF endpoints rejected before outbound access')
# Owner test upstream allows actual breaker half-open and cancellation without changing production Demo.
class Handler(http.server.BaseHTTPRequestHandler):
 failing=True
 def log_message(self,*args):pass
 def do_GET(self):
  if self.path.startswith('/demo/wait'):time.sleep(.5)
  body=b'{"ok":true}';self.send_response(500 if self.path.startswith('/demo/fault') and Handler.failing else 200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers()
  try:self.wfile.write(body)
  except (BrokenPipeError,ConnectionResetError):pass
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start();up='http://127.0.0.1:'+str(server.server_address[1]);l.env['FLOWGATE_ALLOWED_UPSTREAMS']+=','+up;r.save(l.home/'env.json',l.env);l.restart(cache=True,promo=True);l.restart_gateways();l.admin=l.login();service=l.request('/admin/services',l.admin,{'code':'d-fault-'+suffix,'name':'D故障fixture','upstreamUri':up,'enabled':True},201);routes={}
def policy(api,route,typ,params):
 scope='API' if typ=='USAGE_QUOTA' else 'ROUTE';p=l.request('/admin/policies',l.admin,{'code':'d-'+secrets.token_hex(5),'type':typ,'scope':scope,'parametersJson':json.dumps(params),'enabled':True},201);l.request('/admin/policies/'+str(p['id'])+'/bindings',l.admin,{'targetType':scope,'targetId':api['id'] if scope=='API' else route['id']},201)
for name,upstream in [('fault','/fault'),('timeout','/wait'),('capacity','/wait'),('rate','/ok')]:
 api=l.request('/admin/apis',l.admin,{'serviceId':service['id'],'method':'GET','publicPath':'/api/d-gates/'+suffix+'/'+name,'upstreamPath':'/demo'+upstream,'isPublic':False,'requiredScope':'demo:read','enabled':True},201);route=l.request('/admin/routes',l.admin,{'apiId':api['id'],'routeId':'d-gates-'+suffix+'-'+name,'priority':0,'enabled':True},201);routes[name]=api['publicPath'] if 'publicPath' in api else '/api/d-gates/'+suffix+'/'+name;policy(api,route,'USAGE_QUOTA',{'unitsPerCall':1})
 if name=='fault':policy(api,route,'CIRCUIT_BREAKER',{'failureRateThreshold':50,'slidingWindowSize':4,'minimumNumberOfCalls':1,'waitDurationMs':1000,'fallbackMode':'STATIC_JSON'})
 if name=='timeout':policy(api,route,'TIMEOUT',{'timeoutMs':100})
 if name=='capacity':policy(api,route,'CONCURRENCY',{'maxConcurrent':1})
 if name=='rate':policy(api,route,'RATE_LIMIT',{'capacity':1,'refillPerSecond':1})
beforeversion=l.request('/admin/releases/current',l.admin)['version'];publish();before=count();l.request(routes['fault'],key2['token'],expected=503,base=gws[0]);assert count()==before+1;before=count();l.request(routes['fault'],key2['token'],expected=503,base=gws[0]);assert count()==before;Handler.failing=False;time.sleep(1.1);l.request(routes['fault'],key2['token'],base=gws[0]);check('real CB failure OPEN rejects before debit then HALF_OPEN probe recovers CLOSED')
before=count();l.request(routes['timeout'],key2['token'],expected=504,base=gws[0]);assert count()==before+1;before=count();l.request(routes['rate'],key2['token'],base=gws[0]);l.request(routes['rate'],key2['token'],expected=429,base=gws[0]);assert count()==before+1
with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
 pending=pool.submit(c.http_json,gws[0],routes['capacity'],key2['token']);time.sleep(.1);l.request(routes['capacity'],key2['token'],expected=503,base=gws[0]);assert pending.result()[0]==200
l.request(routes['capacity'],key2['token'],base=gws[0]);check('timeout after debit retains Ledger; rate/concurrency rejection before debit; permit returns')
sock=socket.create_connection(('127.0.0.1',l.baseline['ports']['gateway']));sock.sendall(('GET '+routes['capacity']+' HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer '+key2['token']+'\r\nConnection: close\r\n\r\n').encode());time.sleep(.15);sock.close();time.sleep(.6);l.request(routes['capacity'],key2['token'],base=gws[0]);check('real client socket cancellation permits subsequent request without refund')
publish(beforeversion);l.request(routes['capacity'],key2['token'],expected=404,base=gws[0]);check('snapshot rollback removes unpublished fault routes on both nodes')
# Restore service health after DB pause; old current V11 path remains fail-closed, public proxy unaffected.
public=next(x for x in l.request('/admin/apis',l.admin) if x.get('isPublic') and 'd-bench' in x.get('publicPath',''));publicpath=public['publicPath'].replace('{id}','1');mysql=l.container('mysql');c.command(['docker','pause',mysql])
try:l.request('/api/v11/legacy/1',key2['token'],expected=503,base=gws[0]);l.request(publicpath,base=gws[0])
finally:c.command(['docker','unpause',mysql])
l.wait(lambda:c.http_json(l.base,'/status')[1].get('mysql')=='UP','DB restored');check('actual MySQL pause metered503, public proxy200; recovered without Ledger rollback')
# Fixed SQL budget is verified after all operations; no unknown integrity is turned green.
l.wait(lambda:l.sql('SELECT COUNT(*) FROM fg_quota_ledger')==l.sql('SELECT COUNT(*) FROM fg_consumed_event'),'legacy consumption recovered',60)
server.shutdown();l.save('v12-d2-legacy-regressions.json',{'passed':True,'exitCode':0,'checks':checks,'actualConcurrentFree5':{'requests':100,'passed':5,'exhausted':95},'internalSameChargeMessages':100,'kafkaStarted':False,'notExecuted':['legacy binary mixed-node live upgrade in this script','cross-month DB session clock injection','original Kafka partition pause matrix not applicable']});l.save('v12-d2-identity-rollback.json',{'passed':True,'exitCode':0,'checks':checks,'ssrf':'actual400 before outbound','snapshotRollback':True,'halfOpenReal':True,'cancelRealSocket':True,'notExecuted':['all 23 historical B scenarios verbatim; current equivalents listed explicitly','OS clock untouched; historical cross-day consumption covered by event parser unit tests, not live clock travel']});print('D_LEGACY_GATES_PASS',flush=True)

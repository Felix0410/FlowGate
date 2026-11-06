'use strict';
// 本页是薄 HTTP 客户端；所有授权、校验和版本判断以真实后端响应为准。
const el = id => document.getElementById(id);
let adminToken = '', currentRole = '';
const recent = [];
const busyButtons=new Set();
let context = '操作';
let published=null, nodeStates=[], dependencyState=null;
function summaryStatus() {
 const dependencies=dependencyState ? Object.entries(dependencyState.Control及依赖||{}).map(([name,value])=>name+' '+value).join(' / ')+' / Gateway '+(dependencyState.Gateway?.status||'未知') : '环境未检查';
 const applied=nodeStates.length?nodeStates.map(n=>n.nodeId+' 已应用V'+n.appliedVersion+' '+(n.freshness||'')).join('；'):'节点尚未读取';
 el('global-status').textContent=dependencies+' · '+(published?'已发布V'+published.version:'版本未读取')+' · '+applied+' · '+new Date().toLocaleTimeString();
 if(published) el('policy-status').textContent='已发布V'+published.version+'：'+((published.policyStates||[]).map(p=>p.code+' '+(p.enabled?'启用':'停用')+' / '+(String(p.executionStatus).startsWith('ENFORCED')?'发布规则':'历史待执行')).join('；')||'尚无策略')+'。只有节点应用对应版本后才执行。';
}
const mask = value => Array.isArray(value) ? value.map(mask) : value && typeof value === 'object'
  ? Object.fromEntries(Object.entries(value).map(([k, v]) => [k, /token|password|internal.?key/i.test(k) ? '（已遮罩）' : mask(v)])) : value;

/** 请求统一显示实际状态/耗时/ID，凭证不进入结果日志或持久化存储。 */
async function call(path, {method = 'GET', body, admin = false, gateway = false, caller = false, headers = {}} = {}) {
  // 1. 凭证只从当前页面内存加入请求头，不放进 URL、Cookie 或持久化存储。
  const started = performance.now(), operation = context;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (admin) headers.Authorization = `Bearer ${adminToken}`;
  if (caller && el('caller-token').value) headers.Authorization = `Bearer ${el('caller-token').value}`;
  try {
    const base = gateway ? el('gateway').value.replace(/\/$/, '') : '';
    if (gateway && !/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw new Error('Gateway 仅允许本机回环地址');
    const response = await fetch(base + path, {method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'omit'});
    // 2. 保留真实 HTTP 状态和响应，输出前遮罩凭证，不能用固定示例冒充成功。
    const text = await response.text();
    let data; try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    el('result-meta').textContent = `${method} ${path} · HTTP ${response.status} · ${(performance.now() - started).toFixed(1)} ms · Request ID: ${response.headers.get('X-Request-Id') || '无'}`;
    el('result').textContent = JSON.stringify(mask(data), null, 2) ?? '(无响应体)';
    showResult(operation, {method, path: base + path, status: response.status, ms: performance.now()-started, requestId:response.headers.get('X-Request-Id'), body:mask(data)});
    if(admin && response.status===401) {adminToken='';currentRole='';state();}
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${data?.message || '请求被拒绝'}`);
    return data;
  } catch (error) {
    // 3. 区分 HTTP 拒绝和网络不可达，错误仍展示在页面供手动核对。
    if (!error.message.startsWith('HTTP ')) el('result-meta').textContent = `请求失败 / 依赖未就绪：${error.message}`;
    throw error;
  }
}
function action(id, fn) { el(id).addEventListener('click', async () => {
  const button=el(id), name=button.textContent; context=id; busyButtons.add(id); button.disabled=true; button.textContent='执行中…';
  try { await fn(); } catch(error) { if(!error.message.startsWith('HTTP ')) showResult(id,{error:error.message}); }
  finally { button.textContent=name; busyButtons.delete(id); button.disabled=false; state(); }
}); }
/** 每项操作保留独立响应卡；最近十条仅保留无凭证的状态摘要。 */
function showResult(id, result) {
  let card=el('response-'+id);
  if(!card) { card=document.createElement('pre'); card.id='response-'+id; card.setAttribute('aria-live','polite');
    const details=document.createElement('details'),summary=document.createElement('summary'); details.append(summary);details.append(card); card.summary=summary; (el(id)?.closest('section') || el('quick')).append(details); }
  const explanations={400:'输入错误',401:'请登录/凭证过期',403:'权限不足',404:'路由或资源不存在',409:'版本已变化，请先重新读取',429:'速率预算耗尽',503:'并发过载或依赖不可用',504:'上游响应超时'};
  if(card.summary) card.summary.textContent=result.error || (result.status ? 'HTTP '+result.status+' · '+(result.body?.message || explanations[result.status] || (result.status>=400?'请求未成功':'操作成功'))+' · '+Math.round(result.ms||0)+'ms（展开详情）' : result.message || '操作结果（展开详情）');
  card.textContent=JSON.stringify({...result, explanation:explanations[result.status] || ''},null,2);
  recent.unshift({operation:id,status:result.status,error:result.error,ms:result.ms,requestId:result.requestId}); recent.splice(10);
  el('result').textContent=JSON.stringify(recent,null,2);
}

function state() {
  el('role').textContent = adminToken ? `当前角色 ${currentRole}；会话固定 30 分钟，刷新后清空。` : '尚未登录';
  document.querySelectorAll('[data-admin]').forEach(button => {
    button.disabled = busyButtons.has(button.id) || !adminToken || (button.dataset.role && button.dataset.role !== currentRole) || (button.hasAttribute('data-write') && currentRole === 'VIEWER');
    button.title=button.disabled ? '请先登录具备相应权限的账号' : '';
  });
}
function clear() { adminToken = ''; currentRole = ''; el('password').value = ''; el('caller-token').value = ''; el('issued-token').value = ''; state(); }
el('login-form').addEventListener('submit', async event => {
  event.preventDefault();
  try {
    const credentials = {username: el('username').value, password: el('password').value};
    el('password').value = '';
    const result = await call('/admin/auth/login', {method:'POST', body:credentials});
    adminToken = result.token; currentRole = result.role; state(); await loadVersion();
  } catch { clear(); }
});
action('logout', async () => { try { await call('/admin/auth/logout', {method:'POST', admin:true}); } finally { clear(); } });
action('clear-caller', () => { el('caller-token').value = ''; el('issued-token').value = ''; });
action('show-token', () => { el('issued-token').type = el('issued-token').type === 'password' ? 'text' : 'password'; });
action('probe', async () => {
  const control = await call('/status').catch(() => ({control:'无法访问'}));
  const gateway = await call('/actuator/health', {gateway:true}).catch(() => ({status:'无法访问'}));
  dependencyState={Control及依赖:control,Gateway:gateway}; el('health').textContent=JSON.stringify(dependencyState,null,2); summaryStatus();
});
action('fast', () => call(`/api/demo/fast/${encodeURIComponent(el('fast-id').value)}?tag=${encodeURIComponent(el('tag').value)}`, {gateway:true}));
action('echo', () => call('/api/demo/echo', {gateway:true, method:'POST', body:JSON.parse(el('echo-body').value), headers:{'X-Demo-Header':el('demo-header').value}}));
action('slow', () => call(`/api/demo/slow?delayMs=${encodeURIComponent(el('delay').value)}`, {gateway:true}));
action('metrics', () => call('/actuator/metrics/spring.cloud.gateway.requests', {gateway:true}));
action('private', () => { const path = el('private-path').value; if (!path.startsWith('/api/')) throw new Error('只允许 /api/'); return call(path, {gateway:true, caller:true}); });
action('create-caller', async () => {
  if(!confirm('创建 Caller '+el('caller-name').value+'，Scopes='+el('scopes').value+'？凭证只签发一次。')) return;
  const result = await call('/admin/callers', {admin:true, method:'POST', body:{username:el('caller-name').value, scopes:el('scopes').value.split(',').map(x => x.trim()).filter(Boolean)}});
  el('issued-token').value = result.token; el('issued-token').type = 'password'; el('caller-token').value = result.token; el('revoke-id').value = result.id;
  el('result-meta').textContent += ` · Caller ID ${result.id}，${result.expiresInSeconds} 秒后到期`;
});
action('revoke', async () => { if(!confirm('确认吊销此 Caller 的当前凭证？')) return; await call(`/admin/callers/${Number(el('revoke-id').value)}/revoke`, {admin:true, method:'POST'}); el('caller-token').value=''; el('issued-token').value=''; });
action('list', () => call(`/admin/${el('kind').value}`, {admin:true}));
action('save', () => call(`/admin/${el('kind').value}${el('entity-id').value ? '/' + Number(el('entity-id').value) : ''}`, {admin:true, method:el('entity-id').value ? 'PUT' : 'POST', body:JSON.parse(el('entity-body').value)}));
async function loadVersion() { const current=await call('/admin/releases/current',{admin:true}); el('expected').value=current.version;
 published=current; summaryStatus(); el('version-status').textContent='持久化 '+current.status+' V'+current.version+'；节点应用状态请点击查询节点确认'; return current; }
action('publish', async () => { if(el('expected').value==='') throw new Error('请先读取当前版本');
 if(!confirm('发布完整草稿，expectedVersion='+el('expected').value+'？发布持久化不等于节点应用。')) return;
 await call('/admin/releases/publish',{admin:true,method:'POST',body:{expectedVersion:Number(el('expected').value)}}); await loadVersion(); });
action('rollback', () => { if(!confirm('从所选历史生成新版本，不撤销草稿；确认回滚？')) return; return call(`/admin/releases/${Number(el('rollback-version').value)}/rollback`, {admin:true, method:'POST', body:{expectedVersion:Number(el('expected').value)}}).then(loadVersion); });
action('current', loadVersion);
for(const [id,path] of Object.entries({history:'releases',nodes:'nodes',audits:'audits'})) action(id,async()=>{
 const result=await call(`/admin/${path}`,{admin:true}); if(id==='nodes') {nodeStates=result;summaryStatus();} if(id==='nodes') el('version-status').textContent='PERSISTED V'+el('expected').value+'；节点 '+result.map(n=>n.nodeId+' 已应用V'+n.appliedVersion+' '+(n.freshness||'')).join('；'); return result; });
action('bind', () => call(`/admin/policies/${Number(el('policy-id').value)}/bindings`, {admin:true, method:'POST', body:JSON.parse(el('binding').value)}));

/** 初始化仅补缺失草稿，不发布、不覆盖已有规则；每一步都通过正式管理 API。 */
async function seed(privateOnly) {
  // 1. 查询或创建受控 Demo 服务，不从来访请求中构造上游目标。
  let created=0, existing=0;
  const services = await call('/admin/services', {admin:true});
  if(services.some(s=>s.code==='demo')) existing++; else created++;
  const service = services.find(s => s.code === 'demo') || await call('/admin/services', {admin:true, method:'POST', body:{code:'demo', name:'本地 Demo', upstreamUri:'http://127.0.0.1:8085', enabled:true}});
  const entries = privateOnly ? [['private-fast','GET','/api/private/fast/{id}','/demo/fast/{id}',false]] : [
    ['demo-fast','GET','/api/demo/fast/{id}','/demo/fast/{id}',true], ['demo-echo','POST','/api/demo/echo','/demo/echo',true], ['demo-slow','GET','/api/demo/slow','/demo/slow',true]];
  const apis = await call('/admin/apis', {admin:true}), routes = await call('/admin/routes', {admin:true});
  // 2. 逐条补齐固定 API 和路由，不提供静态旁路；生效仍需管理员显式发布。
  for (const [routeId, method, publicPath, upstreamPath, isPublic] of entries) {
    if(apis.some(a=>a.method===method && a.publicPath===publicPath)) existing++; else created++;
    const api = apis.find(a => a.method === method && a.publicPath === publicPath) || await call('/admin/apis', {admin:true, method:'POST', body:{serviceId:service.id, method, publicPath, upstreamPath, isPublic, requiredScope:isPublic ? '' : 'demo:read', enabled:true}});
    if(routes.some(r=>r.apiId===api.id)) existing++; else created++;
    if (!routes.some(r => r.apiId === api.id)) await call('/admin/routes', {admin:true, method:'POST', body:{apiId:api.id, routeId, priority:0, enabled:true}});
  }
  // 3. 回读当前持久化版本，用户可据此发布并另行查看节点应用版本。
  const current = await loadVersion();
  showResult(privateOnly?'seed-private':'seed',{created,existing,message:'只补缺失，未发布',version:current.version});
}
action('seed', () => confirm('只补缺失公共 Demo，不覆盖、不发布。继续？') && seed(false)); action('seed-private', () => seed(true));
state();

// 1. 保留既有全部操作，按业务分区；首屏只显示入门流程。
const panels=[...document.querySelectorAll('main > section')];
const names=['业务概览','环境状态','接口调用','账号与调用方','服务/API与发布','流量策略','故障保护','帮助','最近操作'];
function openPanel(id) { panels.forEach(p=>p.hidden=p.id!==id); document.querySelectorAll('#tabs button').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.panel===id))); }
panels.forEach((panel,i)=>{ if(!panel.id) panel.id='panel-'+(i-1); const b=document.createElement('button'); b.textContent=names[i]; b.dataset.panel=panel.id; b.onclick=()=>openPanel(panel.id); el('tabs').append(b); });
document.querySelectorAll('[data-open]').forEach(b=>b.onclick=()=>openPanel(b.dataset.open));
openPanel('quick');

let policyCatalog=[], targetCatalog={};
function targets() {
 const scope=el('policy-scope').value;
 const values=scope==='GLOBAL' ? [{id:0,label:'共享全局预算'}] : (targetCatalog[scope] || []);
 el('policy-target').replaceChildren(...values.map(v=>{const option=document.createElement('option'); option.value=v.id; option.textContent=v.label || v.code || v.routeId || v.publicPath || String(v.id); return option;}));
}
/** 页面只展示当前规则需要的参数，避免把每秒速率与在途并发混填。 */
function trafficFields() {
 const rate=el('policy-type').value==='RATE_LIMIT';
 el('capacity').closest('label').hidden=!rate;el('refill').closest('label').hidden=!rate;
 el('max-concurrent').closest('label').hidden=rate;
 el('traffic-guidance').textContent=rate?'当前填写每秒速率：容量控制短时额度，每秒补充控制持续速率。推荐ROUTE / demo-slow。':'当前填写本机同时处理数量：只填maxConcurrent，只支持SERVICE或ROUTE，不是每秒次数。';
}
el('policy-type').onchange=trafficFields;
el('policy-scope').onchange=targets;
el('policy-existing').onchange=()=>{
 const policy=policyCatalog.find(p=>p.id===Number(el('policy-existing').value)); el('policy-type').disabled=Boolean(policy); el('policy-scope').disabled=Boolean(policy);
 el('policy-target').disabled=Boolean(policy);
 if(policy) el('policy-status').textContent='编辑只修改参数/启停；绑定关系保持不变。如需新增绑定，请使用下方单独绑定入口。';
 if(!policy) { targets(); trafficFields(); return; }
 el('policy-code').value=policy.code; el('policy-type').value=policy.type; el('policy-scope').value=policy.scope; el('policy-enabled').value=String(policy.enabled);
 const params=JSON.parse(policy.parametersJson); el('capacity').value=params.capacity || 5; el('refill').value=params.refillPerSecond || 2; el('max-concurrent').value=params.maxConcurrent || 2; targets(); trafficFields(); el('policy-target').replaceChildren(new Option('已有绑定保持不变（此处不修改目标）','unchanged'));
};
action('policy-load',async()=>{
 policyCatalog=await call('/admin/policies',{admin:true});
 for(const [scope,path] of Object.entries({SERVICE:'services',API:'apis',ROUTE:'routes'})) targetCatalog[scope]=await call('/admin/'+path,{admin:true});
 el('policy-existing').replaceChildren(new Option('新建',''),...policyCatalog.filter(p=>['RATE_LIMIT','CONCURRENCY'].includes(p.type)).map(p=>new Option(p.code,String(p.id))));
 el('policy-type').disabled=false;el('policy-scope').disabled=false;el('policy-target').disabled=false;
 targets(); trafficFields(); await loadVersion();
});
action('policy-save',async()=>{
 const type=el('policy-type').value,scope=el('policy-scope').value;
 if(type==='CONCURRENCY' && !['SERVICE','ROUTE'].includes(scope)) throw new Error('本机并发只支持SERVICE/ROUTE');
 let target=el('policy-target').value;
 if(!el('policy-existing').value && scope==='CALLER') target=prompt('输入已启用 Caller 的内部数字 ID（不是Token）');
 if(target===null || target==='') throw new Error('请读取并选择绑定目标');
 const params=type==='RATE_LIMIT'?{capacity:Number(el('capacity').value),refillPerSecond:Number(el('refill').value)}:{maxConcurrent:Number(el('max-concurrent').value)};
 const id=el('policy-existing').value;
 const policy=await call('/admin/policies'+(id?'/'+Number(id):''),{admin:true,method:id?'PUT':'POST',body:{code:el('policy-code').value,type,scope,parametersJson:JSON.stringify(params),enabled:el('policy-enabled').value==='true'}});
 // 1. 新建时绑定；已有策略只更新参数，不重复创建绑定，避免冲突发布。
 if(!id) await call('/admin/policies/'+policy.id+'/bindings',{admin:true,method:'POST',body:{targetType:scope,targetId:Number(target)}});
 el('policy-status').textContent='已保存草稿 ID '+policy.id+'，尚未发布。请去配置页发布并确认节点应用。';
});
action('batch',async()=>{
 const size=Number(el('batch-size').value),path=el('batch-path').value;
 if(!Number.isInteger(size)||size<1||size>20||!path.startsWith('/api/')) throw new Error('仅允许1..20个/api/请求');
 // 2. 有界请求只展示真实结果，不声称浏览器小测试证明隔离性能。
 const results=await Promise.allSettled(Array.from({length:size},()=>call(path,{gateway:true,caller:true})));
 showResult('batch',{total:size,success:results.filter(r=>r.status==='fulfilled').length,rejected:results.filter(r=>r.status==='rejected').length});
});
action('admission-metrics',()=>call('/actuator/metrics/flowgate.admission.requests',{gateway:true}));
action('template',async()=>{
 const kind=el('template-kind').value,code=el('template-code').value,parent=Number(el('template-parent').value);
 const body=kind==='services'?{code,name:code,upstreamUri:'http://127.0.0.1:8085',enabled:true}:kind==='apis'?{serviceId:parent,method:el('template-method').value,publicPath:el('template-public').value,upstreamPath:el('template-upstream').value,isPublic:true,requiredScope:'',enabled:true}:{apiId:parent,routeId:code,priority:0,enabled:true};
 el('kind').value=kind; el('entity-body').value=JSON.stringify(body,null,2);
 await call('/admin/'+kind,{admin:true,method:'POST',body});
 const card=el('response-template');if(card?.summary) card.summary.textContent+=' · 草稿已保存，尚未发布';
});

// 1. 业务概览展示真实目录，避免要求用户先理解内部JSON与测试矩阵。
action('business-load',async()=>{
 const services=await call('/admin/services',{admin:true}),apis=await call('/admin/apis',{admin:true}),routes=await call('/admin/routes',{admin:true});
 const view=el('business-list');view.replaceChildren();
 for(const api of apis) { const service=services.find(s=>s.id===api.serviceId),route=routes.find(r=>r.apiId===api.id);
  const row=document.createElement('p');row.textContent=`${service?.name||'未知服务'} · ${api.method} ${api.publicPath} · ${api.isPublic?'公开':'需调用方授权'} · ${api.enabled&&route?.enabled?'草稿启用':'未启用/未配路由'}`;view.append(row); }
 if(!apis.length) view.textContent='尚无 API，可在服务/API页补齐公共Demo草稿，然后发布。';
 await loadVersion();
});
let protectionPolicies=[],protectionRoutes=[];
function protectionFields() { const cb=el('protection-type').value==='CIRCUIT_BREAKER';el('timeout-fields').hidden=cb;el('circuit-fields').hidden=!cb; }
el('protection-type').onchange=protectionFields;
el('protection-existing').onchange=()=>{
 const policy=protectionPolicies.find(p=>p.id===Number(el('protection-existing').value));
 el('protection-route').disabled=Boolean(policy);el('protection-type').disabled=Boolean(policy);
 if(!policy) { el('protection-route').replaceChildren(...protectionRoutes.map(r=>new Option(r.routeId,String(r.id))));return; }
 el('protection-route').replaceChildren(new Option('已有绑定保持不变','unchanged'));
 el('protection-state').textContent='编辑仅修改参数/启停，已有目标不变；保存后仍须发布。';
 el('protection-code').value=policy.code;el('protection-type').value=policy.type;el('protection-enabled').value=String(policy.enabled);
 const params=JSON.parse(policy.parametersJson);
 for(const [id,key] of Object.entries({'timeout-ms':'timeoutMs','failure-rate':'failureRateThreshold','window-size':'slidingWindowSize','minimum-calls':'minimumNumberOfCalls','wait-ms':'waitDurationMs'})) if(params[key]!==undefined) el(id).value=params[key];
 el('fallback-mode').value=params.fallbackMode||'NONE';protectionFields();
};
action('protection-load',async()=>{
 protectionRoutes=await call('/admin/routes',{admin:true});protectionPolicies=(await call('/admin/policies',{admin:true})).filter(p=>['TIMEOUT','CIRCUIT_BREAKER'].includes(p.type));
 el('protection-existing').replaceChildren(new Option('新建',''),...protectionPolicies.map(p=>new Option(p.code,String(p.id))));
 el('protection-route').replaceChildren(...protectionRoutes.map(r=>new Option(r.routeId,String(r.id))));
 el('protection-route').disabled=false;el('protection-type').disabled=false;await loadVersion();
});
action('protection-save',async()=>{
 const type=el('protection-type').value,id=el('protection-existing').value;
 const params=type==='TIMEOUT'?{timeoutMs:Number(el('timeout-ms').value)}:{failureRateThreshold:Number(el('failure-rate').value),slidingWindowSize:Number(el('window-size').value),minimumNumberOfCalls:Number(el('minimum-calls').value),waitDurationMs:Number(el('wait-ms').value),fallbackMode:el('fallback-mode').value};
 // 2. 客户端只组织原业务API参数，范围/只读降级最终由Control和Gateway共同验证。
 const policy=await call('/admin/policies'+(id?'/'+Number(id):''),{admin:true,method:id?'PUT':'POST',body:{code:el('protection-code').value,type,scope:'ROUTE',parametersJson:JSON.stringify(params),enabled:el('protection-enabled').value==='true'}});
 if(!id) {if(!el('protection-route').value) throw new Error('策略已保存但尚未绑定，请选择目标后使用绑定入口'); await call('/admin/policies/'+policy.id+'/bindings',{admin:true,method:'POST',body:{targetType:'ROUTE',targetId:Number(el('protection-route').value)}});}
 el('protection-state').textContent='保护草稿已保存，尚未生效；请发布并查看节点应用版本。';
});
action('protection-publish',async()=>{
 const current=await loadVersion();if(!confirm('发布当前全部草稿（版本'+current.version+'→'+(current.version+1)+'）？')) return;
 await call('/admin/releases/publish',{admin:true,method:'POST',body:{expectedVersion:current.version}});await loadVersion();
 el('protection-state').textContent='已持久化；尚需查询节点确认实际应用。';
});
action('protection-status',async()=>{
 const current=await loadVersion(),nodes=await call('/admin/nodes',{admin:true});nodeStates=nodes;summaryStatus();
 el('protection-state').textContent=nodes.length?nodes.map(n=>`${n.nodeId}：${n.freshness||'未知'}，已应用V${n.appliedVersion}${n.appliedVersion===current.version?'（当前版本）':'（等待追平）'}；${n.runtimeStateKnown?(n.circuitStates||[]).map(s=>s.routeId+' '+s.state).join('；')||'该版本无熔断规则':'熔断状态未取得'}；最后上报 ${n.lastSeenAt}`).join('\n'):'未取得节点状态';
});
action('fault-init',async()=>{
 await seed(false);const services=await call('/admin/services',{admin:true}),apis=await call('/admin/apis',{admin:true}),routes=await call('/admin/routes',{admin:true});
 const service=services.find(s=>s.code==='demo');
 const api=apis.find(a=>a.method==='GET'&&a.publicPath==='/api/demo/fault')||await call('/admin/apis',{admin:true,method:'POST',body:{serviceId:service.id,method:'GET',publicPath:'/api/demo/fault',upstreamPath:'/demo/fault',isPublic:true,requiredScope:'',enabled:true}});
 if(!routes.some(r=>r.apiId===api.id)) await call('/admin/routes',{admin:true,method:'POST',body:{apiId:api.id,routeId:'demo-fault',priority:0,enabled:true}});
 el('protection-state').textContent='已补齐故障API草稿，未发布；Demo需显式开启本机故障演示配置。';
});
for(const [id,mode] of Object.entries({'fault-ok':'ok','fault-500':'500','fault-503':'503','fault-slow':'slow'})) action(id,()=>{
 const path=el('fault-path').value;if(!path.startsWith('/api/')||path.includes('?')) throw new Error('请填写不带Query的/api/路径');return call(path+'?mode='+mode,{gateway:true,caller:true});
});
action('timeout-check',()=>call('/api/demo/slow?delayMs=500',{gateway:true}));
action('protection-metrics',()=>call('/actuator/metrics/flowgate.protection.events',{gateway:true}));
protectionFields();

let catalogServices=[],catalogApis=[];
function catalogParents() {
 const kind=el('template-kind').value||'services',rows=kind==='apis'?catalogServices:kind==='routes'?catalogApis:[];
 el('template-parent-select').replaceChildren(...rows.map(row=>new Option(row.name || row.method+' '+row.publicPath,String(row.id))));
 // 1. 不同目录只显示会真正保存的字段，隐藏字段不能让用户误以为会生效。
 el('template-code').closest('label').hidden=kind==='apis';
 el('template-parent-select').closest('label').hidden=kind==='services';
 for(const id of ['template-method','template-public','template-upstream']) el(id).closest('label').hidden=kind!=='apis';
 el('template-guidance').textContent=kind==='services'?'创建服务：只填唯一代码；名称与代码相同，当前表单上游固定为本地Demo。已有demo时不必重复创建。':kind==='apis'?'创建公开API：先读取并选择服务，填写方法、/api/入口与/demo/下游路径；保存后还要另建关联路由。':'创建路由：先重新读取并选择API，填写唯一routeId；方法和路径直接使用关联API，保存后仍需发布。';
 el('template-parent-select').disabled=kind==='services';el('template-parent').value=el('template-parent-select').value;
}
el('template-kind').onchange=catalogParents;
el('template-parent-select').onchange=()=>el('template-parent').value=el('template-parent-select').value;
action('catalog-load',async()=>{
 catalogServices=await call('/admin/services',{admin:true});catalogApis=await call('/admin/apis',{admin:true});catalogParents();
 showResult('catalog-load',{message:'已读取 '+catalogServices.length+' 个服务、'+catalogApis.length+' 个API，可直接选择关联对象'});
});

// 2. 初次进入即收起无关字段；后续选择模板/类型时由同一说明更新。
catalogParents();
trafficFields();

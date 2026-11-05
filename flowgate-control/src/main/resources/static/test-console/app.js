'use strict';
// 本页是薄 HTTP 客户端；所有授权、校验和版本判断以真实后端响应为准。
const el = id => document.getElementById(id);
let adminToken = '', currentRole = '';
const mask = value => Array.isArray(value) ? value.map(mask) : value && typeof value === 'object'
  ? Object.fromEntries(Object.entries(value).map(([k, v]) => [k, /token|password|internal.?key/i.test(k) ? '（已遮罩）' : mask(v)])) : value;

/** 请求统一显示实际状态/耗时/ID，凭证不进入结果日志或持久化存储。 */
async function call(path, {method = 'GET', body, admin = false, gateway = false, caller = false, headers = {}} = {}) {
  // 1. 凭证只从当前页面内存加入请求头，不放进 URL、Cookie 或持久化存储。
  const started = performance.now();
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
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${data?.message || '请求被拒绝'}`);
    return data;
  } catch (error) {
    // 3. 区分 HTTP 拒绝和网络不可达，错误仍展示在页面供手动核对。
    if (!error.message.startsWith('HTTP ')) el('result-meta').textContent = `请求失败 / 依赖未就绪：${error.message}`;
    throw error;
  }
}
function action(id, fn) { el(id).addEventListener('click', () => Promise.resolve().then(fn).catch(error => {
  if (!error.message.startsWith('HTTP ')) el('result-meta').textContent = `操作失败：${error.message}`;
})); }
function state() {
  el('role').textContent = adminToken ? `当前角色 ${currentRole}；会话固定 30 分钟，刷新后清空。` : '尚未登录';
  document.querySelectorAll('[data-admin]').forEach(button => {
    button.disabled = !adminToken || (button.dataset.role && button.dataset.role !== currentRole) || (button.hasAttribute('data-write') && currentRole === 'VIEWER');
  });
}
function clear() { adminToken = ''; currentRole = ''; el('password').value = ''; el('caller-token').value = ''; el('issued-token').value = ''; state(); }
el('login-form').addEventListener('submit', async event => {
  event.preventDefault();
  try {
    const credentials = {username: el('username').value, password: el('password').value};
    el('password').value = '';
    const result = await call('/admin/auth/login', {method:'POST', body:credentials});
    adminToken = result.token; currentRole = result.role; state();
  } catch { clear(); }
});
action('logout', async () => { try { await call('/admin/auth/logout', {method:'POST', admin:true}); } finally { clear(); } });
action('clear-caller', () => { el('caller-token').value = ''; el('issued-token').value = ''; });
action('show-token', () => { el('issued-token').type = el('issued-token').type === 'password' ? 'text' : 'password'; });
action('probe', async () => {
  const control = await call('/status').catch(() => ({control:'无法访问'}));
  const gateway = await call('/actuator/health', {gateway:true}).catch(() => ({status:'无法访问'}));
  el('health').textContent = JSON.stringify({Control及依赖:control, Gateway:gateway}, null, 2);
});
action('fast', () => call(`/api/demo/fast/${encodeURIComponent(el('fast-id').value)}?tag=${encodeURIComponent(el('tag').value)}`, {gateway:true}));
action('echo', () => call('/api/demo/echo', {gateway:true, method:'POST', body:JSON.parse(el('echo-body').value), headers:{'X-Demo-Header':el('demo-header').value}}));
action('slow', () => call(`/api/demo/slow?delayMs=${encodeURIComponent(el('delay').value)}`, {gateway:true}));
action('metrics', () => call('/actuator/metrics/spring.cloud.gateway.requests', {gateway:true}));
action('private', () => { const path = el('private-path').value; if (!path.startsWith('/api/')) throw new Error('只允许 /api/'); return call(path, {gateway:true, caller:true}); });
action('create-caller', async () => {
  const result = await call('/admin/callers', {admin:true, method:'POST', body:{username:el('caller-name').value, scopes:el('scopes').value.split(',').map(x => x.trim()).filter(Boolean)}});
  el('issued-token').value = result.token; el('issued-token').type = 'password'; el('caller-token').value = result.token; el('revoke-id').value = result.id;
  el('result-meta').textContent += ` · Caller ID ${result.id}，${result.expiresInSeconds} 秒后到期`;
});
action('revoke', async () => { await call(`/admin/callers/${Number(el('revoke-id').value)}/revoke`, {admin:true, method:'POST'}); el('caller-token').value=''; el('issued-token').value=''; });
action('list', () => call(`/admin/${el('kind').value}`, {admin:true}));
action('save', () => call(`/admin/${el('kind').value}${el('entity-id').value ? '/' + Number(el('entity-id').value) : ''}`, {admin:true, method:el('entity-id').value ? 'PUT' : 'POST', body:JSON.parse(el('entity-body').value)}));
action('publish', () => call('/admin/releases/publish', {admin:true, method:'POST', body:{expectedVersion:Number(el('expected').value)}}));
action('rollback', () => call(`/admin/releases/${Number(el('rollback-version').value)}/rollback`, {admin:true, method:'POST', body:{expectedVersion:Number(el('expected').value)}}));
for (const [id, path] of Object.entries({current:'releases/current', history:'releases', nodes:'nodes', audits:'audits'})) action(id, () => call(`/admin/${path}`, {admin:true}));
action('bind', () => call(`/admin/policies/${Number(el('policy-id').value)}/bindings`, {admin:true, method:'POST', body:JSON.parse(el('binding').value)}));

/** 初始化仅补缺失草稿，不发布、不覆盖已有规则；每一步都通过正式管理 API。 */
async function seed(privateOnly) {
  // 1. 查询或创建受控 Demo 服务，不从来访请求中构造上游目标。
  const services = await call('/admin/services', {admin:true});
  const service = services.find(s => s.code === 'demo') || await call('/admin/services', {admin:true, method:'POST', body:{code:'demo', name:'本地 Demo', upstreamUri:'http://127.0.0.1:8085', enabled:true}});
  const entries = privateOnly ? [['private-fast','GET','/api/private/fast/{id}','/demo/fast/{id}',false]] : [
    ['demo-fast','GET','/api/demo/fast/{id}','/demo/fast/{id}',true], ['demo-echo','POST','/api/demo/echo','/demo/echo',true], ['demo-slow','GET','/api/demo/slow','/demo/slow',true]];
  const apis = await call('/admin/apis', {admin:true}), routes = await call('/admin/routes', {admin:true});
  // 2. 逐条补齐固定 API 和路由，不提供静态旁路；生效仍需管理员显式发布。
  for (const [routeId, method, publicPath, upstreamPath, isPublic] of entries) {
    const api = apis.find(a => a.method === method && a.publicPath === publicPath) || await call('/admin/apis', {admin:true, method:'POST', body:{serviceId:service.id, method, publicPath, upstreamPath, isPublic, requiredScope:isPublic ? '' : 'demo:read', enabled:true}});
    if (!routes.some(r => r.apiId === api.id)) await call('/admin/routes', {admin:true, method:'POST', body:{apiId:api.id, routeId, priority:0, enabled:true}});
  }
  // 3. 回读当前持久化版本，用户可据此发布并另行查看节点应用版本。
  const current = await call('/admin/releases/current', {admin:true}); el('expected').value = current.version;
}
action('seed', () => seed(false)); action('seed-private', () => seed(true));
state();

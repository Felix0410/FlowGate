#!/usr/bin/env node
/** 运行真实产品ES模块与模拟DOM事件；不将此契约测试冒充浏览器布局/人工体验。 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createApi, redact} from '../flowgate-control/src/main/resources/static/console/api.js';
import {saveCatalog, catalogBody} from '../flowgate-control/src/main/resources/static/console/catalog.js';
import {savePolicy, policyParameters} from '../flowgate-control/src/main/resources/static/console/governance.js';
import {publishConfiguration, nodeState, createCaller, revokeCaller} from '../flowgate-control/src/main/resources/static/console/operations.js';
import {start, readCore, versionLabel} from '../flowgate-control/src/main/resources/static/console/app.js';

const response = (status, data) => ({status, ok: status >= 200 && status < 300, headers: {get: () => 'request-test'}, text: async () => data === null ? '' : JSON.stringify(data)});
let role = 'ADMIN', version = 2, requests = [], failNext = 0;
const fixtures = {
  services: [{id: 1, code: 'demo', name: '<img src=x onerror=alert(1)>', upstreamUri: 'http://127.0.0.1:8085', enabled: true}],
  apis: [{id: 2, serviceId: 1, method: 'GET', publicPath: '/api/demo/fast/{id}', upstreamPath: '/demo/fast/{id}', isPublic: false, requiredScope: 'demo:read', enabled: true}],
  routes: [{id: 3, apiId: 2, routeId: 'demo-fast', priority: 0, enabled: true}], policies: []
};
async function transport(path, options) {
  const body = options.body ? JSON.parse(options.body) : undefined; requests.push({path, ...options, body});
  if (failNext) { const status = failNext; failNext = 0; return response(status, {message: '版本冲突', code: 'VERSION_CONFLICT'}); }
  if (path === '/admin/auth/login') return response(200, {token: 'admin-memory-only', role});
  if (path === '/admin/auth/logout') return response(204, null);
  if (path === '/status') return response(200, {control: 'UP', mysql: 'UP', redis: 'UP', demo: 'UP'});
  if (path === '/admin/releases/current') return response(200, {version, status: 'PERSISTED'});
  if (path === '/admin/nodes') return response(200, [{nodeId: 'local', appliedVersion: version, freshness: 'ONLINE', circuitStates: []}]);
  if (path === '/admin/releases') return response(200, [{version, status: 'PERSISTED', createdAt: 'now'}]);
  if (path === '/admin/releases/publish' || /\/rollback$/.test(path)) { version++; return response(200, {version, status: 'PERSISTED'}); }
  if (path === '/admin/callers') return response(201, {id: 8, token: 'caller-once-only', expiresInSeconds: 3600});
  if (/\/revoke$/.test(path)) return response(204, null);
  if (path.startsWith('/admin/audits?')) return response(200, []);
  if (path === 'http://127.0.0.1:8080/actuator/health') return response(200, {status: 'UP'});
  if (path.startsWith('http://127.0.0.1:8080/')) return response(404, {});
  if (/\/bindings$/.test(path)) return response(options.method === 'POST' ? 201 : 200, options.method === 'POST' ? {id: 7} : []);
  const match = path.match(/^\/admin\/(services|apis|routes|policies)(?:\/(\d+))?$/);
  if (match) {
    const [, kind, id] = match;
    if (options.method === 'GET') return response(200, structuredClone(fixtures[kind]));
    if (options.method === 'PUT') { const row = {id: Number(id), ...body}; fixtures[kind] = fixtures[kind].map(r => r.id === row.id ? row : r); return response(200, row); }
    const row = {id: 20 + fixtures[kind].length, ...body}; fixtures[kind].push(row); return response(201, row);
  }
  throw new Error('未约定请求：' + path);
}
let expired = 0; const api = createApi(transport, () => expired++); await api.login('admin', 'not-stored');
assert.equal(api.role(), 'ADMIN'); assert.deepEqual(redact({token: 'x', nested: {password: 'x'}}), {token: '已隐藏', nested: {password: '已隐藏'}});
await assert.rejects(api.request('https://evil.invalid/'), /不允许/);
await api.request('/actuator/health', {gateway: true}); assert.equal(requests.at(-1).headers.Authorization, undefined);
const edited = await saveCatalog(api, 'ADMIN', 'apis', {publicPath: '/api/changed/{id}'}, fixtures.apis[0]);
assert.equal(edited.requiredScope, 'demo:read'); assert.equal(edited.isPublic, false); assert.equal(edited.serviceId, 1);
assert.equal(requests.at(-1).method, 'PUT'); assert.equal(requests.at(-1).path, '/admin/apis/2');
await assert.rejects(saveCatalog(api, 'VIEWER', 'services', {}, null), /只读/);
assert.throws(() => catalogBody('apis', {isPublic: 'false', requiredScope: ''}, fixtures.apis[0]), /私有/);
assert.throws(() => policyParameters('TIMEOUT', {timeoutMs: 49}), /50/);
assert.throws(() => policyParameters('CIRCUIT_BREAKER', {failureRateThreshold: 50, slidingWindowSize: 4, minimumNumberOfCalls: 5, waitDurationMs: 500}), /最小样本/);
const existing = {id: 4, code: 'rate', type: 'RATE_LIMIT', scope: 'ROUTE'};
const begin = requests.length;
await savePolicy(api, 'OPERATOR', {...existing, scope: 'GLOBAL', capacity: 5, refillPerSecond: 2, enabled: 'true', targetId: 999}, existing, [{targetId: 3}]);
assert.equal(requests.length - begin, 1); assert.equal(requests.at(-1).body.scope, 'ROUTE'); assert.equal(requests.at(-1).method, 'PUT');
await savePolicy(api, 'ADMIN', {code: 'new-timeout', type: 'TIMEOUT', scope: 'API', timeoutMs: 200, enabled: 'true', targetId: '2'}, null);
assert.equal(requests.at(-1).path.endsWith('/bindings'), true); assert.deepEqual(requests.at(-1).body, {targetType: 'API', targetId: 2});
let partialCalls = [];
const partialApi = {request: async (path, options) => { partialCalls.push({path, ...options}); if (path.endsWith('/bindings') && partialCalls.length === 2) throw new Error('目标不存在'); return {id: 55, ...options.body}; }};
const partialValues = {code: 'partial', type: 'TIMEOUT', scope: 'ROUTE', timeoutMs: 200, enabled: 'true', targetId: '3'};
let savedPartial;
try { await savePolicy(partialApi, 'ADMIN', partialValues, null); } catch (error) { savedPartial = error.savedPolicy; assert.match(error.message, /规则草稿已保存/); }
assert.equal(savedPartial.id, 55);
await savePolicy(partialApi, 'ADMIN', partialValues, savedPartial);
assert.equal(partialCalls[2].method, 'PUT', '绑定失败后重试不得重复POST规则');
failNext = 409; const beforeConflict = requests.length;
await assert.rejects(publishConfiguration(api, 'OPERATOR', {version}), error => error.status === 409);
assert.equal(requests.length - beforeConflict, 1, '409不得自动重试');
await publishConfiguration(api, 'ADMIN', {version}, 1); assert.equal(requests.at(-1).path, '/admin/releases/1/rollback');
await assert.rejects(publishConfiguration(api, 'VIEWER', {version}), /只读/);
await assert.rejects(createCaller(api, 'OPERATOR', {username: 'bad', scopes: ''}), /管理员/);
await createCaller(api, 'ADMIN', {username: 'reports', scopes: 'demo:read,orders:read'}); assert.deepEqual(requests.at(-1).body.scopes, ['demo:read', 'orders:read']);
await revokeCaller(api, 'ADMIN', 8); assert.equal(requests.at(-1).path, '/admin/callers/8/revoke');
assert.equal(nodeState({freshness: 'STALE', appliedVersion: version}, version).label, '心跳过期');
assert.equal(versionLabel(null, []), '版本未取得');
assert.match(versionLabel({version: 0}, []), /尚未发布/);
assert.equal((await readCore({request: async () => { throw new Error('offline'); }})).services, null);
const network = createApi(async () => { throw new TypeError('offline'); }); await assert.rejects(network.login('u', 'p'), /连接失败/);
failNext = 401; await assert.rejects(api.request('/admin/services')); assert.equal(api.role(), ''); assert.equal(expired, 1);

// 1. 用小型DOM执行实际页面导航、编辑表单和提交事件，而非只查源码关键字。
class Element {
  constructor(tag) { this.tagName = tag; this.children = []; this.attrs = {}; this.listeners = {}; this.dataset = {}; this.value = ''; this.disabled = false; this.hidden = false; this.isConnected = true; this.open = false; this._text = ''; this.classList = {add: () => {}, remove: () => {}, toggle: () => false}; }
  set textContent(value) { this._text = String(value); this.children = []; }
  get textContent() { return this._text + this.children.map(c => c.textContent).join(''); }
  setAttribute(k, v) { this.attrs[k] = String(v); if (k.startsWith('data-')) this.dataset[k.slice(5)] = String(v); }
  getAttribute(k) { return this.attrs[k]; }
  removeAttribute(k) { delete this.attrs[k]; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; this._text = ''; }
  get options() { return this.children.filter(c => c.tagName === 'option').map(c => ({value: c.attrs.value})); }
  addEventListener(event, listener) { (this.listeners[event] ||= []).push(listener); }
  async fire(event) { for (const listener of this.listeners[event] || []) await listener({preventDefault() {}, key: ''}); }
  descendants() { return this.children.flatMap(child => [child, ...(child.descendants?.() || [])]); }
  querySelectorAll(selector) { return this.descendants().filter(e => selector === 'a' ? e.tagName === 'a' : selector === 'button' ? e.tagName === 'button' : /input:not/.test(selector) ? ['input', 'select'].includes(e.tagName) && !e.disabled : false); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  focus() { document.activeElement = this; }
  reportValidity() { return true; }
  showModal() { this.open = true; }
  close() { this.open = false; this.onclose?.(); }
}
const elements = new Map();
const html = fs.readFileSync(new URL('../flowgate-control/src/main/resources/static/console/index.html', import.meta.url), 'utf8');
for (const match of html.matchAll(/<([a-z]+)[^>]*\bid="([^"]+)"[^>]*>/g)) elements.set(match[2], new Element(match[1]));
globalThis.document = {body: new Element('body'), activeElement: null, getElementById: id => elements.get(id), createElement: tag => new Element(tag), createTextNode: text => ({textContent: text}), addEventListener() {}};
globalThis.fetch = transport;
const ctx = start(document); elements.get('username').value = 'admin'; elements.get('password').value = 'secret';
await elements.get('login-form').fire('submit');
assert.equal(elements.get('password').value, ''); assert.equal(elements.get('login-view').hidden, true);
assert.match(elements.get('page-content').textContent, /接入服务/);
await ctx.navigate('services');
assert.match(elements.get('page-content').textContent, /<img src=x/);
assert.equal(elements.get('page-content').descendants().some(e => e.tagName === 'img'), false, '不可信名称只能作为文本');
const editButton = elements.get('page-content').descendants().find(e => e.tagName === 'button' && e.textContent === '编辑');
await editButton.fire('click');
const dialog = elements.get('entity-dialog'), form = dialog.children[0];
const nameField = form.descendants().find(e => e.attrs?.name === 'name'); assert.equal(nameField.value, fixtures.services[0].name);
nameField.value = '新名称'; await form.fire('submit');
assert.equal(fixtures.services[0].name, '新名称'); assert.equal(fixtures.services[0].code, 'demo'); assert.match(elements.get('page-notice').textContent, /草稿已保存/);
await ctx.navigate('releases');
await elements.get('page-head').descendants().find(e => e.tagName === 'button' && e.textContent === '发布全部草稿').fire('click');
failNext = 409; const sentBefore = requests.length;
await dialog.children[0].fire('submit'); assert.equal(requests.length - sentBefore, 1); assert.equal(dialog.open, true);
assert.match(dialog.textContent, /重新读取/); dialog.close();
ctx.role = 'VIEWER'; await ctx.navigate('apis');
assert.equal(elements.get('page-content').descendants().find(e => e.tagName === 'button' && e.textContent === '编辑').disabled, true);
await ctx.navigate('protection'); assert.match(elements.get('page-content').textContent, /故障保护|熔断/);
ctx.issued = {token: 'must-clear'}; await ctx.navigate('dashboard'); assert.equal(ctx.issued, null);
await elements.get('logout').fire('click'); assert.equal(elements.get('app-view').hidden, true); assert.equal(elements.get('page-content').textContent, '');
console.log('PASS 产品真实模块：请求/完整PUT/规则绑定/权限/CAS409不重试/回滚/Caller/未知与断网/会话清密');
console.log('PASS 产品模拟DOM：登录/列表/表单回填与真实提交路径/草稿反馈/只读按钮/导航/退出清空；非浏览器验收');

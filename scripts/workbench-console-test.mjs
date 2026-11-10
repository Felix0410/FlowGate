/** 工作台业务上下文与地址安全合同；真实模块+模拟DOM，不替代负责人手测。 */
import './product-console-test.mjs';
import assert from 'node:assert/strict';
import {domains, views, parseRoute, routeHash} from '../flowgate-control/src/main/resources/static/console/navigation.js';
import {renderBusiness} from '../flowgate-control/src/main/resources/static/console/openapi-business.js';
import {renderCatalog} from '../flowgate-control/src/main/resources/static/console/catalog.js';
import {reportSelection, rememberReport} from '../flowgate-control/src/main/resources/static/console/report-context.js';
assert.equal(domains.length, 7); assert.equal(new Set(views.map(row => row[0])).size, 21);
for (const [view] of views) assert.equal(parseRoute(routeHash(view)).view, view);
assert.deepEqual(parseRoute('#business/apps?id=2&token=secret&password=p&response=raw'), {view: 'apps', params: {id: '2'}});
assert.equal(parseRoute('#unknown/screen?id=1').view, 'dashboard');
assert.equal(parseRoute('#apis').view, 'apis');
const requests = [], states = {}, transitions = [];
const customer = {id: 1, name: '客户甲', code: 'c1', status: 'ACTIVE'};
const app = {id: 2, developerId: 1, developerName: '客户甲', name: '应用甲', code: 'a1', status: 'ACTIVE'};
const api = {request: async path => {
  requests.push(path);
  if (path === '/admin/developers/1') return customer;
  if (path === '/admin/apps/2') return app;
  if (path.startsWith('/admin/developers?')) return [customer];
  if (path.startsWith('/admin/apps?')) return [app];
  if (path.startsWith('/admin/subscriptions?')) return [];
  if (path.startsWith('/admin/plans?')) return [];
  throw new Error('未约定路径 ' + path);
}};
const ctx = {api, role: 'ADMIN', params: {id: '1'}, view: 'developers', heading() {}, state(key = this.view) { return states[key] ||= {}; },
  navigate(view, params = {}) { transitions.push({view, params}); this.view = view; this.params = params; }, flash() {}, details() {}, saved() {}};
const click = async (page, label) => {
  const action = page.descendants().find(node => ['a', 'button'].includes(node.tagName) && node.textContent === label);
  assert.ok(action, label); await action.fire('click');
};
let page = await renderBusiness(ctx, 'developers');
assert.ok(requests.some(path => path.endsWith('&developerId=1')));
await click(page, '为此客户新建应用');
let dialog = document.getElementById('entity-dialog');
assert.equal(dialog.descendants().find(node => node.attrs?.name === 'developers').value, '1'); dialog.close();
await click(page, '应用甲'); assert.deepEqual(transitions.at(-1), {view: 'apps', params: {id: 2}});
page = await renderBusiness(ctx, 'apps'); await click(page, '查看配额');
assert.deepEqual(transitions.at(-1), {view: 'quota', params: {applicationId: 2}});
ctx.params = {id: '2', tab: 'subscriptions'}; page = await renderBusiness(ctx, 'apps');
assert.ok(requests.some(path => path.endsWith('&applicationId=2')));
await click(page, '为此应用新增订阅');
assert.equal(dialog.descendants().find(node => node.attrs?.name === 'apps').value, '2'); dialog.close();
ctx.params = {id: '3'}; ctx.data = {services: [{id: 3, code: 's1', name: '服务甲', upstreamUri: 'http://127.0.0.1:8085', enabled: true}], apis: [], routes: []};
page = renderCatalog(ctx, 'services'); await click(page, '在此服务新建API');
assert.equal(dialog.descendants().find(node => node.attrs?.name === 'serviceId').value, '3'); dialog.close();
console.log('PASS 工作台七域二十能力/地址白名单/旧链接/客户详情真实分页/应用与订阅预选/配额携带应用/服务API预选');
const reports = {params: {applicationId: '2'}};
assert.equal(reportSelection(reports).applicationId, '2');
rememberReport(reports, {applicationId: '2', from: '2026-10-20', to: '2026-11-02'});
reports.params = {};
assert.equal(reportSelection(reports).period, '202610');
rememberReport(reports, {applicationId: '2', period: '202610'}, true);
assert.equal(reportSelection(reports).from, '2026-10-20', '按月查询不得覆盖已选日报范围');
rememberReport(reports, {period: '202611'}, true);
assert.equal(reportSelection(reports).from, '2026-11-01');
assert.equal(reportSelection(reports).to, '2026-11-30');
const closed = reportSelection(reports, true);
assert.ok(closed.to <= new Date(Date.now() - 86400000).toISOString().slice(0, 10));
console.log('PASS 报表共享应用/跨月余额契约/同月读取不覆盖日期/显式改月/对账截止封账UTC日');

// 1. 可注入浏览器适配器，执行真实入口的前进/后退与刷新恢复，不写入任何浏览器缓存。
const {start} = await import('../flowgate-control/src/main/resources/static/console/app.js');
const fs = await import('node:fs');
const Element = document.createElement('div').constructor;
function freshDocument() {
  const nodes = new Map();
  for (const match of fs.readFileSync(new URL('../flowgate-control/src/main/resources/static/console/index.html', import.meta.url), 'utf8').matchAll(/<([a-z]+)[^>]*\bid="([^"]+)"[^>]*>/g)) nodes.set(match[2], new Element(match[1]));
  return {body: new Element('body'), activeElement: null, getElementById: id => nodes.get(id), createElement: tag => new Element(tag), createTextNode: text => ({textContent: text}), addEventListener() {}};
}
function historyBrowser(initial) {
  const entries = [initial], listeners = {}; let position = 0;
  const browser = {location: {hash: initial}, addEventListener(type, handler) { (listeners[type] ||= []).push(handler); },
    history: {pushState(data, title, hash) { entries.splice(++position); entries.push(hash); browser.location.hash = hash; }, replaceState(data, title, hash) { entries[position] = hash; browser.location.hash = hash; }},
    async back() { position = Math.max(0, position - 1); browser.location.hash = entries[position]; for (const handler of listeners.popstate || []) handler(); for (const handler of listeners.hashchange || []) handler(); await new Promise(resolve => setImmediate(resolve)); },
    async forward() { position = Math.min(entries.length - 1, position + 1); browser.location.hash = entries[position]; for (const handler of listeners.popstate || []) handler(); await new Promise(resolve => setImmediate(resolve)); }
  }; return browser;
}
const browser = historyBrowser('#access/services?id=1&token=should-disappear');
globalThis.document = freshDocument();
const routed = start(document, browser);
assert.equal(browser.location.hash, '#access/services?id=1');
document.getElementById('username').value = 'admin'; document.getElementById('password').value = 'fixture';
await document.getElementById('login-form').fire('submit');
assert.equal(routed.view, 'services'); assert.equal(routed.params.id, '1');
await routed.navigate('apis', {id: 2});
// 2. 业务筛选只替换当前非敏感地址，返回/前进不得恢复旧Token或重复处理同一次事件。
routed.issued = {token: 'once'}; routed.appIssued = {token: 'app-once'};
await browser.back(); assert.equal(routed.view, 'services'); assert.equal(routed.issued, null); assert.equal(routed.appIssued, null);
await browser.forward(); assert.equal(routed.view, 'apis'); assert.equal(routed.params.id, '2');
routed.updateParams({password: 'bad', response: 'raw', id: 2}); assert.equal(browser.location.hash, '#access/apis?id=2');
// 3. 模拟刷新后是空会话，登录后恢复安全详情链接，而非沿用旧会话或敏感回执。
const reloadedBrowser = historyBrowser(browser.location.hash); globalThis.document = freshDocument();
const reloaded = start(document, reloadedBrowser); assert.equal(reloaded.role, ''); assert.equal(reloaded.view, 'apis'); assert.equal(reloaded.params.id, '2');
console.log('PASS 真实入口hash直达/敏感参数移除/登录后恢复/前进后退/一次性回执清理/刷新空会话');

// 原生close事件会排队；替换模态内容不能先close再打开，否则迟到事件清空新表单。
const {openDialog, h, formFields} = await import('../flowgate-control/src/main/resources/static/console/ui.js');
const replacementDialog = document.getElementById('entity-dialog'), originalClose = replacementDialog.close;
replacementDialog.close = function () { this.open = false; queueMicrotask(() => this.onclose?.()); };
openDialog('规则类型', h('p', {}, '选择类型'), null);
const ruleFields = formFields([{name: 'timeoutMs', label: '超时', value: '200'}]);
openDialog('规则表单', ruleFields.node, async () => {});
await new Promise(resolve => setImmediate(resolve));
assert.equal(replacementDialog.open, true); assert.ok(replacementDialog.descendants().some(node => node.attrs?.name === 'timeoutMs'));
replacementDialog.close = originalClose; replacementDialog.close();
let resolveDetail, changedHeading = false;
const lateContext = {role: 'ADMIN', params: {id: 2}, secretEpoch: 1, api: {request: () => new Promise(resolve => {resolveDetail = resolve;})}, heading() {changedHeading = true;}};
const lateDetail = renderBusiness(lateContext, 'apps'); lateContext.secretEpoch++; resolveDetail(app); await lateDetail;
assert.equal(changedHeading, false, '离页后的迟到详情不得覆盖新页标题');
console.log('PASS 原生close事件排队时模态替换保留表单/迟到详情不覆盖新页');
const originalFetch = globalThis.fetch; let finishSlowDetail;
globalThis.fetch = (path, options) => path === '/admin/apps/2' ? new Promise(resolve => {finishSlowDetail = resolve;}) : originalFetch(path, options);
document.getElementById('username').value = 'admin'; document.getElementById('password').value = 'fixture';
await document.getElementById('login-form').fire('submit');
const slowNavigation = reloaded.navigate('apps', {id: 2});
assert.equal(document.getElementById('page-head').querySelectorAll('button').length, 0, '加载详情时旧对象编辑操作必须消失');
assert.equal(document.getElementById('page-content').attrs['aria-busy'], 'true');
finishSlowDetail({status: 200, ok: true, headers: {get: () => null}, text: async () => JSON.stringify(app)});
await slowNavigation; globalThis.fetch = originalFetch;
assert.equal(document.getElementById('page-content').attrs['aria-busy'], 'false');
assert.equal(document.getElementById('workspace').scrollTop, 0);
console.log('PASS 详情加载期间移除旧编辑操作/aria-busy/切页滚动归零');

/** 工作台业务上下文与地址安全合同；真实模块+模拟DOM，不替代负责人手测。 */
import './product-console-test.mjs';
import assert from 'node:assert/strict';
import {domains, views, parseRoute, routeHash} from '../flowgate-control/src/main/resources/static/console/navigation.js';
import {renderBusiness} from '../flowgate-control/src/main/resources/static/console/openapi-business.js';
import {renderCatalog} from '../flowgate-control/src/main/resources/static/console/catalog.js';
assert.equal(domains.length, 7); assert.equal(new Set(views.map(row => row[0])).size, 20);
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

import {createApi, redact} from './api.js';
import {h, button, badge, notice, permissionButton, openDialog} from './ui.js';
import {renderCatalog, editCatalog} from './catalog.js';
import {renderGovernance} from './governance.js';
import {renderReleases, renderCallers, renderMonitoring, renderAudits, nodesTable, deliveryProgress} from './operations.js';
import {renderUsage, renderAsyncEvents} from './usage.js';
import {renderQuota} from './quota.js';
import {renderBusiness, businessKinds} from './openapi-business.js';
import {domains, views, domainFor, parseRoute, routeHash, safeParams} from './navigation.js';
export {views};
/** 相互独立的读取失败各自显示未知，不用旧数值或空数组冒充当前真实状态。 */
export async function readCore(api) {
  const paths = {services: '/admin/services', apis: '/admin/apis', routes: '/admin/routes', policies: '/admin/policies', current: '/admin/releases/current', nodes: '/admin/nodes', health: '/status'};
  const keys = Object.keys(paths), result = await Promise.allSettled(keys.map(key => api.request(paths[key], {publicRequest: key === 'health'})));
  return Object.fromEntries(keys.map((key, index) => [key, result[index].status === 'fulfilled' ? result[index].value : null]));
}
export function versionLabel(current, nodes) {
  if (!current) return '版本未取得';
  if (current.version === 0) return '尚未发布 · 暂无生效配置';
  if (!nodes?.length) return '已发布V' + current.version + ' · 暂无节点确认';
  const synced = nodes.filter(node => node.freshness === 'ONLINE' && node.appliedVersion === current.version).length;
  return '已发布V' + current.version + ' · ' + synced + '/' + nodes.length + '节点已应用';
}
function dashboard(ctx) {
  ctx.heading('工作台', '从接入到发布，在这里查看配置与运行的真实状态。', permissionButton('接入服务', () => editCatalog(ctx, 'services'), ctx.role, false, true));
  const stats = h('div', {class: 'stats'}, ['services', 'apis', 'routes', 'policies'].map((kind, index) => button('', () => ctx.navigate(kind === 'policies' ? 'rate' : kind), {class: 'stat-card', 'aria-label': ['服务', 'API', '路由', '规则'][index] + '列表'})));
  [...stats.children].forEach((card, index) => { const key = ['services', 'apis', 'routes', 'policies'][index]; card.append(h('span', {}, ['接入服务', 'API接口', '路由草稿', '治理规则'][index]), h('strong', {}, ctx.data[key]?.length ?? '—'), h('small', {}, ctx.data[key] === null ? '未取得 · 请重新读取' : '当前目录记录')); });
  const health = ctx.data.health;
  const healthCard = h('section', {class: 'card'}, h('h2', {}, '本机依赖'), health ? h('div', {class: 'health-list'}, Object.entries(health).map(([key, value]) => h('div', {class: 'health-item'}, ({control: 'Control管理服务', mysql: '配置数据库', redis: 'Redis', demo: 'Demo下游'})[key] || key, badge(value === 'UP' ? '正常' : String(value), value === 'UP' ? 'good' : 'warn')))) : notice('依赖状态未取得。请确认Control已启动并重新读取。'), h('p', {class: 'meta'}, '数据读取时间：' + ctx.readAt + '。依赖正常不表示路由已发布。'));
  const steps = h('section', {class: 'card'}, h('h2', {}, '开始接入一个API'), [
    ['接入服务', '登记已被部署白名单允许的上游地址。', 'services'], ['创建API与路由', '选择服务、请求路径与权限，再建立关联路由。', 'apis'], ['配置保护规则', '按需选择业务目标与速率、并发或故障保护参数。', 'rate'], ['发布并确认', '发布完整草稿，确认网关上报的应用版本。', 'releases']
  ].map(([title, text, view], index) => h('div', {class: 'step-row'}, h('span', {class: 'step-number'}, index + 1), h('div', {}, button(title, () => ctx.navigate(view)), h('p', {class: 'muted'}, text)))));
  const issues = [];
  if (!ctx.data.current || ctx.data.current.version === 0) issues.push('尚无已发布版本。先准备目录，再到发布中心发布。');
  if (ctx.data.nodes === null) issues.push('节点信息未取得，不能确认网关应用状态。');
  else if (!ctx.data.nodes.length) issues.push('尚无网关确认记录，请检查Gateway运行情况。');
  else if (ctx.data.nodes.some(n => n.freshness !== 'ONLINE' || n.appliedVersion !== ctx.data.current?.version)) issues.push('有节点心跳过期或版本未同步，请前往运行监控核对。');
  return h('div', {}, stats, ...issues.map(text => notice(text)), h('div', {class: 'grid-two dashboard-panels'}, healthCard, steps), h('section', {class: 'card'}, h('h2', {}, '配置交付与节点'), deliveryProgress(ctx.data.current, ctx.data.nodes), nodesTable(ctx.data.nodes, ctx.data.current?.version)));
}
export function start(documentRef = document) {
  // 1. 会话结束同时清空数据、回执与模态内容，旧请求用序号隔离。
  const el = id => documentRef.getElementById(id); let renderNumber = 0, refreshing = false, menuOpen = false;
  const closeMenu = (restoreFocus = false) => {
    menuOpen = false; documentRef.body.classList.remove('menu-open'); el('menu-backdrop').hidden = true;
    el('menu-toggle').setAttribute('aria-expanded', 'false'); el('workspace').inert = false;
    if (restoreFocus) el('menu-toggle').focus();
  };
  function sessionEnded(message = '') {
    renderNumber++; api.clear(); ctx.role = ''; ctx.data = {}; ctx.callers = []; ctx.issued = null; ctx.appIssued = null; ctx.secretEpoch = (ctx.secretEpoch || 0) + 1;
    if (el('entity-dialog').open) el('entity-dialog').close();
    el('page-content').replaceChildren(); el('page-notice').replaceChildren(); el('password').value = '';
    el('app-view').hidden = true; el('login-view').hidden = false; closeMenu();
    el('login-error').replaceChildren(message ? notice(message, 'error') : h('span'));
    el('username').focus();
  }
  const api = createApi(undefined, () => sessionEnded('登录已过期，请重新登录。当前凭证已清空。'));
  const ctx = {
    api, role: '', params: {}, pageState: {}, lastViews: {}, state(view = ctx.view) { return ctx.pageState[view] ||= {}; }, data: Object.fromEntries(['services', 'apis', 'routes', 'policies', 'current', 'nodes', 'health'].map(key => [key, null])), view: 'dashboard', callers: [], issued: null, readAt: '',
    heading(title, description, action) {
      const anchors = {dashboard: 'start', services: 'connect', apis: 'connect', routes: 'connect', developers: 'openapi', apps: 'openapi', plans: 'openapi', subscriptions: 'openapi', quota: 'quota', 'quota-policy': 'quota', usage: 'async-usage', 'async-events': 'async-usage', reconciliation: 'async-usage', rate: 'protect', concurrency: 'protect', protection: 'protect', releases: 'release', callers: 'callers', monitoring: 'monitor', audits: 'monitor'};
      el('page-head').replaceChildren(h('div', {class: 'page-heading'}, h('p', {class: 'breadcrumb'}, 'FlowGate / ' + title), h('h1', {}, title), h('p', {}, description)), h('div', {class: 'page-actions'}, h('a', {class: 'page-help', href: 'guide.html#' + (anchors[ctx.view] || 'start'), target: '_blank', rel: 'noopener'}, '使用说明', h('span', {class: 'sr-only'}, '（新窗口）')), action));
    },
    unavailable(text) { return h('div', {class: 'card'}, notice(text, 'error'), button('重新读取', () => ctx.refresh())); },
    flash(text, tone = '') { el('page-notice').replaceChildren(notice(text, tone), text.includes('草稿') ? button('前往发布中心', () => ctx.navigate('releases')) : h('span')); },
    details(title, value) { openDialog(title, h('div', {}, notice('只读技术摘要，不是编辑入口。'), h('pre', {}, JSON.stringify(redact(value), null, 2))), null); },
    async saved(text) { await ctx.refresh(); ctx.flash(text, 'good'); },
    async navigate(view, params = {}, {history = true} = {}) {
      ctx.issued = null; ctx.appIssued = null; ctx.secretEpoch = (ctx.secretEpoch || 0) + 1; // 一次性凭证离开领取页即清除，不留在隐藏DOM或其他页面。
      if (el('entity-dialog').open) el('entity-dialog').close();
      ctx.view = views.some(row => row[0] === view) ? view : 'dashboard'; ctx.params = safeParams(ctx.view, params);
      closeMenu();
      el('page-notice').replaceChildren(); await ctx.render(); el('main').focus();
    },
    async refresh() {
      if (refreshing || !ctx.role) return;
      refreshing = true; el('refresh').disabled = true;
      const ticket = renderNumber;
      try { const data = await readCore(api); if (!ctx.role || ticket !== renderNumber) return; ctx.data = data; ctx.readAt = new Date().toLocaleTimeString(); await ctx.render(); }
      finally { refreshing = false; el('refresh').disabled = false; }
    },
    async render() {
      if (!ctx.role) return;
      const ticket = ++renderNumber, view = ctx.view;
      el('top-title').textContent = views.find(row => row[0] === view)[1]; el('version-state').textContent = versionLabel(ctx.data.current, ctx.data.nodes);
      el('session-role').textContent = ({ADMIN: '管理员', OPERATOR: '操作员', VIEWER: '只读用户'})[ctx.role] + ' · 会话仅限本页';
      const domain = domainFor(view); ctx.lastViews[domain.key] = view;
      for (const a of el('navigation').querySelectorAll('a')) { if (a.dataset.domain === domain.key) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current'); }
      el('page-tabs').replaceChildren(...domain.tabs.map(([key, label]) => h('a', {href: routeHash(key), 'data-view': key, ...(view === key ? {'aria-current': 'page'} : {}), onclick: event => { event.preventDefault(); ctx.navigate(key); }}, label)));
      el('page-tabs').hidden = domain.tabs.length === 1;
      el('page-content').replaceChildren(notice('正在读取页面…'));
      try {
        // 2. 页面切换与会话退出后，迟到响应不得覆盖新页面或重新展示敏感回执。
        const content = view === 'usage' || view === 'reconciliation' ? await renderUsage(ctx, view === 'reconciliation') : view === 'async-events' ? await renderAsyncEvents(ctx) : view === 'quota' ? await renderQuota(ctx) : businessKinds[view] ? await renderBusiness(ctx, view) : ['services', 'apis', 'routes'].includes(view) ? renderCatalog(ctx, view) : ['rate', 'concurrency', 'protection', 'quota-policy'].includes(view) ? await renderGovernance(ctx, view) : view === 'releases' ? await renderReleases(ctx) : view === 'callers' ? renderCallers(ctx) : view === 'monitoring' ? await renderMonitoring(ctx) : view === 'audits' ? await renderAudits(ctx) : dashboard(ctx);
        if (ticket === renderNumber && ctx.role) el('page-content').replaceChildren(content);
      } catch (error) { if (ticket === renderNumber && ctx.role) el('page-content').replaceChildren(ctx.unavailable(error.message)); }
    }
  };
  for (const domain of domains) {
    el('navigation').append(h('a', {href: routeHash(domain.tabs[0][0]), 'data-domain': domain.key, onclick: event => {
      event.preventDefault(); const view = ctx.lastViews[domain.key] || domain.tabs[0][0]; ctx.navigate(view);
    }}, domain.label));
  }
  el('home-link').addEventListener('click', event => { event.preventDefault(); ctx.navigate('dashboard'); });
  // 3. 绑定显式的登录/退出/刷新动作，不定时造状态，不在前端持久化会话。
  el('login-form').addEventListener('submit', async event => {
    event.preventDefault(); if (el('login-submit').disabled) return;
    el('login-submit').disabled = true; el('login-submit').textContent = '正在登录…'; el('login-error').replaceChildren();
    const username = el('username').value, password = el('password').value; el('password').value = '';
    try { const session = await api.login(username, password); ctx.role = session.role; el('login-view').hidden = true; el('app-view').hidden = false; await ctx.refresh(); }
    catch (error) { sessionEnded(error.message); }
    finally { el('login-submit').disabled = false; el('login-submit').textContent = '登录 FlowGate'; }
  });
  el('logout').addEventListener('click', async () => { try { await api.logout(); } catch { /* 即使服务不可达，也先清空本页会话。 */ } finally { sessionEnded(); } });
  el('refresh').addEventListener('click', () => ctx.refresh());
  el('login-health').addEventListener('click', async () => {
    el('login-health').disabled = true;
    try { const status = await api.request('/status', {publicRequest: true}); el('login-dependencies').replaceChildren(notice(Object.entries(status).map(([key, value]) => key + '：' + value).join(' / '))); }
    catch (error) { el('login-dependencies').replaceChildren(notice(error.message, 'error')); }
    finally { el('login-health').disabled = false; }
  });
  el('menu-toggle').addEventListener('click', () => {
    if (menuOpen) { closeMenu(true); return; }
    menuOpen = true; documentRef.body.classList.add('menu-open'); el('menu-backdrop').hidden = false;
    el('menu-toggle').setAttribute('aria-expanded', 'true'); el('workspace').inert = true; el('navigation').querySelector('a')?.focus();
  });
  el('menu-backdrop').addEventListener('click', () => closeMenu(true));
  documentRef.addEventListener('keydown', event => {
    // 抽屉开启时将键盘焦点留在导航内；Escape关闭后返回触发按钮。
    if (!menuOpen) return;
    if (event.key === 'Escape') { event.preventDefault(); closeMenu(true); }
    if (event.key === 'Tab') {
      const targets = [...el('sidebar').querySelectorAll('a[href], button:not([disabled]), input:not([disabled])')].filter(node => node.getClientRects().length > 0);
      const first = targets[0], last = targets.at(-1);
      if (event.shiftKey && documentRef.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && documentRef.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
  });
  if (typeof matchMedia === 'function') matchMedia('(min-width: 1101px)').addEventListener('change', () => closeMenu());
  return ctx;
}
if (typeof document !== 'undefined' && document.getElementById('login-form')) start();

import {h, button, badge, notice, permissionButton, table, formFields, openDialog, viewLink, facts} from './ui.js';
export const businessKinds = {developers: '客户', apps: '应用', plans: '套餐', subscriptions: '订阅'};
const states = [{value: 'ACTIVE', label: '启用'}, {value: 'SUSPENDED', label: '暂停'}];
const statusLabel = value => ({ACTIVE: '启用', SUSPENDED: '暂停', EXPIRED: '已到期', CURRENT: '当前期间', SCHEDULED: '待生效'})[value] || '状态未取得';
export function businessBody(kind, values, editing = false) {
  const body = {};
  const fields = kind === 'subscriptions' ? ['applicationId', 'planId', 'effectiveAt', 'expiresAt'] : kind === 'plans' && editing ? ['name', 'status'] : kind === 'apps' && !editing ? ['developerId', 'code', 'name', 'status'] : ['code', 'name', 'status'];
  for (const name of fields) body[name] = values[name];
  for (const name of ['developerId', 'applicationId', 'planId']) if (name in body) { body[name] = Number(body[name]); if (!Number.isSafeInteger(body[name]) || body[name] < 1) throw new Error('请选择真实存在的关联名称'); }
  if (kind === 'plans' && !editing) { body.monthlyUnits = Number(values.monthlyUnits); if (!Number.isInteger(body.monthlyUnits) || body.monthlyUnits < 1 || body.monthlyUnits > 1000000) throw new Error('套餐额度须1–1000000整数'); }
  if (body.code && !/^[A-Za-z0-9_-]{1,100}$/.test(body.code)) throw new Error('代码仅允许100位以内字母数字横线下划线');
  if (kind === 'subscriptions') for (const key of ['effectiveAt', 'expiresAt']) if (!/Z$/.test(body[key]) || !Number.isFinite(Date.parse(body[key]))) throw new Error('订阅日期须ISO8601 UTC时间并以Z结尾');
  return body;
}
export async function saveBusiness(api, role, kind, values, original = null) {
  if (role !== 'ADMIN') throw new Error('只有ADMIN可以修改客户、应用、套餐和订阅');
  if (!businessKinds[kind] || (kind === 'subscriptions' && original)) throw new Error('订阅只支持创建与查询，不支持区间编辑');
  return api.request('/admin/' + kind + (original ? '/' + original.id : ''), {method: original ? 'PATCH' : 'POST', body: businessBody(kind, values, Boolean(original))});
}
export async function businessPage(api, kind, afterId = 0, filter = {}) {
  const query = new URLSearchParams({afterId: String(afterId), limit: '20'});
  for (const key of ['developerId', 'applicationId']) if (filter[key]) query.set(key, String(filter[key]));
  return api.request('/admin/' + kind + '?' + query);
}
/** 关联选项按游标分批读取，使用名称+代码，不让操作者查数据库ID。 */
export async function reference(api, kind, label, selected = '', optional = false) {
  const controls = formFields([{name: kind, label, value: '', options: [{value: '', label: optional ? '全部' : '请选择'}], required: !optional, help: '显示已读取的名称和代码；记录较多时继续读取选项。'}]);
  const input = controls.controls[kind], error = h('div', {'aria-live': 'polite'}); let cursor = 0, records = [], busy = false;
  const more = button('继续读取选项', async () => { try { await load(); error.replaceChildren(); } catch (e) { error.replaceChildren(notice(e.message, 'error')); } });
  function option(row) { return h('option', {value: row.id}, row.name + ' · ' + row.code + (row.status === 'SUSPENDED' ? '（暂停）' : '')); }
  async function load() {
    if (busy) return; busy = true; more.disabled = true;
    try { const rows = await api.request('/admin/' + kind + '?afterId=' + cursor + '&limit=100'); const previous = input.value;
      records.push(...rows); rows.forEach(row => { if (![...input.options].some(o => o.value === String(row.id))) input.append(option(row)); });
      if (rows.length) cursor = rows.at(-1).id; more.hidden = rows.length < 100; input.value = previous;
    } finally { busy = false; more.disabled = false; }
  }
  await load();
  if (selected && !records.some(r => r.id === Number(selected))) input.append(option(await api.request('/admin/' + kind + '/' + selected)));
  input.value = String(selected || '');
  return {input, node: h('div', {class: 'wide'}, controls.node, more, error)};
}
export async function editBusiness(ctx, kind, original = null, defaults = {}) {
  if (ctx.role !== 'ADMIN') return;
  try {
    // 1. 编辑前重新读详情；PATCH仅带可修改字段，不覆盖内部版本、时间或原有套餐额度。
    const row = original ? await ctx.api.request('/admin/' + kind + '/' + original.id) : {};
    const definitions = [];
    if (kind !== 'subscriptions') {
      definitions.push({name: 'name', label: businessKinds[kind] + '名称', value: row.name || '', maxlength: 200});
      definitions.push({name: 'code', label: '唯一代码', value: row.code || '', pattern: '[A-Za-z0-9_-]{1,100}', maxlength: 100, disabled: Boolean(original && kind === 'plans'), help: '字母、数字、横线或下划线；不可与同类记录重复。'});
      definitions.push({name: 'status', label: '管理状态', value: row.status || 'ACTIVE', options: states});
    }
    if (kind === 'plans') definitions.push({name: 'monthlyUnits', label: '每月准入单位（套餐定义）', value: row.monthlyUnits ?? 5, type: 'number', min: 1, max: 1000000, step: 1, disabled: Boolean(original), help: '创建后额度不可修改；调整额度请新建套餐。首次准入时按订阅冻结本月余额；套餐定义本身不是余额。'});
    let owner = null, application = null, plan = null;
    if (kind === 'apps') { owner = await reference(ctx.api, 'developers', '所属客户', row.developerId || defaults.developerId || ctx.params?.developerId || ctx.state?.('apps')?.developerId); owner.input.disabled = Boolean(original); }
    if (kind === 'subscriptions') {
      application = await reference(ctx.api, 'apps', '订阅应用', defaults.applicationId || ctx.params?.applicationId || ctx.state?.('subscriptions')?.applicationId); plan = await reference(ctx.api, 'plans', '选择套餐');
      const now = new Date(), start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1)), end = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1));
      definitions.push({name: 'effectiveAt', label: '生效时间（UTC）', value: start.toISOString(), help: '当前月份可立即生效；排队变更只能下个UTC月首日00:00。初值来自本机，后端以数据库UTC时间复验。'});
      definitions.push({name: 'expiresAt', label: '到期时间（UTC，不含端点）', value: end.toISOString(), help: 'ISO8601并以Z结尾，毫秒精度。当前订阅到期端点可与次月订阅起点相接。'});
    }
    const fields = formFields(definitions);
    const period = kind === 'subscriptions' ? formFields([{name: 'month', label: '计费月份（UTC）', value: 'CURRENT', options: [{value: 'CURRENT', label: '本月'}, {value: 'NEXT', label: '下月首日'}, {value: 'CUSTOM', label: '高级：自填UTC区间'}], help: '同一UTC月只能选择一次套餐；后端以数据库UTC时间复验。'}]) : null;
    if (period) period.controls.month.addEventListener('change', () => { if (period.controls.month.value !== 'CUSTOM') { const span = subscriptionMonth(period.controls.month.value); fields.controls.effectiveAt.value = span.effectiveAt; fields.controls.expiresAt.value = span.expiresAt; } });
    const content = h('div', {}, notice(kind === 'subscriptions' ? '订阅区间为左闭右开；最多一个当前和一个次月排队。创建后不开放任意区间修改。' : '保存立即更新管理记录，不需要V1路由发布；不代表额度或应用凭证已启用。'), owner?.node, application?.node, plan?.node, period?.node, fields.node);
    openDialog((original ? '编辑' : '新建') + businessKinds[kind], content, async () => {
      // 2. 名称选择映射为可信API引用；真正归属/状态/并发验证由服务器完成。
      const values = fields.values(); if (owner) values.developerId = owner.input.value;
      if (application) values.applicationId = application.input.value; if (plan) values.planId = plan.input.value;
      await saveBusiness(ctx.api, ctx.role, kind, values, original);
      // 3. 成功只说明管理数据保存，不宣称配额或发布生效。
      await ctx.saved(businessKinds[kind] + (kind === 'subscriptions' ? '已创建。' : '已保存。') + '是否可用请查看凭证与发布中心实际状态。');
    }, {submitLabel: kind === 'subscriptions' ? '创建订阅' : '保存管理记录'});
  } catch (error) { ctx.flash(error.message, 'error'); }
}
export async function credentialsPlaceholder(ctx, app) {
  if (ctx.role !== 'ADMIN') return;
  try { await ctx.api.request('/admin/apps/' + app.id + '/credentials', {method: 'POST'}); ctx.flash('未收到预期的未启用响应，请联系负责人核对', 'error'); }
  catch (error) { ctx.flash(error.code === 'FEATURE_NOT_READY' ? 'A阶段尚未启用应用凭证，需B阶段身份隔离与配额验证后才能签发。' : error.message, error.code === 'FEATURE_NOT_READY' ? '' : 'error'); }
}
/** 业务列表保留查询及服务端游标，不把局部分页当全量关联。 */
export async function renderBusiness(ctx, kind) {
  if (ctx.params?.id) return businessDetail(ctx, kind);
  ctx.heading(businessKinds[kind] + '管理', '', permissionButton('新建' + businessKinds[kind], () => editBusiness(ctx, kind), ctx.role, true, true));
  const state = ctx.state?.(kind) || {}, key = kind === 'apps' ? 'developerId' : 'applicationId';
  const selected = ctx.params?.[key] ?? state[key] ?? '';
  if (String(selected) !== String(state[key] || '')) { state.after = 0; state.previous = []; }
  state[key] = String(selected);
  const container = h('section', {class: 'card'}), error = h('div', {'aria-live': 'polite'});
  let rows = [], after = state.after || 0, previous = state.previous || [], busy = false, pendingFilter = false;
  const more = button('读取下一页', () => { previous.push(after); return load(rows.at(-1)?.id || 0); });
  const earlier = button('上一页', () => load(previous.pop() || 0)), retry = button('重新读取本页', () => load(after));
  let filter = null;
  if (kind === 'apps') filter = await reference(ctx.api, 'developers', '按客户查看应用', selected, true);
  if (kind === 'subscriptions') filter = await reference(ctx.api, 'apps', '按应用查看订阅', selected, true);
  const columns = kind === 'developers' ? ['客户名称', '代码', '管理状态', '创建时间', '操作'] : kind === 'apps' ? ['应用名称', '代码', '所属客户', '状态', '操作'] : kind === 'plans' ? ['套餐名称', '代码', '每月准入单位', '固定版本', '状态', '操作'] : ['应用', '套餐/冻结版本', 'UTC期间', '期间状态', '关联状态'];
  function cells(row) {
    const link = kind !== 'subscriptions' ? viewLink(ctx, kind, row.name, {id: row.id}) : null;
    const action = viewLink(ctx, kind, '查看', {id: row.id});
    const status = badge(row.status === 'ACTIVE' ? '启用' : row.status === 'SUSPENDED' ? '暂停' : '已到期', row.status === 'ACTIVE' ? 'good' : 'warn');
    if (kind === 'developers') return [link, row.code, status, row.createdAt || '未取得', action];
    if (kind === 'apps') return [link, row.code, row.developerName || '未取得', status, action];
    if (kind === 'plans') return [link, row.code, row.monthlyUnits, 'V' + row.planVersion, status, action];
    return [viewLink(ctx, 'apps', row.applicationName || '查看应用', {id: row.applicationId}), row.planName + ' / V' + row.planVersionSnapshot, row.effectiveAt + ' 至 ' + row.expiresAt, badge(({CURRENT: '当前期间', SCHEDULED: '待生效', EXPIRED: '已到期'})[row.periodState] || '未取得'), row.parentsActive ? '关联主体启用' : '关联主体/套餐已暂停'];
  }
  async function load(cursor) {
    if (busy) { pendingFilter = true; return; }
    busy = true; more.disabled = earlier.disabled = retry.disabled = true;
    const value = filter?.input.value || '';
    container.replaceChildren(notice('正在读取列表…'));
    try {
      const page = await businessPage(ctx.api, kind, cursor, value ? {[key]: value} : {});
      if (value !== (filter?.input.value || '')) { pendingFilter = true; return; }
      rows = page; after = cursor; state.after = after; state.previous = [...previous]; state[key] = value;
      container.replaceChildren(table(columns, rows.map(cells), '暂无记录，可通过上方新建按钮建立' + businessKinds[kind]));
      more.hidden = page.length < 20; earlier.hidden = !previous.length; error.replaceChildren();
    } catch (failure) { container.replaceChildren(); more.hidden = true; error.replaceChildren(notice('读取失败，当前数量未知：' + failure.message, 'error')); }
    finally { busy = false; more.disabled = earlier.disabled = retry.disabled = false; if (pendingFilter) { pendingFilter = false; previous = []; await load(0); } }
  }
  if (filter) filter.input.addEventListener('change', () => { previous = []; state[key] = filter.input.value; ctx.updateParams?.({[key]: filter.input.value}); load(0); });
  await load(after);
  return h('div', {}, filter ? h('section', {class: 'card query-panel'}, h('h2', {}, '筛选条件'), filter.node) : null, error, container, h('div', {class: 'actions'}, earlier, more, retry));
}

/** 关联页按正式API逐页读，不显示未经证实的总数。 */
async function relatedBusiness(ctx, kind, filter) {
  const body = h('div'), error = h('div', {'aria-live': 'polite'}); let after = 0, busy = false;
  async function load() {
    if (busy) return; busy = true; more.disabled = true;
    try {
      const rows = await businessPage(ctx.api, kind, after, filter);
      if (rows.length) after = rows.at(-1).id;
      body.append(table(kind === 'apps' ? ['应用', '代码', '状态'] : ['套餐', 'UTC期间', '期间状态'], rows.map(row => kind === 'apps' ? [viewLink(ctx, 'apps', row.name, {id: row.id}), row.code, statusLabel(row.status)] : [row.planName + ' / V' + row.planVersionSnapshot, row.effectiveAt + ' 至 ' + row.expiresAt, statusLabel(row.periodState)]), '当前页暂无关联记录'));
      more.hidden = rows.length < 20; error.replaceChildren();
    } catch (failure) { error.replaceChildren(notice('关联数据未取得：' + failure.message, 'error')); }
    finally { busy = false; more.disabled = false; }
  }
  const more = button('读取更多关联记录', load);
  // 显式调用相同读取函数，失败保留重试入口，不把异常转换成空关联。
  const wrapper = h('div', {}, body, error, more);
  return {wrapper, load};
}

async function businessDetail(ctx, kind) {
  // 1. 深链接始终重新查询正式详情，不用列表缓存冒充当前对象。
  const epoch = ctx.secretEpoch;
  const row = await ctx.api.request('/admin/' + kind + '/' + Number(ctx.params.id));
  if (epoch !== ctx.secretEpoch) return h('div');
  ctx.heading(row.name, businessKinds[kind] + '详情', permissionButton('编辑' + businessKinds[kind], () => editBusiness(ctx, kind, row), ctx.role, true, true));
  const back = h('div', {class: 'object-actions'}, viewLink(ctx, kind, '返回' + businessKinds[kind] + '列表'));
  const info = h('section', {class: 'card'}, h('h2', {}, '基本信息'), facts([['名称', row.name], ['代码', row.code], ['状态', statusLabel(row.status)], ...(kind === 'apps' ? [['所属客户', row.developerName || '未取得']] : []), ...(kind === 'plans' ? [['每月准入单位', row.monthlyUnits], ['固定版本', 'V' + row.planVersion]] : [])]), h('details', {}, h('summary', {}, '技术字段'), button('查看技术摘要', () => ctx.details(businessKinds[kind] + '技术摘要', row))));
  const sections = [info];
  // 2. 客户到应用、应用到订阅通过服务端过滤；不要求管理员重填关联ID。
  if (kind === 'developers') {
    const related = await relatedBusiness(ctx, 'apps', {developerId: row.id});
    sections.push(h('section', {class: 'card'}, h('h2', {}, '客户应用'), permissionButton('为此客户新建应用', () => editBusiness(ctx, 'apps', null, {developerId: row.id}), ctx.role, true), viewLink(ctx, 'apps', '查看应用列表', {developerId: row.id}), related.wrapper));
    await related.load();
  }
  if (kind === 'apps') {
    const tab = ctx.params.tab || 'info';
    const tabs = h('nav', {class: 'page-tabs', 'aria-label': '应用详情分区'}, [['info', '应用信息'], ['credentials', 'APP凭证'], ['subscriptions', '订阅'], ['usage', '配额与用量']].map(([key, label]) => {
      const link = viewLink(ctx, 'apps', label, {id: row.id, tab: key}); if (key === tab) link.setAttribute('aria-current', 'page'); return link;
    }));
    if (tab !== 'info') sections.length = 0;
    sections.unshift(tabs);
    if (tab === 'credentials') sections.push(appReceipt(ctx), h('section', {class: 'card'}, h('h2', {}, 'APP凭证'), button('查询与管理APP凭证', () => manageCredentials(ctx, row)), notice('只用于计量私有路由，与API接入中的旧Caller凭证不同。签发成功只展示一次，离开页面即清除。')));
    if (tab === 'subscriptions') {
      const related = await relatedBusiness(ctx, 'subscriptions', {applicationId: row.id});
      sections.push(h('section', {class: 'card'}, h('h2', {}, '应用订阅'), permissionButton('为此应用新增订阅', () => editBusiness(ctx, 'subscriptions', null, {applicationId: row.id}), ctx.role, true), related.wrapper)); await related.load();
    }
    // 3. 报表入口只携带业务引用；Token与完整响应不进入地址或历史记录。
    const actions = h('div', {class: 'object-actions'}, viewLink(ctx, 'subscriptions', '查看订阅', {applicationId: row.id}), viewLink(ctx, 'quota', '查看配额', {applicationId: row.id}), viewLink(ctx, 'usage', '查看用量', {applicationId: row.id}), viewLink(ctx, 'reconciliation', '查看对账', {applicationId: row.id}));
    sections.push(actions);
    if (tab === 'info') sections.push(h('div', {class: 'object-actions'}, viewLink(ctx, 'apps', '管理APP凭证', {id: row.id, tab: 'credentials'}), viewLink(ctx, 'apps', '新增或查看订阅', {id: row.id, tab: 'subscriptions'})));
  }
  return h('div', {}, back, sections);
}

/** 使用UTC月份选项产生ISO区间，最终仍由数据库UTC和月度唯一规则复验。 */
export function subscriptionMonth(choice, now = new Date()) {
  const offset = choice === 'NEXT' ? 1 : 0;
  return {effectiveAt: new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + offset, 1)).toISOString(), expiresAt: new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + offset + 1, 1)).toISOString()};
}
export async function credentialAction(api, role, id, action, scopes = '') {
  if (role !== 'ADMIN') throw new Error('只有ADMIN可以操作应用凭证');
  if (!['issue', 'rotate', 'revoke'].includes(action)) throw new Error('凭证操作无效');
  return api.request('/admin/apps/' + id + '/credentials' + (action === 'issue' ? '' : '/' + action), {method: 'POST', ...(action === 'revoke' ? {} : {body: {scopes: scopes.split(/[，,\s]+/).filter(Boolean)}})});
}
/** 一次性回执只驻留当前页面内存，不进入详情、localStorage或日志。 */
export function appReceipt(ctx) {
  if (!ctx.appIssued) return null;
  const issued = ctx.appIssued, token = h('input', {type: 'password', readOnly: true, autocomplete: 'off', 'aria-label': '一次性应用Token'}); token.value = issued.token;
  return h('section', {class: 'card'}, h('h2', {}, '领取应用凭证 · ' + issued.applicationName), notice('仅展示这一次；离开页面、退出或清空后无法找回。仅用于计量私有路由，有效期' + issued.expiresInSeconds + '秒。'), token,
    h('div', {class: 'actions'}, button('显示/隐藏', () => { token.type = token.type === 'password' ? 'text' : 'password'; }), button('复制凭证', async () => { try { await navigator.clipboard.writeText(token.value); ctx.flash('已复制，请安全交付。'); } catch { ctx.flash('浏览器不允许复制，请显示后手动复制。', 'error'); } }), button('清空凭证', () => { token.value = ''; ctx.appIssued = null; ctx.render(); })));
}
export async function manageCredentials(ctx, app) {
  try {
    // 1. 独立读取真实状态，不沿用A阶段固定占位或将错误当作未签发。
    const ticket = ctx.secretEpoch || 0, role = ctx.role;
    const state = await ctx.api.request('/admin/apps/' + app.id + '/credentials/status');
    if (ticket !== (ctx.secretEpoch || 0) || role !== ctx.role) return;
    if (state.status === 'FEATURE_NOT_READY') { ctx.flash('尚无可撤销的应用凭证；新签发需开启完整配额链路并确认节点就绪。'); return; }
    const issueAllowed = state.issuanceEnabled !== false;
    const fields = formFields([{name: 'scopes', label: '授权范围', value: '', required: false, help: '例如 demo:read，以逗号或空格分隔，必须包含计量私有API的requiredScope。轮换需要重新输入完整范围。', disabled: !issueAllowed}]);
    const actions = h('div', {class: 'actions'});
    const labels = {NOT_ISSUED: '尚未签发', ACTIVE: '凭证代次已启用（不代表Redis会话可用或未过期）', REVOKED: '已撤销'};
    // 功能关闭时不允许签发和轮换，但已存在凭证仍保留管理员紧急撤销入口。
    const availableActions = state.status === 'NOT_ISSUED' ? (issueAllowed ? [['issue', '首次签发']] : []) :
      [...(issueAllowed ? [['rotate', '轮换凭证']] : []), ['revoke', '撤销凭证']];
    for (const [action, label] of availableActions) {
      actions.append(permissionButton(label, () => {
        const scopes = fields.values().scopes, epoch = ctx.secretEpoch || 0;
        openDialog('确认' + label, notice(action === 'revoke' ? '当前凭证将失效，即使Redis清理异常，数据库代次也会阻断旧Token。' : '为“' + app.name + '”' + label + '。旧凭证会失效，授权范围：' + (scopes || '空') + '。新Token只展示一次。'), async () => {
          // 2. 只在服务器ACK后领取；失败不恢复旧Token，也不假装签发成功。
          const result = await credentialAction(ctx.api, ctx.role, app.id, action, scopes);
          if (ctx.role !== 'ADMIN' || epoch !== (ctx.secretEpoch || 0)) return;
          ctx.appIssued = action === 'revoke' ? null : {...result, applicationName: app.name};
          // 3. 重新绘制实际回执，离页/登出由应用入口统一销毁内存与DOM。
          await ctx.saved(action === 'revoke' ? '应用凭证已撤销。' : '凭证已签发，请在当前应用凭证页立即领取。');
        }, {submitLabel: '确认' + label, danger: action !== 'issue'});
      }, ctx.role, true));
    }
    openDialog(app.name + ' · 凭证管理', h('div', {}, notice('真实状态：' + (labels[state.status] || '未知') + '；代次：' + (state.generation ?? '未建立')),
      !issueAllowed ? notice('计量功能已关闭：签发、轮换暂不可用；已有凭证仍可由ADMIN紧急撤销。') : null,
      fields.node, actions, notice('余额与流水在“配额与账本”查看。凭证签发不会自动建立或发布计量规则。')), null);
  } catch (error) { ctx.flash('凭证状态未取得：' + error.message, 'error'); }
}

import {h, button, badge, notice, permissionButton, table, formFields, openDialog} from './ui.js';
export const businessKinds = {developers: '客户', apps: '应用', plans: '套餐', subscriptions: '订阅'};
const states = [{value: 'ACTIVE', label: '启用'}, {value: 'SUSPENDED', label: '暂停'}];
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
async function reference(api, kind, label, selected = '', optional = false) {
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
export async function editBusiness(ctx, kind, original = null) {
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
    if (kind === 'plans') definitions.push({name: 'monthlyUnits', label: '每月准入单位（套餐定义）', value: row.monthlyUnits ?? 5, type: 'number', min: 1, max: 1000000, step: 1, disabled: Boolean(original), help: '创建后额度不可修改；调整额度请新建套餐。A阶段不扣额度、不统计用量。'});
    let owner = null, application = null, plan = null;
    if (kind === 'apps') { owner = await reference(ctx.api, 'developers', '所属客户', row.developerId); owner.input.disabled = Boolean(original); }
    if (kind === 'subscriptions') {
      application = await reference(ctx.api, 'apps', '订阅应用'); plan = await reference(ctx.api, 'plans', '选择套餐');
      const now = new Date(), start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1)), end = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1));
      definitions.push({name: 'effectiveAt', label: '生效时间（UTC）', value: start.toISOString(), help: '当前月份可立即生效；排队变更只能下个UTC月首日00:00。初值来自本机，后端以数据库UTC时间复验。'});
      definitions.push({name: 'expiresAt', label: '到期时间（UTC，不含端点）', value: end.toISOString(), help: 'ISO8601并以Z结尾，毫秒精度。当前订阅到期端点可与次月订阅起点相接。'});
    }
    const fields = formFields(definitions), content = h('div', {}, notice(kind === 'subscriptions' ? '订阅区间为左闭右开；最多一个当前和一个次月排队。创建后不开放任意区间修改。' : '保存立即更新管理记录，不需要V1路由发布；不代表额度或应用凭证已启用。'), owner?.node, application?.node, plan?.node, fields.node);
    openDialog((original ? '编辑' : '新建') + businessKinds[kind], content, async () => {
      // 2. 名称选择映射为可信API引用；真正归属/状态/并发验证由服务器完成。
      const values = fields.values(); if (owner) values.developerId = owner.input.value;
      if (application) values.applicationId = application.input.value; if (plan) values.planId = plan.input.value;
      await saveBusiness(ctx.api, ctx.role, kind, values, original);
      // 3. 成功只说明管理数据保存，不宣称配额或发布生效。
      await ctx.saved(businessKinds[kind] + (kind === 'subscriptions' ? '已创建。' : '已保存。') + 'A阶段凭证与计量尚未启用。');
    }, {submitLabel: kind === 'subscriptions' ? '创建订阅' : '保存管理记录'});
  } catch (error) { ctx.flash(error.message, 'error'); }
}
export async function credentialsPlaceholder(ctx, app) {
  if (ctx.role !== 'ADMIN') return;
  try { await ctx.api.request('/admin/apps/' + app.id + '/credentials', {method: 'POST'}); ctx.flash('未收到预期的未启用响应，请联系负责人核对', 'error'); }
  catch (error) { ctx.flash(error.code === 'FEATURE_NOT_READY' ? 'A阶段尚未启用应用凭证，需B阶段身份隔离与配额验证后才能签发。' : error.message, error.code === 'FEATURE_NOT_READY' ? '' : 'error'); }
}
export async function renderBusiness(ctx, kind) {
  ctx.heading(businessKinds[kind] + '管理', '客户 → 应用 → 套餐 → 订阅。这里管理的是业务定义，A阶段尚无有效应用Token、额度余额或用量数据。', permissionButton('新建' + businessKinds[kind], () => editBusiness(ctx, kind), ctx.role, true, true));
  const container = h('section', {class: 'card'}), error = h('div', {'aria-live': 'polite'}); let rows = [], cursor = 0, generation = 0, busy = false, pendingReset = false;
  const more = button('读取下一页', () => load(false)), retry = button('重新读取本页', () => load(true));
  let filter = null; if (kind === 'apps') filter = await reference(ctx.api, 'developers', '按客户查看应用', '', true);
  if (kind === 'subscriptions') filter = await reference(ctx.api, 'apps', '按应用查看订阅', '', true);
  const columns = kind === 'developers' ? ['客户名称', '代码', '管理状态', '创建时间', '操作'] : kind === 'apps' ? ['应用名称', '代码', '所属客户', '状态', '凭证', '操作'] : kind === 'plans' ? ['套餐名称', '代码', '每月准入单位', '固定版本', '状态', '操作'] : ['应用', '套餐/冻结版本', 'UTC期间', '期间状态', '关联状态', '操作'];
  function cells(row) {
    const actions = h('div', {class: 'actions'}, button('详情', () => ctx.details(businessKinds[kind] + '详情', row)), kind !== 'subscriptions' ? permissionButton('编辑', () => editBusiness(ctx, kind, row), ctx.role, true) : null);
    const state = badge(row.status === 'ACTIVE' ? '启用' : row.status === 'SUSPENDED' ? '暂停' : '已到期', row.status === 'ACTIVE' ? 'good' : 'warn');
    if (kind === 'developers') return [row.name, row.code, state, row.createdAt || '未取得', actions];
    if (kind === 'apps') { actions.append(permissionButton('凭证待启用', () => credentialsPlaceholder(ctx, row), ctx.role, true)); return [row.name, row.code, row.developerName, state, 'A阶段未启用', actions]; }
    if (kind === 'plans') return [row.name, row.code, row.monthlyUnits, 'V' + row.planVersion, state, actions];
    return [row.applicationName, row.planName + ' / V' + row.planVersionSnapshot, row.effectiveAt + ' 至 ' + row.expiresAt, badge(({CURRENT: '当前期间', SCHEDULED: '待生效', EXPIRED: '已到期'})[row.periodState] || '未取得'), row.parentsActive ? '关联主体启用' : '关联主体/套餐已暂停', actions];
  }
  async function load(reset) {
    if (busy) { if (reset) pendingReset = true; return; } busy = true; more.disabled = true; retry.disabled = true; const ticket = ++generation;
    try {
      const selected = filter?.input.value, key = kind === 'apps' ? 'developerId' : 'applicationId';
      const page = await businessPage(ctx.api, kind, reset ? 0 : cursor, selected ? {[key]: selected} : {});
      if (ticket !== generation || selected !== filter?.input.value) return; rows = reset ? page : [...rows, ...page]; cursor = rows.at(-1)?.id || 0;
      container.replaceChildren(table(columns, rows.map(cells), '暂无记录。ADMIN可通过上方新建按钮建立' + businessKinds[kind] + '。')); more.hidden = page.length < 20; error.replaceChildren();
    } catch (failure) { error.replaceChildren(notice('读取失败，当前数量未知：' + failure.message, 'error')); if (reset || !rows.length) container.replaceChildren(); more.hidden = true; }
    finally { busy = false; more.disabled = false; retry.disabled = false; if (pendingReset) { pendingReset = false; await load(true); } }
  }
  if (filter) filter.input.addEventListener('change', () => load(true));
  await load(true);
  return h('div', {}, notice('应用凭证待B阶段启用；套餐定义不是已发放余额，订阅不改变V1路由与旧Caller。'), filter?.node, error, container, h('div', {class: 'actions'}, more, retry));
}

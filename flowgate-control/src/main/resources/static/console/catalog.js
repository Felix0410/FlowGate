import {h, badge, notice, permissionButton, searchableTable, formFields, openDialog, button, viewLink, facts, table} from './ui.js';
import {canWrite} from './api.js';
export const catalogFields = {
  services: ['code', 'name', 'upstreamUri', 'enabled'],
  apis: ['serviceId', 'method', 'publicPath', 'upstreamPath', 'isPublic', 'requiredScope', 'enabled'],
  routes: ['apiId', 'routeId', 'priority', 'enabled']
};
const names = {services: '服务', apis: 'API', routes: '路由'};
export const apiName = api => api ? api.method + ' ' + api.publicPath : '关联API未取得';
export function catalogBody(kind, values, original = {}) {
  // 1. 完整回填后只提交后端白名单字段；数字/布尔显式转换，避免字符串false被误判。
  const merged = {...original, ...values};
  const body = Object.fromEntries(catalogFields[kind].map(key => [key, merged[key]]));
  for (const key of ['serviceId', 'apiId', 'priority']) if (key in body) body[key] = Number(body[key]);
  for (const key of ['enabled', 'isPublic']) if (key in body) body[key] = body[key] === true || body[key] === 'true';
  if (kind === 'apis' && !body.isPublic && !/^[A-Za-z0-9:_-]{1,200}$/.test(body.requiredScope || '')) throw new Error('私有API需要有效授权范围，例如 demo:read');
  return body;
}
export async function saveCatalog(api, role, kind, values, original) {
  if (!canWrite(role)) throw new Error('只读角色不能保存草稿');
  return api.request('/admin/' + kind + (original ? '/' + original.id : ''), {method: original ? 'PUT' : 'POST', body: catalogBody(kind, values, original)});
}
function definitions(ctx, kind, row) {
  const common = {name: 'enabled', label: '草稿状态', value: row.enabled ?? true, options: [{value: 'true', label: '启用'}, {value: 'false', label: '停用'}], help: '保存后仍需发布。停用父服务/API前，请同时停用其路由和相关启用规则。'};
  if (kind === 'services') return [
    {name: 'name', label: '服务名称', value: row.name, placeholder: '例如：订单服务', maxlength: 200},
    {name: 'code', label: '唯一服务代码', value: row.code, placeholder: '例如：order-service', maxlength: 200},
    {name: 'upstreamUri', label: '上游地址', value: row.upstreamUri || '', type: 'url', placeholder: 'http://127.0.0.1:8085', help: '必须命中部署白名单，不含路径。地址不受支持时需由部署负责人配置白名单。'}, common];
  if (kind === 'apis') return [
    {name: 'serviceId', label: '所属服务', value: row.serviceId ?? '', options: [{value: '', label: '请选择服务'}, ...(ctx.data.services || []).map(s => ({value: s.id, label: s.name + ' · ' + s.code}))], help: '没有服务？先到服务管理创建。'},
    {name: 'method', label: '请求方法', value: row.method || 'GET', options: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].map(value => ({value, label: value}))},
    {name: 'publicPath', label: '网关入口路径', value: row.publicPath, placeholder: '/api/orders/{id}', help: '必须以/api/开头；{id}表示一个路径变量，不支持通配**。'},
    {name: 'upstreamPath', label: '上游接口路径', value: row.upstreamPath, placeholder: '/demo/fast/{id}', help: '由网关转发到服务的实际路径，变量须与入口匹配。'},
    {name: 'isPublic', label: '访问权限', value: row.isPublic ?? true, options: [{value: 'true', label: '公开访问'}, {value: 'false', label: '需要调用方授权'}]},
    {name: 'requiredScope', label: '所需授权范围', value: row.requiredScope || '', required: false, placeholder: 'demo:read', help: '私有API必填；调用方必须具有相同范围。公开API不校验此字段。'}, common];
  return [
    {name: 'routeId', label: '稳定路由名称', value: row.routeId, placeholder: '例如：order-query', pattern: '[A-Za-z0-9_-]+', help: '仅字母、数字、横线、下划线。规则与指标会引用此名称。'},
    {name: 'apiId', label: '关联API', value: row.apiId ?? '', options: [{value: '', label: '请选择API'}, ...(ctx.data.apis || []).map(a => ({value: a.id, label: apiName(a)}))], help: '方法和路径自动沿用API，不需要重复填写。'},
    {name: 'priority', label: '匹配优先级', value: row.priority ?? 0, type: 'number', step: 1, help: '默认0；请避免重叠路径，发布时会再次校验。'}, common];
}
export function editCatalog(ctx, kind, original = null, defaults = {}) {
  if (!canWrite(ctx.role)) return;
  const fields = formFields(definitions(ctx, kind, original || defaults));
  const content = h('div', {}, notice('本次只保存草稿，不立即影响正在代理的请求。'), fields.node);
  openDialog((original ? '编辑' : '接入新') + names[kind], content, async () => {
    await saveCatalog(ctx.api, ctx.role, kind, fields.values(), original);
    await ctx.saved(names[kind] + '草稿已保存，尚未发布。请前往发布中心确认后发布。');
  });
}
export function renderCatalog(ctx, kind) {
  const records = ctx.data[kind];
  if (ctx.params?.id) return catalogDetail(ctx, kind, records);
  ctx.heading(names[kind] + '管理', '', permissionButton('新建' + names[kind], () => editCatalog(ctx, kind), ctx.role, false, true));
  if (records === null) return ctx.unavailable('目录未取得，请重新读取。');
  const lookup = (rows, id, name) => (rows || []).find(r => r.id === id)?.[name] || '关联信息未取得';
  const rows = record => {
    const state = badge(record.enabled ? '草稿启用' : '草稿停用', record.enabled ? 'good' : '');
    const label = kind === 'services' ? record.name : kind === 'apis' ? record.publicPath : record.routeId;
    const actions = viewLink(ctx, kind, '查看', {id: record.id});
    const technical = text => h('span', {class: 'mono technical'}, text);
    const name = viewLink(ctx, kind, label, {id: record.id});
    if (kind === 'services') return [name, technical(record.code), technical(record.upstreamUri), state, actions];
    if (kind === 'apis') return [badge(record.method), name, lookup(ctx.data.services, record.serviceId, 'name'), record.isPublic ? '公开' : '授权 · ' + record.requiredScope, state, actions];
    return [name, technical(apiName((ctx.data.apis || []).find(a => a.id === record.apiId))), record.priority, state, actions];
  };
  const headers = kind === 'services' ? ['服务名称', '服务代码', '上游地址', '草稿状态', '操作'] : kind === 'apis' ? ['方法', '入口路径', '所属服务', '访问权限', '草稿状态', '操作'] : ['路由名称', '关联API', '优先级', '草稿状态', '操作'];
  return h('div', {}, notice('草稿保存后仍需发布'), searchableTable(headers, records || [], rows, '尚未创建' + names[kind] + '，可从上方新建', ctx.state?.(kind) || {}));
}

/** 目录关联仅从已完整读取的正式目录计算，不推测未取得的关系。 */
function catalogDetail(ctx, kind, records) {
  const row = records?.find(item => item.id === Number(ctx.params.id));
  ctx.heading(row ? row.name || row.publicPath || row.routeId : names[kind] + '详情', '草稿配置', row ? permissionButton('编辑' + names[kind], () => editCatalog(ctx, kind, row), ctx.role, false, true) : null);
  const back = h('div', {class: 'object-actions'}, viewLink(ctx, kind, '返回' + names[kind] + '列表'));
  if (!row) return h('div', {}, back, ctx.unavailable(records === null ? '目录未取得，请重新读取。' : '对象不存在或已不可访问。'));
  // 1. 基础信息使用业务字段；原始响应仅在明确展开的技术摘要中展示。
  const fields = kind === 'services' ? [['服务代码', row.code], ['上游地址', row.upstreamUri]] : kind === 'apis' ? [['请求方法', row.method], ['入口路径', row.publicPath], ['上游路径', row.upstreamPath], ['访问权限', row.isPublic ? '公开' : '需授权 · ' + row.requiredScope]] : [['路由名称', row.routeId], ['优先级', row.priority]];
  const sections = [h('section', {class: 'card'}, h('h2', {}, '基础信息'), facts([...fields, ['草稿状态', row.enabled ? '启用，仍需发布' : '停用，仍需发布']]), h('details', {}, h('summary', {}, '技术摘要'), button('查看字段', () => ctx.details('草稿技术摘要', row))))];
  // 2. 有完整目录才建立关联；失败显式未知，不能以空表冒充无关联。
  if (kind === 'services') {
    const related = ctx.data.apis?.filter(api => api.serviceId === row.id);
    sections.push(h('section', {class: 'card'}, h('h2', {}, '服务下的API'), permissionButton('在此服务新建API', () => editCatalog(ctx, 'apis', null, {serviceId: row.id}), ctx.role), related ? table(['API', '操作'], related.map(api => [apiName(api), viewLink(ctx, 'apis', '查看API', {id: api.id})]), '尚无关联API') : notice('API目录未取得，关联关系未知', 'error')));
  }
  if (kind === 'apis') {
    const routes = ctx.data.routes?.filter(route => route.apiId === row.id);
    sections.push(h('section', {class: 'card'}, h('h2', {}, '关联路由'), permissionButton('为此API新建路由', () => editCatalog(ctx, 'routes', null, {apiId: row.id}), ctx.role), routes ? table(['路由', '操作'], routes.map(route => [route.routeId, viewLink(ctx, 'routes', '查看路由', {id: route.id})]), '尚无关联路由') : notice('路由目录未取得，关联关系未知', 'error')));
  }
  // 3. 策略绑定不能仅凭目录推断；带真实目标进入策略页再读取正式绑定。
  const targetType = {services: 'SERVICE', apis: 'API', routes: 'ROUTE'}[kind];
  sections.push(h('section', {class: 'card'}, h('h2', {}, '保护规则'), viewLink(ctx, 'rate', '查看此对象的限速规则', {targetType, targetId: row.id}), h('p', {class: 'meta'}, '规则页将读取真实绑定；无法读取时显示未知')));
  return h('div', {}, back, sections, viewLink(ctx, 'releases', '前往发布中心'));
}

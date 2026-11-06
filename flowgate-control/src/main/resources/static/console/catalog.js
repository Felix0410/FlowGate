import {h, badge, notice, permissionButton, searchableTable, formFields, openDialog, button} from './ui.js';
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
export function editCatalog(ctx, kind, original = null) {
  if (!canWrite(ctx.role)) return;
  const fields = formFields(definitions(ctx, kind, original || {}));
  const content = h('div', {}, notice('本次只保存草稿，不立即影响正在代理的请求。'), fields.node);
  openDialog((original ? '编辑' : '接入新') + names[kind], content, async () => {
    await saveCatalog(ctx.api, ctx.role, kind, fields.values(), original);
    await ctx.saved(names[kind] + '草稿已保存，尚未发布。请前往发布中心确认后发布。');
  });
}
export function renderCatalog(ctx, kind) {
  const records = ctx.data[kind];
  ctx.heading(names[kind] + '管理', '创建、编辑和启停接入草稿。发布作用于全部配置，保存草稿不代表网关已应用。', permissionButton('新建' + names[kind], () => editCatalog(ctx, kind), ctx.role, false, true));
  if (records === null) return ctx.unavailable('目录未取得，请重新读取。');
  const lookup = (rows, id, name) => (rows || []).find(r => r.id === id)?.[name] || '关联信息未取得';
  const rows = record => {
    const state = badge(record.enabled ? '草稿启用' : '草稿停用', record.enabled ? 'good' : '');
    const actions = h('div', {class: 'actions'}, button('详情', () => ctx.details(names[kind] + '草稿详情', record)), permissionButton('编辑', () => editCatalog(ctx, kind, record), ctx.role));
    if (kind === 'services') return [record.name, record.code, record.upstreamUri, state, actions];
    if (kind === 'apis') return [badge(record.method), record.publicPath, lookup(ctx.data.services, record.serviceId, 'name'), record.isPublic ? '公开' : '授权 · ' + record.requiredScope, state, actions];
    return [record.routeId, apiName((ctx.data.apis || []).find(a => a.id === record.apiId)), record.priority, state, actions];
  };
  const headers = kind === 'services' ? ['服务名称', '服务代码', '上游地址', '草稿状态', '操作'] : kind === 'apis' ? ['方法', '入口路径', '所属服务', '访问权限', '草稿状态', '操作'] : ['路由名称', '关联API', '优先级', '草稿状态', '操作'];
  return h('div', {}, notice('这是当前草稿目录。后端没有逐对象发布差异接口，因此不把这张表标成“全部已上线”。发布版本和节点应用请查看发布中心。'), searchableTable(headers, records || [], rows, '尚未创建' + names[kind] + '。点击上方新建按钮开始；已有草稿时可直接编辑。'));
}

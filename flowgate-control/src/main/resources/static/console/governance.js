import {h, badge, notice, permissionButton, searchableTable, formFields, openDialog} from './ui.js';
import {canWrite} from './api.js';
import {apiName} from './catalog.js';
export const policyTypes = {
  USAGE_QUOTA: {name: '月度计量准入', scopes: ['API', 'ROUTE'], description: '仅私有启用API/路由。成功Grant即消耗单位，下游失败也不退款；默认开关关闭，完整接流名单及能力检查通过才允许发布。', parameters: [['unitsPerCall', '每次准入消耗单位', 1, 1, 100, '1–100整数，不是速率或并发。']]},
  RATE_LIMIT: {name: '速率限制', scopes: ['ROUTE', 'SERVICE', 'API', 'GLOBAL', 'CALLER'], description: '控制短时额度和持续每秒速率。跨网关共享Redis预算，超额请求返回429。', parameters: [
    ['capacity', '短时容量（次）', 5, 1, 1000000, '令牌桶最多保留多少次额度。'], ['refillPerSecond', '每秒补充（次）', 2, 1, 1000000, '持续允许的每秒速率，不是同时处理数量。']]},
  CONCURRENCY: {name: '并发保护', scopes: ['ROUTE', 'SERVICE'], description: '限制单个网关同时处理的请求数，满载立即503。两个网关的许可独立，不是全局总并发。', parameters: [['maxConcurrent', '每台网关在途上限', 1, 1, 1000000, '从请求进入到终止期间占用许可。']]},
  TIMEOUT: {name: '请求超时', scopes: ['ROUTE', 'API', 'SERVICE'], description: '上游超过时限返回504。同一个请求命中多条超时时取最小值。网关超时不代表下游任务立即中止。', parameters: [['timeoutMs', '响应超时（毫秒）', 1000, 50, 10000, '允许50–10000ms；例如200ms慢请求保护。']]},
  CIRCUIT_BREAKER: {name: '熔断保护', scopes: ['ROUTE'], description: '对单条路由记录失败，达到阈值短路503，等待后进行半开探测。参数变更会产生新revision；旧实例暂未自动回收。', parameters: [
    ['failureRateThreshold', '失败率阈值（%）', 50, 1, 100, '达到该百分比时打开。'], ['slidingWindowSize', '统计窗口（次）', 10, 2, 100, '最近多少次请求参与统计。'], ['minimumNumberOfCalls', '最小样本（次）', 5, 1, 100, '须不超过统计窗口。'], ['waitDurationMs', '打开后等待（毫秒）', 3000, 500, 60000, '之后半开，两次成功才恢复，任一次失败重新打开。']]}
};
const scopes = {GLOBAL: '全局', SERVICE: '服务', API: 'API', ROUTE: '路由', CALLER: '调用方'};
export function policyParameters(type, values) {
  const definition = policyTypes[type]; if (!definition) throw new Error('规则类型无效');
  const parameters = {};
  for (const [name, label, , min, max] of definition.parameters) {
    const value = Number(values[name]);
    if (!Number.isInteger(value) || value < min || value > max) throw new Error(label + '须为' + min + '–' + max + '的整数');
    parameters[name] = value;
  }
  if (type === 'CIRCUIT_BREAKER') {
    if (parameters.minimumNumberOfCalls > parameters.slidingWindowSize) throw new Error('最小样本不能大于统计窗口');
    parameters.fallbackMode = values.fallbackMode || 'NONE';
  }
  return parameters;
}
export async function savePolicy(api, role, values, original, bindings = []) {
  if (!canWrite(role)) throw new Error('只读角色不能修改规则');
  const body = {code: values.code, type: original?.type || values.type, scope: original?.scope || values.scope,
    enabled: values.enabled === 'true' || values.enabled === true};
  body.parametersJson = JSON.stringify(policyParameters(body.type, values));
  const row = await api.request('/admin/policies' + (original ? '/' + original.id : ''), {method: original ? 'PUT' : 'POST', body});
  // 1. 已有绑定只读，保存参数绝不暗中移动目标；无绑定时允许补建一次。
  if (!bindings.length) {
    try { await api.request('/admin/policies/' + row.id + '/bindings', {method: 'POST', body: {targetType: body.scope, targetId: Number(values.targetId)}}); }
    catch (error) { error.savedPolicy = row; error.message = '规则草稿已保存，但目标绑定失败。请修正目标后再次保存（不会重复创建规则）。' + error.message; throw error; }
  }
  return row;
}
function optionsFor(ctx, scope) {
  if (scope === 'GLOBAL') return [{value: 0, label: '全局共享预算'}];
  const rows = scope === 'CALLER' ? ctx.callers : ctx.data[{SERVICE: 'services', API: 'apis', ROUTE: 'routes'}[scope]] || [];
  return rows.map(row => ({value: row.id, label: scope === 'SERVICE' ? row.name : scope === 'API' ? apiName(row) : scope === 'ROUTE' ? row.routeId : row.username}));
}
export function editPolicy(ctx, type, original = null, bindings = []) {
  // 1. 完整回填真实参数；既有绑定只读，未取得事实的规则不会进入编辑。
  if (!canWrite(ctx.role)) return;
  let saved = original;
  const definition = policyTypes[type]; let parameters = {};
  try { parameters = JSON.parse(original?.parametersJson || '{}'); } catch { ctx.flash('规则参数无法读取，请检查技术详情', 'error'); return; }
  const base = formFields([
    {name: 'code', label: '规则名称（唯一）', value: original?.code || '', placeholder: '例如：查询接口超时', maxlength: 200},
    {name: 'scope', label: '保护对象类型', value: original?.scope || definition.scopes[0], options: definition.scopes.map(value => ({value, label: scopes[value]})), disabled: Boolean(original), help: original ? '既有规则的类型和作用域固定。需要另一作用域请新建规则。' : '先选择对象类型，再选择具体业务对象。'},
    {name: 'enabled', label: '草稿状态', value: original?.enabled ?? true, options: [{value: 'true', label: '启用'}, {value: 'false', label: '停用'}]}
  ]);
  const target = formFields([{name: 'targetId', label: '具体保护对象', value: '', options: [], help: '没有可选对象？先创建相应目录。Caller只提供本次登录签发记录，不假装全量列表。'}]);
  const locked = bindings.length > 0;
  const refreshTargets = () => {
    const input = target.controls.targetId;
    input.replaceChildren(...optionsFor(ctx, base.controls.scope.value).map(option => h('option', {value: option.value}, option.label)));
    input.value = input.options?.[0]?.value || '';
  };
  refreshTargets(); base.controls.scope.addEventListener('change', refreshTargets);
  const fields = formFields(definition.parameters.map(([name, label, initial, min, max, help]) => ({name, label, value: parameters[name] ?? initial, type: 'number', step: 1, min, max, help})));
  const fallback = type === 'CIRCUIT_BREAKER' ? formFields([{name: 'fallbackMode', label: '不可用时的响应', value: parameters.fallbackMode || 'NONE', options: [{value: 'NONE', label: '返回真实错误（不降级）'}, {value: 'STATIC_JSON', label: 'GET静态不可用提示（仍返回503）'}], help: '仅GET路由允许静态降级，绝不返回假成功。'}]) : null;
  const content = h('div', {}, notice(definition.description), base.node,
    locked ? notice('已绑定：' + bindings.map(b => b.targetLabel).join('、') + '。本次仅修改参数/启停；当前后端不支持移动或删除绑定。') : target.node,
    fields.node, fallback?.node, notice('规则保存为草稿，前往发布中心发布，等待真实节点应用后才执行。'));
  openDialog((original ? '编辑' : '新建') + definition.name, content, async () => {
    // 2. GET-only在表单边界提前解释，后端发布仍会独立复验。
    const values = {...base.values(), ...target.values(), ...fields.values(), ...fallback?.values(), type};
    if (!locked && !optionsFor(ctx, base.controls.scope.value).some(option => String(option.value) === values.targetId)) throw new Error('请选择真实存在的保护对象');
    if (values.fallbackMode === 'STATIC_JSON') {
      const ids = locked ? bindings.map(b => b.targetId) : [Number(values.targetId)];
      if (!ids.every(id => { const route = (ctx.data.routes || []).find(r => r.id === id); return (ctx.data.apis || []).find(a => a.id === route?.apiId)?.method === 'GET'; })) throw new Error('静态降级仅支持已关联GET API的路由');
    }
    try { await savePolicy(ctx.api, ctx.role, values, saved, bindings); }
    catch (error) { if (error.savedPolicy) { saved = error.savedPolicy; base.controls.scope.disabled = true; } throw error; }
    // 3. 全部保存成功才显示草稿成功；部分失败保留表单和已创建ID供修正。
    await ctx.saved('规则及绑定草稿已保存，尚未发布。请前往发布中心。');
  });
}
export async function renderGovernance(ctx, mode) {
  const types = mode === 'quota-policy' ? ['USAGE_QUOTA'] : mode === 'rate' ? ['RATE_LIMIT'] : mode === 'concurrency' ? ['CONCURRENCY'] : ['TIMEOUT', 'CIRCUIT_BREAKER'];
  ctx.heading(mode === 'quota-policy' ? '计量规则' : mode === 'rate' ? '速率限制' : mode === 'concurrency' ? '并发保护' : '故障保护', '先选择业务目标，再设置保护参数。所有规则以发布版本与网关实际应用为准。', types.length === 1 ? permissionButton('新建规则', () => editPolicy(ctx, types[0]), ctx.role, false, true) : null);
  if (ctx.data.policies === null) return ctx.unavailable('规则未读取，请重试。');
  const records = (ctx.data.policies || []).filter(p => types.includes(p.type));
  const bindings = new Map();
  await Promise.all(records.map(async p => { try { bindings.set(p.id, await ctx.api.request('/admin/policies/' + p.id + '/bindings')); } catch { bindings.set(p.id, null); } }));
  const rows = record => {
    const bound = bindings.get(record.id); let parameters;
    try { const values = JSON.parse(record.parametersJson); parameters = policyTypes[record.type].parameters.map(([key, label]) => label + ' ' + values[key]).join(' / '); }
    catch { parameters = '参数未能解析'; }
    return [record.code, policyTypes[record.type].name, scopes[record.scope], bound === null ? '绑定未取得（请重读）' : bound.length ? bound.map(b => b.targetLabel).join('、') : '未绑定', parameters, badge(record.enabled ? '草稿启用' : '草稿停用', record.enabled ? 'good' : ''),
      permissionButton('编辑', () => editPolicy(ctx, record.type, record, bound), bound === null ? 'VIEWER' : ctx.role)];
  };
  return h('div', {}, types.length > 1 ? h('div', {class: 'actions card'}, types.map(type => permissionButton('新建' + policyTypes[type].name, () => editPolicy(ctx, type), ctx.role))) : null,
    notice(types.map(type => policyTypes[type].description).join(' ')), searchableTable(['规则名称', '类型', '范围', '真实绑定目标', '参数摘要', '草稿状态', '操作'], records, rows, '尚无此类规则。可按业务需要新建；无规则不表示依赖故障。'));
}

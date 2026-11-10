/** 七个业务域只组织已有能力；叶子key保持与原产品模块一致。 */
export const domains = [
  {key: 'dashboard', label: '工作台', tabs: [['dashboard', '概览']]},
  {key: 'access', label: 'API 接入', tabs: [['services', '服务'], ['apis', 'API'], ['routes', '路由'], ['callers', '旧调用方凭证']]},
  {key: 'business', label: '客户业务', tabs: [['developers', '客户'], ['apps', '应用'], ['plans', '套餐'], ['subscriptions', '订阅'], ['market', 'API市场']]},
  {key: 'policies', label: '治理策略', tabs: [['rate', '限速'], ['concurrency', '并发'], ['protection', '故障保护'], ['quota-policy', '计量规则']]},
  {key: 'usage', label: '用量与对账', tabs: [['quota', '余额与账本'], ['usage', '用量统计'], ['reconciliation', '对账结果']]},
  {key: 'releases', label: '发布中心', tabs: [['releases', '版本与节点']]},
  {key: 'operations', label: '运行监控', tabs: [['monitoring', '网关节点'], ['async-events', '异步事件'], ['audits', '操作审计']]}
];
export const views = domains.flatMap(domain => domain.tabs.map(([key, label]) => [key, label, domain.label]));
export const domainFor = view => domains.find(domain => domain.tabs.some(([key]) => key === view)) || domains[0];
const validId = value => /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value));
/** 地址只允许非敏感业务引用及日期，绝不将任意响应或表单转为URL。 */
export function safeParams(view, source = {}) {
  const input = source instanceof URLSearchParams ? Object.fromEntries(source) : source;
  const result = {};
  // 1. 先收窄每个字段的业务语义；对象编号不是访问授权，最终仍由API校验权限。
  if (['services', 'apis', 'routes', 'developers', 'apps', 'plans'].includes(view) && validId(String(input.id || ''))) result.id = String(input.id);
  if (view === 'apps' && validId(String(input.developerId || ''))) result.developerId = String(input.developerId);
  if (['subscriptions', 'quota', 'usage', 'reconciliation'].includes(view) && validId(String(input.applicationId || ''))) result.applicationId = String(input.applicationId);
  if (['rate', 'concurrency', 'protection', 'quota-policy'].includes(view) && ['API', 'ROUTE', 'SERVICE'].includes(input.targetType) && validId(String(input.targetId || ''))) {
    result.targetType = input.targetType; result.targetId = String(input.targetId);
  }
  // 2. 日期与月份独立保留，日报范围不能被误解成实时月度余额。
  if (['quota', 'usage', 'reconciliation'].includes(view)) {
    if (/^\d{4}(0[1-9]|1[0-2])$/.test(input.period || '')) result.period = input.period;
    if (validId(String(input.apiId || ''))) result.apiId = String(input.apiId);
    for (const name of ['from', 'to']) if (/^\d{4}-\d{2}-\d{2}$/.test(input[name] || '') && Number.isFinite(Date.parse(input[name] + 'T00:00:00Z'))) result[name] = input[name];
  }
  if (view === 'apps' && ['info', 'credentials', 'subscriptions', 'usage'].includes(input.tab)) result.tab = input.tab;
  // 3. 没有白名单的字段全部丢弃，包括Token、password、response、任意搜索文本。
  return result;
}
export function routeHash(view, params = {}) {
  const domain = domainFor(view), leaf = domain.tabs.some(([key]) => key === view) ? view : 'dashboard';
  const path = leaf === 'dashboard' ? 'dashboard' : domain.key + '/' + leaf;
  const query = new URLSearchParams(safeParams(leaf, params)).toString();
  return '#' + path + (query ? '?' + query : '');
}
export function parseRoute(hash = '') {
  const [path, query = ''] = String(hash).replace(/^#\/?/, '').split('?');
  const legacy = views.find(([key]) => key === path)?.[0];
  const domain = domains.find(item => path.startsWith(item.key + '/'));
  const leaf = legacy || domain?.tabs.find(([key]) => domain.key + '/' + key === path)?.[0] || 'dashboard';
  return {view: leaf, params: safeParams(leaf, new URLSearchParams(query))};
}

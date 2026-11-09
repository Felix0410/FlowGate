import {h, badge, viewLink} from './ui.js';
const row = (id, label, state, reason, action, view) => ({id, label, state, reason, action, view});
/** 仅汇总既有认证接口的真实事实；TCP/开关/历史计数不替代可信门禁或当前运行状态。 */
export function readinessFacts(data) {
  const {current, nodes, quota, asyncSummary: async} = data;
  // 1. 配置持久化与实际应用独立核对；必须包含checksum，缺字段不能涂绿。
  let v1 = row('v1', 'V1 路由', 'UNKNOWN', '发布或节点事实未取得', '重新读取发布与节点状态', 'releases');
  if (current?.version === 0) v1 = row('v1', 'V1 路由', 'BLOCKED', '尚未发布非计量初版', '先登记公开Demo API并发布，等待网关确认', 'releases');
  else if (current && Array.isArray(nodes)) {
    const synced = nodes.length && current.checksum && nodes.every(node => node.freshness === 'ONLINE' && node.appliedVersion === current.version && node.checksum === current.checksum);
    v1 = row('v1', 'V1 路由', synced ? 'READY' : 'BLOCKED', synced ? '已上报节点确认当前版本与校验和' : '节点未确认、过期或版本/校验和不同', '核对当前接流节点与历史记录', 'monitoring');
  }
  // 2. B直接使用后端与签发/发布相同的只读资格检查，前端不复制或放宽门禁。
  const b = !quota ? row('b', 'B 月度配额', 'UNKNOWN', '计量状态未取得', '检查权限/数据库，再重新读取', 'releases') : row('b', 'B 月度配额', quota.activationEnabled === false ? 'BLOCKED' : ['READY', 'BLOCKED', 'UNKNOWN'].includes(quota.readiness) ? quota.readiness : 'UNKNOWN', quota.reason || '当前后端未提供完整门禁证明', quota.code === 'INITIAL_RELEASE_REQUIRED' ? '先发布非计量初版并等待可信能力' : '查看本地交付手册；签发和发布仍由后端实时复验', 'releases');
  let c = row('c', 'C 异步用量', 'UNKNOWN', '异步状态未取得', '检查C两表、权限和Control状态', 'async-events');
  if (async?.asyncEnabled === false) c = row('c', 'C 异步用量', 'BLOCKED', '异步关闭，账本可用但日报暂不聚合', '按隔离交付手册启动，不要全局改开关', 'async-events');
  else if (async) {
    const runtime = async.runtime;
    if (async.broker?.state !== 'BROKER_AVAILABLE') c = row('c', 'C 异步用量', async.broker?.state === 'BROKER_UNAVAILABLE' ? 'BLOCKED' : 'UNKNOWN', 'Kafka协议尚未确认可用', '检查Broker协议状态，不能只检查端口', 'async-events');
    else if (!runtime || runtime.publisher?.state === 'UNKNOWN' || runtime.consumer?.state === 'UNKNOWN') c = row('c', 'C 异步用量', 'UNKNOWN', 'Broker已连接，Worker或Consumer当前状态未验证', '等待实际调度与分区分配，再重新读取', 'async-events');
    else if (runtime.publisher?.state !== 'READY' || runtime.consumer?.state !== 'READY') c = row('c', 'C 异步用量', 'BLOCKED', runtime.publisher?.state !== 'READY' ? runtime.publisher?.reason || 'Worker未就绪' : runtime.consumer?.reason || 'Consumer未就绪', '查看异步事件并诊断，禁止跳过offset', 'async-events');
    else if (async.failedCount > 0 || async.pendingConsumption > 0) c = row('c', 'C 异步用量', 'BLOCKED', '仍有失败或未消费事件，日报可能滞后', '诊断后安全重试/补投原事件，不重复业务扣额', 'async-events');
    else c = row('c', 'C 异步用量', 'READY', 'Broker协议、Worker和Consumer有当前运行事实', 'SENT不等于已消费，日报不等于实时余额', 'usage');
  }
  // 3. 页面没有全表迁移/历史风险探针，保持未验证，指向只读doctor。
  return [v1, b, c, row('schema', '数据库迁移', 'UNKNOWN', '页面不能直接确认全部表结构与四项历史风险', '运行 python3 scripts/v11-local.py doctor；现有库不自动迁移')];
}
export function readinessPanel(ctx, data = ctx.data) {
  return h('section', {class: 'card', 'aria-label': '本地功能就绪'}, h('h2', {}, '本地功能就绪'),
    h('div', {class: 'readiness-list'}, readinessFacts(data).map(item => h('div', {class: 'readiness-row'}, h('div', {}, h('strong', {}, item.label), badge(({READY: '就绪 READY', BLOCKED: '受阻 BLOCKED', UNKNOWN: '未验证 UNKNOWN'})[item.state], item.state === 'READY' ? 'good' : item.state === 'BLOCKED' ? 'warn' : '')), h('div', {}, h('p', {}, item.reason), h('p', {class: 'meta'}, item.action)), item.view ? viewLink(ctx, item.view, '查看') : null))),
    h('a', {href: 'local-delivery.html', target: '_blank', rel: 'noopener'}, '本地交付手册（新窗口）'));
}

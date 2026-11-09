import {reportSelection, rememberReport} from './report-context.js';
import {h, button, notice, table, formFields, permissionButton, openDialog} from './ui.js';
import {reference} from './openapi-business.js';
export function usageQuery(values, cursor = '') {
  const from = Date.parse(values.from + 'T00:00:00Z'), to = Date.parse(values.to + 'T00:00:00Z');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(values.from) || !/^\d{4}-\d{2}-\d{2}$/.test(values.to) || !Number.isFinite(from) || !Number.isFinite(to) || from > to || to - from > 30 * 86400000) throw new Error('请选择最多31天的有效UTC日期范围');
  const q = new URLSearchParams({from: values.from, to: values.to, limit: '20'});
  for (const key of ['applicationId', 'apiId']) if (values[key]) q.set(key, values[key]);
  if (cursor) q.set('cursor', cursor); return q.toString();
}
/** 稳定复合游标由服务器提供，不从日期自行推测下一页。 */
export async function renderUsage(ctx, reconciliation = false) {
  ctx.heading(reconciliation ? '对账中心' : '用量统计', reconciliation ? '仅核对已结束UTC日，不自动修改余额' : '异步统计可能延迟，不等于业务成功次数或收费金额');
  const selection = reportSelection(ctx, reconciliation);
  const app = await reference(ctx.api, 'apps', '按应用名称查询', selection.applicationId, true);
  const fields = formFields([{name: 'apiId', label: 'API接口', value: selection.apiId, options: [{value: '', label: '全部接口'}, ...(ctx.data.apis || []).map(a => ({value: a.id, label: a.method + ' ' + a.publicPath}))]}, {name: 'from', label: '开始UTC日期（含）', type: 'date', value: selection.from}, {name: 'to', label: '结束UTC日期（含）', type: 'date', value: selection.to, help: reconciliation ? '必须是昨天或更早；后端以数据库UTC时间复验。' : '最多31天；统计可能落后于配额账本。'}]);
  const integrity = h('div', {'aria-live': 'assertive'}), body = h('section', {class: 'card'}, notice('尚未读取，请选择条件并查询。')), info = h('div', {'aria-live': 'polite'});
  let cursor = null, rows = [], busy = false, active = '';
  const more = button('读取下一页', () => load(false)); more.hidden = true;
  const query = button('查询', () => load(true), {class: 'primary'});
  const values = () => ({...fields.values(), applicationId: app.input.value});
  async function load(reset) {
    if (busy) return; busy = true; query.disabled = more.disabled = true;
    try {
      // 1. 查询变更后必须重读，不拼接不同应用/日期的页。
      const key = usageQuery(values()); rememberReport(ctx, values()); if (!reset && active !== key) throw new Error('条件已改变，请重新查询');
      if (reset) { rows = []; cursor = null; more.hidden = true; integrity.replaceChildren(); body.replaceChildren(notice('正在读取…')); }
      const result = await ctx.api.request((reconciliation ? '/admin/reconciliation?' : '/admin/usage?') + usageQuery(values(), reset ? '' : cursor));
      if (usageQuery(values()) !== key) { integrity.replaceChildren(); body.replaceChildren(notice('查询条件已改变，请重新查询。')); more.hidden = true; return; }
      // 2. 服务器nextCursor保留同日App/API位置，不丢同日记录，也不制造0汇总。
      renderIntegrity(result); active = key; rows = reset ? result.rows : [...rows, ...result.rows]; cursor = result.nextCursor; more.hidden = !cursor;
      const headers = reconciliation ? ['UTC日', '应用', 'API', '权威单位/次数', '已消费单位/次数', '聚合单位/次数', '待消费', '对账状态'] : ['UTC日', '应用', 'API', '授权单位', '准入次数', '最后聚合时间'];
      body.replaceChildren(table(headers, rows.map(r => reconciliation ? [r.dayUtc, r.applicationName || '名称未取得', r.apiName || '名称未取得', r.expectedUnits + '/' + r.expectedCount, r.consumedUnits + '/' + r.consumedCount, r.aggregatedUnits + '/' + r.aggregatedCount, r.outboxBacklog, ({PENDING: '待追平 PENDING', MATCH: '一致 MATCH', MISMATCH: '不一致 MISMATCH', INTEGRITY_GAP: '完整性缺口 INTEGRITY_GAP'})[r.status] || '未知'] : [r.dayUtc, r.applicationName || '名称未取得', r.apiName || '名称未取得', r.authorizedUnits, r.grantCount, r.lastAggregatedAt || '未取得']), '所选条件暂无读模型记录。可能尚未调用、异步关闭或处理延迟；不等于授权消耗为0。'));
      info.replaceChildren(notice('数据库快照：' + result.asOfUtc + '；异步开关：' + (result.asyncEnabled ? '开启，仍可能延迟' : '关闭，统计可能滞后') + '。实时权威消耗请看“配额与账本”。'));
    } catch (e) { info.replaceChildren(notice('读取失败，当前状态未知：' + e.message, 'error')); if (reset) { body.replaceChildren(); integrity.replaceChildren(); } more.hidden = true; }
    // 3. 网络失败不伪造0，不自动改权威数据；写修复没有在本期开放。
    finally { busy = false; query.disabled = more.disabled = false; }
  }
  function renderIntegrity(result) {
    if (!reconciliation) return;
    const warnings = result.integrityWarnings || [];
    if (result.integrityStatus !== 'INTEGRITY_GAP') { integrity.replaceChildren(); return; }
    const labels = {LEDGER_WITHOUT_OUTBOX: '权威Ledger存在，但缺少Outbox，事件日期未知', OUTBOX_WITHOUT_LEDGER: 'Outbox找不到对应权威Ledger', DUPLICATE_OUTBOX: '同一Grant关联多条Outbox，未放大权威授权量', EVENT_TIME_MISSING: '事件UTC时间缺失或格式不合法'};
    integrity.replaceChildren(notice('INTEGRITY_GAP：账本/事件完整性异常，本次不能判为MATCH，也不代表用户配额超扣。请人工核查，页面不会自动改余额或旧Ledger。', 'error'), table(['异常', '安全记录编号', '关联数量'], warnings.map(w => [labels[w.code] || '未分类异常，请联系负责人', w.recordId, w.relatedCount])), notice(result.integrityScan?.note || '日期未知时不伪造日报归属。'), ...(result.integrityTruncated ? [notice('异常样本超过100条，请缩小日期/应用/API条件继续核查。')] : []));
  }
  const changed = () => {
    rememberReport(ctx, values()); more.hidden = true; integrity.replaceChildren();
    body.replaceChildren(notice('查询条件已改变，请重新查询')); info.replaceChildren();
  };
  app.input.addEventListener('change', changed); Object.values(fields.controls).forEach(control => control.addEventListener('change', changed));
  if (selection.applicationId) await load(true);
  return h('div', {}, h('section', {class: 'card query-panel', 'aria-label': '用量查询条件'}, h('h2', {}, '查询条件'), ...(selection.clamped ? [notice('对账仅包含已结束UTC日，本页已将结束日调整为昨日')] : []), app.node, fields.node, h('div', {class: 'query-actions'}, query)), info, integrity, body, more);
}
export async function retryAsyncEvent(api, role, id) {
  if (role !== 'ADMIN') throw new Error('只有ADMIN可重投失败事件');
  return api.request('/admin/async-events/' + id + '/retry', {method: 'POST'});
}
export async function replaySentEvent(api, role, id) {
  if (role !== 'ADMIN') throw new Error('只有ADMIN可补投SENT未消费事件');
  return api.request('/admin/async-events/' + id + '/replay', {method: 'POST'});
}
export async function renderAsyncEvents(ctx) {
  ctx.heading('异步事件', 'NEW待投递、LEASED发送中、SENT仅表示Kafka已确认、FAILED需处理。是否已消费单独显示；重投保留原事件，不再扣额度。');
  const fields = formFields([{name: 'status', label: '事件状态', value: '', options: [{value: '', label: '全部'}, ...['NEW', 'LEASED', 'SENT', 'FAILED'].map(value => ({value, label: value}))]}]);
  const summary = h('section', {class: 'card'}), body = h('section', {class: 'card'}), error = h('div', {'aria-live': 'polite'}); let rows = [], after = 0, active = '', busy = false;
  const more = button('读取下一页事件', () => load(false)); more.hidden = true;
  const reload = button('查询与刷新', () => load(true));
  function replayButton(row) {
    const action = permissionButton('补投原事件', () => openDialog('确认补投SENT未消费事件', notice('仅在超过等待期且尚未消费时补投“' + row.eventLabel + '”。保留原eventId与payload，不再扣额度；消费者可能同时完成，届时409应重新读取。'), async () => { await replaySentEvent(ctx.api, ctx.role, row.id); await load(true); }, {submitLabel: '确认补投'}), ctx.role, true);
    if (!row.replayEligible) { action.disabled = true; if (ctx.role === 'ADMIN') action.setAttribute('title', '事件或节点等待期未满，或状态已变化，请稍后重新读取'); }
    return action;
  }

  async function load(reset) {
    if (busy) return; busy = true; reload.disabled = more.disabled = true;
    try {
      const selected = fields.values().status; if (!reset && selected !== active) throw new Error('状态已改变，请重新查询');
      if (reset) { rows = []; after = 0; body.replaceChildren(); summary.replaceChildren(notice('正在读取实际异步状态…')); }
      const [state, page] = await Promise.all([ctx.api.request('/admin/async-events/summary'), ctx.api.request('/admin/async-events?afterId=' + (reset ? 0 : after) + '&limit=20' + (selected ? '&status=' + selected : ''))]);
      if (fields.values().status !== selected) { body.replaceChildren(); summary.replaceChildren(notice('条件已改变，请重新查询。')); more.hidden = true; return; }
      active = selected; rows = reset ? page : [...rows, ...page]; after = rows.at(-1)?.id || 0; more.hidden = page.length < 20;
      summary.replaceChildren(notice('异步：' + (state.asyncEnabled ? '开启' : '关闭') + '；Broker：' + state.broker.state + '（实际检查于' + state.broker.checkedAt + '）'), table(['待投递', '租约发送中', 'Kafka已确认', '失败', '尚未消费', '最旧待投递秒数'], [[state.newCount, state.leasedCount, state.sentCount, state.failedCount, state.pendingConsumption, state.oldestAgeSeconds]]), notice('最近' + (state.sentReplayScanLimit ?? 1000) + '事件内超时SENT未消费：' + (state.overdueUnconsumedSent ?? '未取得') + '；补投等待期' + (state.replayGraceSeconds ?? '未取得') + '秒，节点保护：' + (state.replayGuardReady ? '就绪' : '等待中') + '。补投只恢复原事件，不代表新的业务授权。'), notice(state.poisonPolicy), table(['本进程消费成功', '重复跳过', '毒消息暂停次数', '数据库自动重试次数', '消费异常诊断'], [[state.processCounters?.consumed ?? '未取得', state.processCounters?.duplicate ?? '未取得', state.processCounters?.poisonPause ?? '未取得', state.processCounters?.transientRetry ?? '未取得', state.processCounters?.conflict ?? '未取得']]), notice('这些是进程累计次数，重启归零；暂停次数不是当前分区数量。数据库恢复会自动追平；仅毒消息暂停需要先诊断修复。'));
      body.replaceChildren(table(['事件短标', '状态', '是否已消费', '尝试次数', '调度/租约/补投等待期时间', '错误码', '操作'], rows.map(row => [row.eventLabel, row.status, row.consumed ? '已消费' : '尚未消费', row.attemptCount, (row.status === 'LEASED' ? row.leaseUntil : row.nextAttemptAt) || '未取得', row.lastErrorCode || '无', row.status === 'FAILED' ? permissionButton('重投', () => openDialog('确认重投失败事件', notice('只重排事件“' + row.eventLabel + '”，保留原eventId。消费者幂等，不重新扣配额。'), async () => { await retryAsyncEvent(ctx.api, ctx.role, row.id); await load(true); }, {submitLabel: '确认重投'}), ctx.role, true) : row.status === 'SENT' && !row.consumed ? replayButton(row) : '—']), '暂无所选事件。')); error.replaceChildren();
    } catch (e) { summary.replaceChildren(notice('状态未取得')); if (reset) body.replaceChildren(); error.replaceChildren(notice(e.message, 'error')); more.hidden = true; }
    finally { busy = false; reload.disabled = more.disabled = false; }
  }
  await load(true); return h('div', {}, h('section', {class: 'card query-panel', 'aria-label': '异步事件查询条件'}, h('h2', {}, '筛选事件'), fields.node, h('div', {class: 'query-actions'}, reload)), error, summary, body, more);
}

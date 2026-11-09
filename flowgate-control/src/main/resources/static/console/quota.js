import {reportSelection, rememberReport} from './report-context.js';
import {h, button, notice, table, formFields} from './ui.js';
import {reference} from './openapi-business.js';
export function quotaQuery(applicationId, period, afterId = 0) {
  if (!Number.isSafeInteger(Number(applicationId)) || Number(applicationId) < 1) throw new Error('请选择应用名称');
  if (!/^\d{4}(0[1-9]|1[0-2])$/.test(period)) throw new Error('月份须为YYYYMM，例如202611');
  return new URLSearchParams({applicationId: String(applicationId), period, afterId: String(afterId), limit: '20'}).toString();
}
/** MySQL只读余额与游标流水；空余额不等于0，不展示虚构Kafka送达量。 */
export async function renderQuota(ctx) {
  ctx.heading('配额与账本', '权威余额与流水 · 成功准入即消耗，下游失败不退款');
  const selection = reportSelection(ctx);
  const app = await reference(ctx.api, 'apps', '选择应用', selection.applicationId);
  const fields = formFields([{name: 'period', label: 'UTC计费月', value: selection.period, pattern: '[0-9]{6}', help: '余额按月查询；跨月日报切换到此页时，使用起始日期所在月。'}]);
  const balance = h('section', {class: 'card'}, notice('请选择应用并点击查询；尚未取得余额。')), ledger = h('section', {class: 'card'}), error = h('div', {'aria-live': 'polite'});
  let cursor = 0, rows = [], busy = false, active = '';
  const more = button('读取下一页流水', () => load(false)); more.hidden = true;
  const query = button('查询余额与流水', () => load(true), {class: 'primary'});
  async function load(reset) {
    if (busy) return; busy = true; query.disabled = more.disabled = true;
    try {
      const key = app.input.value + ':' + fields.values().period;
      rememberReport(ctx, {applicationId: app.input.value, period: fields.values().period}, true);
      if (!reset && active !== key) throw new Error('查询条件已改变，请重新查询');
      const params = quotaQuery(app.input.value, fields.values().period, reset ? 0 : cursor);
      // 1. 变更查询条件先清空旧事实；读取失败不把上一应用余额当作本次结果。
      if (reset) { rows = []; cursor = 0; more.hidden = true; balance.replaceChildren(notice('正在查询余额…')); ledger.replaceChildren(); }
      const [balances, page] = await Promise.all([ctx.api.request('/admin/quota/balances?' + params), ctx.api.request('/admin/quota/ledger?' + params)]);
      if (key !== app.input.value + ':' + fields.values().period) { balance.replaceChildren(notice('查询条件已改变，请重新查询。')); ledger.replaceChildren(); more.hidden = true; return; }
      active = key; rows = reset ? page : [...rows, ...page]; cursor = rows.at(-1)?.id || 0;
      // 2. 首次Grant才冻结余额；不存在记录时绝不显示余额0或已发放套餐额度。
      balance.replaceChildren(h('h2', {}, '权威月度余额'), balances.length ? table(['UTC月', '冻结套餐版本', '总单位', '剩余单位', '已准入消耗单位'], balances.map(b => [b.periodYyyymm, 'V' + b.planVersion, b.totalUnits, b.remainingUnits, b.totalUnits - b.remainingUnits])) : notice('尚无余额记录：可能尚未首次准入。请核对订阅、凭证和已发布规则；不代表已耗尽。'));
      ledger.replaceChildren(h('h2', {}, '准入流水'), table(['流水编号', 'UTC月', '路由', '应用版本', '消耗单位', '准入后余额', '准入时间'], rows.map(r => [r.id, r.periodUtc, r.routeId, 'V' + r.appliedVersion, r.units, r.remainingUnitsAfter, r.createdAt || '未取得']), '此月暂无准入流水。'));
      more.hidden = page.length < 20; error.replaceChildren();
    } catch (e) { error.replaceChildren(notice('读取失败，状态未知：' + e.message, 'error')); if (reset) { balance.replaceChildren(); ledger.replaceChildren(); } more.hidden = true; }
    finally { busy = false; query.disabled = more.disabled = false; }
  }
  const changed = () => {
    rememberReport(ctx, {applicationId: app.input.value, period: fields.values().period}, true);
    more.hidden = true; balance.replaceChildren(notice('查询条件已改变，请重新查询')); ledger.replaceChildren();
  };
  app.input.addEventListener('change', changed); fields.controls.period.addEventListener('change', changed);
  if (selection.applicationId) await load(true);
  return h('div', {}, h('section', {class: 'card query-panel', 'aria-label': '配额查询条件'}, h('h2', {}, '查询条件'), app.node, fields.node, h('div', {class: 'query-actions'}, query)), error, balance, ledger, more, notice('429 QUOTA_EXHAUSTED：本月已耗尽；403：应用/客户/订阅/Scope或代次不可用；503 QUOTA_UNAVAILABLE：依赖不可用或授权结果不确定。503时不要假设已退款，请查询账本。'));
}

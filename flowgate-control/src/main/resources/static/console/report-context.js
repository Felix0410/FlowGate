/** 月度余额与UTC日报共享非敏感选择，查询时仍使用各自的正式时间契约。 */
export function reportSelection(ctx, closedDays = false) {
  const now = new Date(), today = now.toISOString().slice(0, 10), yesterday = new Date(now.getTime() - 86400000).toISOString().slice(0, 10);
  const start = new Date(now); start.setUTCDate(start.getUTCDate() - 6);
  const shared = ctx.reportContext ||= {applicationId: '', apiId: '', from: start.toISOString().slice(0, 10), to: today, period: today.slice(0, 7).replace('-', '')};
  // 1. 地址仅携带已白名单处理的业务引用；不要保存凭证、请求体或原始响应。
  for (const key of ['applicationId', 'apiId', 'from', 'to', 'period']) if (ctx.params?.[key]) shared[key] = String(ctx.params[key]);
  // 2. 月度余额按起始日所在月查询；跨月日报不伪装成同一计费月份。
  if (ctx.params?.from && !ctx.params?.period) shared.period = shared.from.slice(0, 7).replace('-', '');
  const selection = {...shared};
  // 3. 对账只核对已结束UTC日；调整显式显示，不把今日未封账当异常。
  selection.clamped = closedDays && selection.to > yesterday;
  if (selection.clamped) selection.to = yesterday;
  if (selection.from > selection.to) selection.from = selection.to;
  return selection;
}
export function rememberReport(ctx, values, monthly = false) {
  const shared = ctx.reportContext ||= {};
  const periodChanged = values.period !== undefined && values.period !== shared.period;
  Object.assign(shared, values);
  if (monthly && (periodChanged || !shared.from) && /^\d{6}$/.test(values.period)) {
    const year = Number(values.period.slice(0, 4)), month = Number(values.period.slice(4));
    shared.from = values.period.slice(0, 4) + '-' + values.period.slice(4) + '-01';
    shared.to = new Date(Date.UTC(year, month, 0)).toISOString().slice(0, 10);
  } else if (values.from) shared.period = values.from.slice(0, 7).replace('-', '');
  ctx.updateParams?.(shared);
}

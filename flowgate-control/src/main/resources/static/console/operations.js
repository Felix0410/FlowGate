import {h, badge, notice, button, permissionButton, table, searchableTable, formFields, openDialog} from './ui.js';
import {canWrite, canManageCallers, redact} from './api.js';
export function nodeState(node, version) {
  if (!node || version === undefined || version === null) return {label: '未取得', tone: ''};
  if (node.freshness !== 'ONLINE') return {label: '心跳过期', tone: 'warn'};
  if (node.appliedVersion !== version) return {label: '版本未同步', tone: 'warn'};
  return {label: '已应用当前版本', tone: 'good'};
}
export function nodesTable(nodes, version) {
  if (nodes === null) return notice('节点信息未取得，请重新读取。', 'error');
  return table(['节点', '已应用版本', '同步状态', '最近心跳'], (nodes || []).map(node => {
    const state = nodeState(node, version);
    return [node.nodeId, 'V' + node.appliedVersion, badge(state.label, state.tone), node.lastSeenAt || '未取得'];
  }), '尚未有网关上报。请启动Gateway，首次发布后等待节点真实应用。');
}
export async function publishConfiguration(api, role, current, rollbackVersion = null) {
  if (!canWrite(role)) throw new Error('只读角色不能发布');
  if (!Number.isSafeInteger(current?.version)) throw new Error('请先重新读取当前发布版本');
  // 1. 使用确认时读取的版本，不捕获409后自动重试，不修改历史。
  return api.request(rollbackVersion === null ? '/admin/releases/publish' : '/admin/releases/' + rollbackVersion + '/rollback',
    {method: 'POST', body: {expectedVersion: current.version}});
}
function confirmPublish(ctx, rollback = null) {
  const current = ctx.data.current;
  if (!Number.isSafeInteger(current?.version)) { ctx.flash('版本未读取，请先重新读取', 'error'); return; }
  const text = rollback === null ? '将完整当前草稿发布为新版本V' + (current.version + 1) + '。影响全部启用路由和规则；后台没有差异预览接口，请先核对草稿。' : '以历史V' + rollback + '内容生成新版本V' + (current.version + 1) + '。不回退版本号，也不恢复或覆盖草稿。';
  openDialog(rollback === null ? '确认发布全部草稿' : '确认回滚历史配置', h('div', {}, notice(text, rollback === null ? '' : 'error'), h('p', {}, '当前已发布V' + current.version + '。若其他人先发布，本次会被拒绝；请重读核对后重新确认。')), async () => {
    try { const result = await publishConfiguration(ctx.api, ctx.role, current, rollback); await ctx.saved('配置已持久化为V' + result.version + '。尚不能视为已应用，请重新读取节点确认。'); }
    catch (error) { if (error.status === 409) ctx.data.current = null; throw error; }
  }, {submitLabel: rollback === null ? '确认发布' : '确认生成回滚版本', danger: rollback !== null});
}
export async function renderReleases(ctx) {
  ctx.heading('发布中心', '保存草稿 → 发布版本 → 网关实际应用。回滚生成新版本，不更改草稿。', permissionButton('发布全部草稿', () => confirmPublish(ctx), ctx.role, false, true));
  let history = null; try { history = await ctx.api.request('/admin/releases'); } catch { /* 页面内明确标记历史未取得。 */ }
  const current = ctx.data.current;
  return h('div', {}, h('div', {class: 'grid-two'}, h('section', {class: 'card'}, h('h2', {}, '当前发布版本'), h('h1', {}, current ? 'V' + current.version : '未取得'), h('p', {class: 'muted'}, current?.status === 'EMPTY' ? '尚未发布。请先建立服务、API和路由草稿。' : '已持久化不代表节点已应用。'), notice('没有草稿差异预览接口。发布范围为完整草稿，请核对目录和规则；不展示推测的变更数量。')),
    h('section', {class: 'card'}, h('h2', {}, '节点应用情况'), nodesTable(ctx.data.nodes, current?.version))),
    h('section', {class: 'card'}, h('h2', {}, '发布历史（最近100条）'), history === null ? notice('历史未取得，请重新读取', 'error') : table(['版本', '发布时间', '来源', '操作'], history.map(row => ['V' + row.version, row.createdAt || '未取得', row.rollbackFrom ? '回滚自V' + row.rollbackFrom : '草稿发布', h('div', {class: 'actions'}, button('查看摘要', () => ctx.details('历史V' + row.version + '（只读摘要）', row)), permissionButton('回滚', () => confirmPublish(ctx, row.version), ctx.role))]), '尚无发布记录。')));
}
export async function createCaller(api, role, values) {
  if (!canManageCallers(role)) throw new Error('只有管理员可以签发调用方凭证');
  return api.request('/admin/callers', {method: 'POST', body: {username: values.username.trim(), scopes: values.scopes.split(/[，,\s]+/).map(s => s.trim()).filter(Boolean)}});
}
export async function revokeCaller(api, role, id) {
  if (!canManageCallers(role)) throw new Error('只有管理员可以吊销凭证');
  if (!Number.isSafeInteger(Number(id)) || Number(id) < 1) throw new Error('请输入签发记录中的正确调用方编号');
  return api.request('/admin/callers/' + Number(id) + '/revoke', {method: 'POST'});
}
function receipt(ctx) {
  const issued = ctx.issued;
  if (!issued) return notice('暂无一次性凭证。只在成功签发后显示，不能查询历史Token。');
  const token = h('input', {type: 'password', readonly: '', 'aria-label': '本次一次性调用方凭证', autocomplete: 'off'}); token.value = issued.token;
  return h('section', {class: 'card'}, h('h2', {}, '领取一次性凭证'), notice('请立即安全交付给调用方。仅驻留本页内存；刷新、退出、清空或吊销后不能找回。'),
    h('p', {}, '调用方：' + issued.username + ' · 编号 ' + issued.id + ' · 有效期 ' + issued.expiresInSeconds + '秒'), token,
    h('div', {class: 'actions'}, button('显示/隐藏', () => { token.type = token.type === 'password' ? 'text' : 'password'; }), button('清空凭证', () => { token.value = ''; ctx.issued = null; ctx.render(); })));
}
export function renderCallers(ctx) {
  // 1. 按真实角色禁用写操作，不伪造后端不存在的全量Caller列表。
  ctx.heading('调用方管理', '签发访问私有API的凭证，授权范围必须与API要求匹配。仅管理员可签发或吊销。');
  const allowed = canManageCallers(ctx.role);
  const create = formFields([{name: 'username', label: '调用方名称（唯一）', value: '', placeholder: '例如：报表系统', disabled: !allowed}, {name: 'scopes', label: '授权范围', value: '', placeholder: 'demo:read,orders:read', help: '以逗号或空格分隔，与私有API所需范围一致。', required: false, disabled: !allowed}]);
  const revoke = formFields([{name: 'id', label: '调用方编号', value: '', type: 'number', min: 1, step: 1, help: '后端暂无调用方查询接口。仅使用签发回执中的编号，不猜测他人的编号。', disabled: !allowed}]);
  const issueForm = h('form', {}, create.node, h('button', {type: 'submit', class: 'primary', disabled: !allowed}, '签发调用方凭证'));
  // 2. 签发先确认，凭证只进入当前页回执，不进入列表、日志或技术详情。
  issueForm.addEventListener('submit', event => { event.preventDefault(); const values = create.values(); openDialog('确认签发调用方凭证', h('p', {}, '为“' + values.username + '”签发凭证，授权范围：' + (values.scopes || '无') + '。Token只展示一次。'), async () => {
    const result = await createCaller(ctx.api, ctx.role, values); ctx.issued = {...result, username: values.username};
    ctx.callers.push({id: result.id, username: values.username}); create.controls.username.value = ''; create.controls.scopes.value = ''; ctx.render();
  }, {submitLabel: '确认签发'}); });
  const revokeForm = h('form', {}, revoke.node, h('button', {type: 'submit', class: 'danger', disabled: !allowed}, '吊销当前凭证'));
  // 3. 吊销必须二次确认；成功后清除相同编号的一次性Token。
  revokeForm.addEventListener('submit', event => { event.preventDefault(); const id = revoke.values().id; openDialog('确认吊销凭证', notice('编号' + id + '的当前调用方凭证将立即失效，不需要发布，不能恢复旧Token。', 'error'), async () => {
    await revokeCaller(ctx.api, ctx.role, id); if (ctx.issued?.id === Number(id)) ctx.issued = null; await ctx.saved('调用方凭证已吊销。');
  }, {submitLabel: '确认吊销', danger: true}); });
  return h('div', {}, !allowed ? notice('当前角色不能访问调用方管理API。请使用ADMIN账号操作；不会尝试请求无权限的列表。') : null,
    h('div', {class: 'grid-two'}, h('section', {class: 'card'}, h('h2', {}, '签发访问凭证'), issueForm), h('section', {class: 'card'}, h('h2', {}, '撤销授权'), revokeForm)), receipt(ctx),
    h('section', {class: 'card'}, h('h2', {}, '本次登录签发记录'), table(['调用方名称', '签发编号'], ctx.callers.map(c => [c.username, c.id]), '无本次记录。后端没有全量调用方列表，此处不展示推测数据；刷新会清空本次记录。')));
}
export async function renderMonitoring(ctx) {
  ctx.heading('运行监控', '节点和熔断状态来自最近一次真实上报，重新读取刷新；不是无延迟事件流。');
  const metrics = await Promise.allSettled(['spring.cloud.gateway.requests', 'flowgate.admission.requests', 'flowgate.protection.events'].map(name => ctx.api.request('/actuator/metrics/' + name, {gateway: true})));
  const circuitRows = (ctx.data.nodes || []).flatMap(node => (node.circuitStates || []).map(c => [node.nodeId, c.routeId, badge(c.state, c.state === 'CLOSED' ? 'good' : 'warn'), c.stateChangedAt, c.revision]));
  return h('div', {}, h('section', {class: 'card'}, h('h2', {}, '网关节点'), nodesTable(ctx.data.nodes, ctx.data.current?.version)),
    h('section', {class: 'card'}, h('h2', {}, '最近上报的熔断状态'), notice('节点过期时下面的状态也可能过期。Control/Gateway重启后可能显示未知；未取得不等于CLOSED。'), table(['节点', '路由', '最近状态', '状态变化时间', '修订'], circuitRows, '无熔断摘要。可能尚无绑定规则、节点未上报或Control重启后尚未取得。')),
    h('section', {class: 'card'}, h('h2', {}, 'Gateway基础指标 · 127.0.0.1:8080'), metrics.map((result, index) => {
      const name = ['网关请求', '流量准入', '故障保护事件'][index];
      if (result.status !== 'fulfilled') return notice(name + '：暂无样本或接口不可达。可先产生业务调用再重新读取。');
      const value = result.value;
      return h('div', {class: 'card'}, h('h3', {}, name), table(['统计量', '实际数值'], (value?.measurements || []).map(m => [m.statistic, m.value]), '暂无数据'), h('details', {}, h('summary', {}, '指标技术详情'), h('pre', {}, JSON.stringify(redact(value), null, 2))));
    })));
}
export async function renderAudits(ctx) {
  ctx.heading('操作审计', '只读查看配置、发布和身份管理行为。每次读取最近50条，可继续读取更早记录。');
  const container = h('section', {class: 'card'}); let rows = [], loading = false;
  const more = button('读取更早记录', async () => { await load(); });
  async function load() {
    if (loading) return; loading = true; more.disabled = true;
    try {
      const last = rows.at(-1); const page = await ctx.api.request('/admin/audits?limit=50' + (last ? '&beforeId=' + last.id : ''));
      rows.push(...page); container.replaceChildren(table(['时间', '动作', '对象', '版本', '摘要'], rows.map(row => [row.createdAt || '未取得', row.action, row.targetType + ' · ' + row.targetId, row.releaseVersion ?? '—', row.summary]), '尚无审计记录。'));
      more.hidden = page.length < 50;
    } catch (error) { container.append(notice(error.message, 'error')); }
    finally { loading = false; more.disabled = false; }
  }
  await load(); return h('div', {}, container, more);
}

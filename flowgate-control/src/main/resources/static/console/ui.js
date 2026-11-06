/** 只用textContent与DOM节点构建界面，服务端名称、路径与错误不能注入HTML。 */
export function h(tag, attributes = {}, ...children) {
  const element = document.createElement(tag);
  for (const [key, value] of Object.entries(attributes)) {
    if (key.startsWith('on')) element.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'class') element.className = value;
    else if (['disabled', 'hidden', 'required', 'readOnly', 'checked'].includes(key)) element[key] = Boolean(value);
    else if (value !== undefined && value !== null) element.setAttribute(key, value);
  }
  for (const child of children.flat(Infinity)) if (child !== null && child !== undefined) element.append(typeof child === 'object' ? child : document.createTextNode(String(child)));
  return element;
}
export const button = (text, action, options = {}) => h('button', {type: 'button', onclick: action, ...options}, text);
export const badge = (text, tone = '') => h('span', {class: 'badge ' + tone}, text);
export function notice(text, tone = '') { return h('div', {class: 'notice ' + tone, role: tone === 'error' ? 'alert' : 'status'}, text); }
export function permissionButton(text, action, role, adminOnly = false, primary = false) {
  const allowed = adminOnly ? role === 'ADMIN' : ['ADMIN', 'OPERATOR'].includes(role);
  return button(text, action, {class: primary ? 'primary' : '', disabled: !allowed, title: allowed ? text : adminOnly ? '仅管理员可操作' : '只读角色不能修改'});
}
export function table(headers, rows, empty = '暂无记录。可以先创建一条草稿。') {
  if (!rows.length) return h('div', {class: 'empty'}, h('strong', {}, '暂无可显示的记录'), h('p', {}, empty));
  return h('div', {class: 'table-wrap'}, h('table', {}, h('thead', {}, h('tr', {}, headers.map(title => h('th', {scope: 'col'}, title)))),
    h('tbody', {}, rows.map(row => h('tr', {}, row.map(cell => h('td', {}, cell)))))));
}
let fieldNumber = 0;
export function field({name, label, value = '', type = 'text', options, help = '', required = true, ...attributes}) {
  const id = 'field-' + ++fieldNumber;
  const input = options ? h('select', {name, id, required, ...attributes}, options.map(option => h('option', {value: option.value}, option.label))) : h('input', {name, id, type, required, ...attributes});
  input.value = String(value ?? '');
  const wrapper = h('label', {for: id, class: 'field'}, h('span', {}, label), input, help ? h('small', {id: id + '-help'}, help) : null);
  if (help) input.setAttribute('aria-describedby', id + '-help');
  return {wrapper, input};
}
export function formFields(definitions) {
  const controls = {}, nodes = definitions.map(definition => { const f = field(definition); controls[definition.name] = f.input; return f.wrapper; });
  return {controls, node: h('div', {class: 'form-grid'}, nodes), values: () => Object.fromEntries(Object.entries(controls).map(([key, input]) => [key, input.value]))};
}
/** 原生dialog提供焦点约束和Escape；提交中禁止关闭，避免请求完成后写入另一个表单。 */
export function openDialog(title, content, save, {submitLabel = '保存草稿', danger = false, onClose = () => {}} = {}) {
  const dialog = document.getElementById('entity-dialog'), previous = document.activeElement;
  if (dialog.open) dialog.close();
  let busy = false;
  const errors = h('div', {'aria-live': 'assertive', tabindex: '-1'});
  const cancel = button('取消', () => dialog.close());
  const submit = h('button', {type: 'submit', class: danger ? 'danger' : 'primary'}, submitLabel);
  const form = h('form', {}, h('div', {class: 'dialog-head'}, h('h2', {id: 'dialog-title'}, title), button('关闭', () => dialog.close(), {'aria-label': '关闭对话框'})), content, errors,
    h('div', {class: 'dialog-actions'}, cancel, save ? submit : null));
  dialog.replaceChildren(form);
  dialog.oncancel = event => { if (busy) event.preventDefault(); };
  dialog.onclose = () => { onClose(); dialog.replaceChildren(); if (previous?.isConnected) previous.focus(); else document.getElementById('main')?.focus(); };
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (!save || busy || !form.reportValidity()) return;
    // 1. 保持表单和错误在原位置，失败不关闭，不自动重试发布或创建。
    busy = true; errors.replaceChildren(); const buttons = [...form.querySelectorAll('button')]; buttons.forEach(b => b.disabled = true); submit.textContent = '正在保存…';
    try { await save(); dialog.close(); }
    catch (error) { errors.replaceChildren(notice(error.message, 'error')); errors.focus(); }
    finally { busy = false; buttons.forEach(b => b.disabled = false); submit.textContent = submitLabel; }
  });
  dialog.showModal();
  (form.querySelector('input:not([disabled]), select:not([disabled])') || cancel).focus();
  return dialog;
}
export function searchableTable(headers, records, cells, empty) {
  const search = h('input', {type: 'search', placeholder: '搜索当前已读取的记录…', 'aria-label': '搜索当前列表'}), body = h('div');
  const render = () => {
    const term = search.value.trim().toLowerCase();
    const filtered = records.filter(record => JSON.stringify(record).toLowerCase().includes(term));
    body.replaceChildren(table(headers, filtered.map(cells), term ? '没有匹配项，请修改关键词。' : empty));
  };
  search.addEventListener('input', render); render();
  return h('div', {class: 'card'}, h('div', {class: 'table-toolbar'}, search, h('span', {class: 'muted'}, records.length + '条 · 当前列表本地筛选')), body);
}

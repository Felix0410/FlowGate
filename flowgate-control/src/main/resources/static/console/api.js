import {gatewayOrigin as configuredGatewayOrigin} from './runtime-config.js';
/** 管理会话只在闭包内存中；网络失败与HTTP拒绝分别呈现，不持久化任何凭证。 */
export function createApi(transport = (...args) => fetch(...args), expired = () => {}, gatewayOrigin = configuredGatewayOrigin) {
  const origin = new URL(gatewayOrigin);
  if (origin.protocol !== 'http:' || origin.hostname !== '127.0.0.1' || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) throw new Error('Gateway指标来源必须是明确的本机回环地址');
  gatewayOrigin = origin.origin;
  let token = '', role = '', generation = 0;
  const clear = () => { token = ''; role = ''; generation++; };
  async function request(path, {method = 'GET', body, publicRequest = false, gateway = false} = {}) {
    if (!(gateway ? /^\/actuator\/(health|metrics(?:\/[a-zA-Z0-9._-]+)?)$/.test(path) : path === '/status' || path.startsWith('/admin/'))) throw new Error('不允许的请求目标');
    if (!publicRequest && !gateway && !token) throw new Error('请先登录');
    const ticket = generation, headers = {}, controller = new AbortController();
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (!publicRequest && !gateway) headers.Authorization = 'Bearer ' + token;
    const timer = setTimeout(() => controller.abort(), 10000);
    try {
      // 1. 管理请求只走同源；Gateway指标固定回环，绝不向外域附加管理Token。
      const response = await transport((gateway ? gatewayOrigin : '') + path,
        {method, headers, credentials: 'omit', signal: controller.signal, body: body === undefined ? undefined : JSON.stringify(body)});
      const raw = await response.text(); let data;
      try { data = raw ? JSON.parse(raw) : null; } catch { data = null; }
      if (ticket !== generation) throw new Error('会话已切换，请重新读取');
      if (!response.ok) {
        if (response.status === 401 && !publicRequest && !gateway) { clear(); expired(); }
        const explanations = {401: '登录已过期，请重新登录', 403: '当前角色没有此操作权限', 409: '数据已变化或名称重复。请重新读取并核对，不会自动重试', 503: '依赖暂不可用，请检查工作台后重试'};
        const message = response.status === 409 && data?.code !== 'FEATURE_NOT_READY' ? (data?.message || '版本变化或名称重复') + '。请取消本次操作，重新读取并核对，不会自动重试。' : data?.message || explanations[response.status] || '请求未成功，请检查输入或稍后重试';
        const error = new Error(message);
        error.status = response.status; error.code = data?.code; error.requestId = response.headers.get('X-Request-Id'); throw error;
      }
      return data;
    } catch (error) {
      // 2. 不把原始Body或网络异常对象写日志；仅保留用户可采取行动的说明。
      if (!error.status && (error.name === 'AbortError' || error instanceof TypeError)) throw new Error('连接失败或等待超时，请检查Control/Gateway是否运行，再重新读取');
      throw error;
    } finally { clearTimeout(timer); }
  }
  return {
    request, clear, role: () => role, gatewayOrigin: () => gatewayOrigin,
    async login(username, password) {
      clear(); const result = await request('/admin/auth/login', {method: 'POST', body: {username, password}, publicRequest: true});
      if (!result?.token || !['ADMIN', 'OPERATOR', 'VIEWER'].includes(result.role)) throw new Error('登录响应无有效角色');
      token = result.token; role = result.role; return {role};
    },
    async logout() { try { await request('/admin/auth/logout', {method: 'POST'}); } finally { clear(); } }
  };
}
export const canWrite = role => role === 'ADMIN' || role === 'OPERATOR';
export const canManageCallers = role => role === 'ADMIN';
export function redact(value) {
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, val]) => [key, /token|password|internal.?key/i.test(key) ? '已隐藏' : redact(val)]));
  return value;
}

/** Portal Token仅在闭包内存；错误不包含原始响应/请求体或凭证。 */
export function createPortalApi(fetcher = (...args) => fetch(...args), expired = () => {}) {
  let token = '', epoch = 0;
  return {
    clear() { token = ''; epoch++; },
    async login(loginName, password) { const data = await this.request('/auth/login', {body: {loginName, password}}); token = data.token; epoch++; return data; },
    async logout() { try { await this.request('/auth/logout', {body: {}}); } finally { this.clear(); } },
    async request(path, {body, method = body === undefined ? 'GET' : 'POST'} = {}) {
      const version = epoch;
      const response = await fetcher('/portal/api' + path, {method, cache: 'no-store', headers: {...(token ? {Authorization: 'Bearer ' + token} : {}), ...(body === undefined ? {} : {'Content-Type': 'application/json'})}, ...(body === undefined ? {} : {body: JSON.stringify(body)})});
      const data = await response.json().catch(() => ({}));
      if (!response.ok) { if ((response.status === 401 || data.code === 'USER_DISABLED') && token && version === epoch) { this.clear(); expired(); } const error = new Error((data.message || '请求暂未完成') + (data.code ? '（' + data.code + '）' : '')); error.code = data.code; throw error; }
      if (version !== epoch) throw new Error('会话已变更，请重新操作');
      return data;
    }
  };
}

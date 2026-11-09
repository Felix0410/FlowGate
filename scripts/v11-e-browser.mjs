// 1. config仅由Python通过stdin传入；密码与Token不写文件、不打印、不进入截图。
const fs = await import('node:fs/promises');
const task = await taskSpace('FlowGate E delivery browser');
console.log({spaceId: task.spaceId});
const page = task.page('p1');
let phase = 'login';
const checks = [];
const assert = (ok, name) => { if (!ok) throw new Error(name); checks.push(name); };
try {
  await page.goto(config.url);
  await page.snapshot();
  await page.evaluate(() => {
    const original = window.fetch;
    window.__gatewayAuthLeak = false;
    window.fetch = function(input, options) {
      const url = new URL(typeof input === 'string' ? input : input.url, location.href);
      if (url.origin !== location.origin && new Headers(options?.headers).has('Authorization')) window.__gatewayAuthLeak = true;
      return original.apply(this, arguments);
    };
  });
  async function login(username) {
    await page.fill('#username', username); await page.fill('#password', config.password);
    await page.click('#login-submit');
    await page.waitForSelector('#app-view', {state: 'visible'});
    await page.goto(config.url + '#dashboard');
    await page.waitForSelector('[aria-label="本地功能就绪"]', {state: 'visible'});
  }
  await login(config.username);
  // 2. 在真实后端核对两种视口、七域、门禁、应用详情、历史导航和键盘焦点。
  for (const width of [1280, 375]) {
    phase = 'viewport-' + width;
    await page.cdp('Emulation.setDeviceMetricsOverride', {width, height: 900, deviceScaleFactor: 1, mobile: false});
    assert(await page.evaluate(() => document.querySelectorAll('#navigation a').length === 7), width + ' seven domains');
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), width + ' no overflow');
    assert(await page.evaluate(() => {
      const text = document.querySelector('[aria-label="本地功能就绪"]').textContent;
      return text.includes('数据库迁移') && text.includes('UNKNOWN') && text.includes('READY');
    }), width + ' truthful readiness');
    if (width === 375) {
      await page.focus('#menu-toggle'); await page.keyboard.press('Enter');
      await page.waitForFunction(() => document.querySelector('#menu-toggle').getAttribute('aria-expanded') === 'true');
      await page.keyboard.press('Escape');
      assert(await page.evaluate(() => document.activeElement.id === 'menu-toggle' && !document.body.classList.contains('menu-open')), 'drawer Escape restores focus');
    }
    await page.screenshot({path: config.screenshots + '/console-' + width + '.png'});
  }
  phase = 'details';
  await page.goto(config.url + '#business/apps?id=' + config.applicationId + '&tab=credentials');
  await page.waitForSelector('text=查询与管理APP凭证', {state: 'visible'});
  await page.snapshot();
  await page.click('text=查询与管理APP凭证');
  await page.waitForSelector('#entity-dialog[open]', {state: 'visible'});
  await page.snapshot();
  await page.fill('#entity-dialog input[name=scopes]', 'demo:read');
  await page.click('text="轮换凭证"');
  phase = 'credential-confirm';
  await page.waitForSelector('#entity-dialog button[type=submit]', {state: 'visible'});
  await page.click('#entity-dialog button[type=submit]');
  await page.waitForSelector('[aria-label="一次性应用Token"]', {state: 'visible'});
  assert(await page.evaluate(() => {
    const token = document.querySelector('[aria-label="一次性应用Token"]');
    return token.type === 'password' && token.value.startsWith('fgapp_') &&
      !JSON.stringify(localStorage).includes('fgapp_') && !JSON.stringify(sessionStorage).includes('fgapp_') && !location.href.includes('fgapp_');
  }), 'one-time credential stays masked and memory only');
  await page.goto(config.url + '#dashboard');
  phase = 'history';
  await page.waitForSelector('[aria-label="本地功能就绪"]', {state: 'visible'});
  assert(await page.evaluate(() => !document.querySelector('[aria-label="一次性应用Token"]')), 'leaving clears receipt');
  await page.evaluate(() => history.back());
  await page.waitForSelector('text=查询与管理APP凭证', {state: 'visible'});
  assert(await page.evaluate(() => !document.querySelector('[aria-label="一次性应用Token"]')), 'back does not restore secret');
  await page.evaluate(() => history.forward());
  await page.waitForSelector('[aria-label="本地功能就绪"]', {state: 'visible'});
  phase = 'roles-and-metrics';
  await page.goto(config.url + '#operations/monitoring');
  await page.waitForSelector('text=' + config.gatewayOrigin, {state: 'visible'});
  assert(await page.evaluate(() => !window.__gatewayAuthLeak), 'admin authorization never forwarded to Gateway');
  await page.goto(config.url + '#dashboard');
  await page.waitForSelector('[aria-label="本地功能就绪"]', {state: 'visible'});
  await page.reload();
  await page.waitForSelector('#login-view', {state: 'visible'});
  await login('viewer');
  await page.goto(config.url + '#business/developers');
  await page.waitForSelector('text=E隔离客户', {state: 'visible'});
  assert(await page.evaluate(() => {
    const buttons = [...document.querySelectorAll('#page-content button, #page-head button')].filter(b => /新建|创建|编辑/.test(b.textContent));
    return buttons.length > 0 && buttons.every(b => b.disabled);
  }), 'viewer cannot write');
  phase = 'mobile-logout';
  await page.click('#menu-toggle');
  await page.waitForFunction(() => document.querySelector('#menu-toggle').getAttribute('aria-expanded') === 'true');
  await page.click('#logout');
  await page.waitForSelector('#login-view', {state: 'visible'});
  await fs.writeFile(config.screenshots + '/browser-result.json', JSON.stringify({passed: true, checks, viewports: [1280,375]}, null, 2), {mode: 0o600});
  await task.finish({keep: []});
} catch {
  await fs.writeFile(config.screenshots + '/browser-result.json', JSON.stringify({passed: false, phase, checks}, null, 2), {mode: 0o600});
  throw new Error('E browser check failed at ' + phase);
}

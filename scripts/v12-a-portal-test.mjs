import assert from 'node:assert/strict';
import {createPortalApi} from '../flowgate-control/src/main/resources/static/portal/api.js';
const calls=[];let expired=0;
const api=createPortalApi(async(path,options)=>{calls.push({path,options});return {ok:true,json:async()=>path.endsWith('/auth/login')?{token:'fgportal_test',role:'PORTAL_USER'}:{status:'OK'}};},()=>expired++);
await api.login('alice','test-password');await api.request('/me');assert.equal(calls.at(-1).options.headers.Authorization,'Bearer fgportal_test');assert.equal(calls.at(-1).options.cache,'no-store');assert.ok(!calls.at(-1).path.includes('test-password'));
await api.logout();await api.request('/catalog/categories');assert.equal(calls.at(-1).options.headers.Authorization,undefined);assert.equal(expired,0);
const {readFile}=await import('node:fs/promises');const js=await readFile(new URL('../flowgate-control/src/main/resources/static/portal/app.js',import.meta.url),'utf8');assert.ok(!/innerHTML|localStorage|sessionStorage/.test(js));assert.ok(js.includes('ticket!==epoch'));assert.ok(js.includes("pagehide"));console.log('V12 Portal: session isolation, logout, no persistent tokens, safe DOM, stale receipts PASS');

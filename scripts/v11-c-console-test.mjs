/** C真实ES模块与模拟DOM合同，不替代真人浏览器验收。 */
import './v11-a-console-test.mjs';
import assert from 'node:assert/strict';
import {usageQuery, renderUsage, renderAsyncEvents, retryAsyncEvent} from '../flowgate-control/src/main/resources/static/console/usage.js';
assert.throws(()=>usageQuery({from:'2026-01-01',to:'2026-03-01'}),/31天/);
assert.match(usageQuery({from:'2026-11-01',to:'2026-11-02',applicationId:'2'},'opaque-cursor'),/cursor=opaque-cursor/);
const calls=[];let offline=false, pageNumber=0;
const api={request:async(path,options={})=>{calls.push({path,...options});if(offline)throw new Error('不可达');
 if(path.startsWith('/admin/apps?'))return [{id:2,name:'客户应用',code:'app'}];
 if(path.startsWith('/admin/usage?')||path.startsWith('/admin/reconciliation?')){pageNumber++;return {rows:[{dayUtc:'2026-11-01',applicationId:2,apiId:3,applicationName:'客户应用',apiName:'GET /api/private',authorizedUnits:5,grantCount:5,lastAggregatedAt:'UTC',expectedUnits:5,expectedCount:5,consumedUnits:4,consumedCount:4,aggregatedUnits:4,aggregatedCount:4,outboxBacklog:1,status:'PENDING'}],nextCursor:pageNumber===1?'next-composite':null,asOfUtc:'UTC',asyncEnabled:false};}
 if(path.endsWith('/summary'))return {asyncEnabled:true,broker:{state:'BROKER_UNAVAILABLE',checkedAt:'UTC'},newCount:1,leasedCount:0,sentCount:1,failedCount:1,pendingConsumption:2,oldestAgeSeconds:30,poisonPolicy:'暂停不提交offset'};
 if(path.startsWith('/admin/async-events?'))return [{id:1,eventLabel:'event-01',status:'FAILED',consumed:false,attemptCount:8,lastErrorCode:'BROKER_UNAVAILABLE'}];
 if(path.endsWith('/retry'))return {status:'NEW'};
 throw new Error(path);}};
const ctx={api,role:'ADMIN',data:{apis:[{id:3,method:'GET',publicPath:'/api/private'}]},heading(){}};
const usage=await renderUsage(ctx);await usage.descendants().find(e=>e.textContent==='查询'&&e.tagName==='button').fire('click');assert.match(usage.textContent,/客户应用/);assert.match(usage.textContent,/关闭，统计可能滞后/);
await usage.descendants().find(e=>e.textContent==='读取下一页'&&e.tagName==='button').fire('click');assert.match(calls.at(-1).path,/cursor=next-composite/);
const reconcile=await renderUsage(ctx,true);await reconcile.descendants().find(e=>e.textContent==='查询'&&e.tagName==='button').fire('click');assert.match(reconcile.textContent,/待追平 PENDING/);
await assert.rejects(retryAsyncEvent(api,'VIEWER',1),/ADMIN/);
ctx.role='VIEWER';const readOnly=await renderAsyncEvents(ctx);assert.ok(readOnly.descendants().find(e=>e.textContent==='重投'&&e.tagName==='button').disabled);
ctx.role='ADMIN';const events=await renderAsyncEvents(ctx);assert.match(events.textContent,/BROKER_UNAVAILABLE/);await events.descendants().find(e=>e.textContent==='重投'&&e.tagName==='button').fire('click');
const dialog=document.getElementById('entity-dialog');assert.match(dialog.textContent,/保留原eventId/);await dialog.children[0].fire('submit');assert.ok(calls.some(c=>c.path==='/admin/async-events/1/retry'&&c.method==='POST'));
offline=true;await usage.descendants().find(e=>e.textContent==='查询'&&e.tagName==='button').fire('click');assert.match(usage.textContent,/状态未知/);assert.doesNotMatch(usage.textContent,/客户应用GET/);
console.log('PASS C 日期/复合游标/真实读模型与延迟提示/PENDING不冒充错账/ADMIN确认重投/只读/失败未知');

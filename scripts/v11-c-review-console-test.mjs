/** 复审页面合同：SENT补投和FAILED重试必须区分，均不可被只读角色写入。 */
import './v11-c-console-test.mjs';
import assert from 'node:assert/strict';
import {renderAsyncEvents,replaySentEvent,renderUsage} from '../flowgate-control/src/main/resources/static/console/usage.js';
let eligible=true;const calls=[];
const api={request:async(path,options={})=>{calls.push({path,...options});if(path.endsWith('/summary'))return {asyncEnabled:true,broker:{state:'BROKER_AVAILABLE',checkedAt:'UTC'},overdueUnconsumedSent:1,replayGraceSeconds:300,replayGuardReady:true,poisonPolicy:'数据库自动退避，毒消息暂停'};if(path.includes('/async-events?'))return [{id:4,eventLabel:'sent-one',status:'SENT',consumed:false,replayEligible:eligible,attemptCount:1}];if(path.endsWith('/replay'))return {status:'NEW'};throw new Error(path);}};
const ctx={api,role:'ADMIN',heading(){}};
await assert.rejects(replaySentEvent(api,'VIEWER',4),/ADMIN/);
const page=await renderAsyncEvents(ctx);assert.match(page.textContent,/最近1000事件内超时SENT未消费：1/);const replay=page.descendants().find(e=>e.tagName==='button'&&e.textContent==='补投原事件');assert.equal(replay.disabled,false);await replay.fire('click');
const dialog=document.getElementById('entity-dialog');assert.match(dialog.textContent,/不再扣额度/);await dialog.children[0].fire('submit');assert.ok(calls.some(c=>c.path==='/admin/async-events/4/replay'&&c.method==='POST'));
eligible=false;const recent=await renderAsyncEvents(ctx);assert.equal(recent.descendants().find(e=>e.tagName==='button'&&e.textContent==='补投原事件').disabled,true);assert.match(recent.descendants().find(e=>e.tagName==='button'&&e.textContent==='补投原事件').attrs.title,/等待期/);
const integrityApi={request:async(path)=>path.startsWith('/admin/apps?')?[]:{rows:[],nextCursor:null,asOfUtc:'UTC',asyncEnabled:true,integrityStatus:'INTEGRITY_GAP',integrityWarnings:[{code:'LEDGER_WITHOUT_OUTBOX',recordId:7,relatedCount:0}],integrityScan:{note:'写入时间仅筛选候选，不作为日报日期'},integrityTruncated:false}};
const integrityPage=await renderUsage({...ctx,api:integrityApi,data:{apis:[]}},true);await integrityPage.descendants().find(e=>e.tagName==='button'&&e.textContent==='查询').fire('click');assert.match(integrityPage.textContent,/INTEGRITY_GAP/);assert.match(integrityPage.textContent,/权威Ledger存在，但缺少Outbox，事件日期未知/);assert.match(integrityPage.textContent,/本次不能判为MATCH/);
console.log('PASS 复审SENT补投独立确认/ADMIN/等待期禁用/超时告警/空日报仍显示完整性异常');

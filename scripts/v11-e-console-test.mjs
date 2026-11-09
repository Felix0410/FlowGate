/** E就绪诊断不绕过权限/可信门禁；纯事实夹具不是后台实测。 */
import assert from 'node:assert/strict';
import {readinessFacts} from '../flowgate-control/src/main/resources/static/console/readiness.js';
import {createApi} from '../flowgate-control/src/main/resources/static/console/api.js';
const data={current:{version:1,checksum:'sha'},nodes:[{freshness:'ONLINE',appliedVersion:1,checksum:'sha'}],quota:{activationEnabled:true,readiness:'READY',reason:'门禁实测通过'},asyncSummary:{asyncEnabled:true,broker:{state:'BROKER_AVAILABLE'},runtime:{publisher:{state:'READY'},consumer:{state:'READY'}},failedCount:0,pendingConsumption:0}};
assert.deepEqual(readinessFacts(data).map(row=>row.state),['READY','READY','READY','UNKNOWN']);
assert.equal(readinessFacts({...data,current:{version:0}})[0].state,'BLOCKED');
assert.equal(readinessFacts({...data,quota:{activationEnabled:true}})[1].state,'UNKNOWN');
assert.equal(readinessFacts({...data,quota:{activationEnabled:false}})[1].state,'BLOCKED');
assert.equal(readinessFacts({...data,asyncSummary:{asyncEnabled:false}})[2].state,'BLOCKED');
assert.equal(readinessFacts({...data,asyncSummary:{...data.asyncSummary,runtime:undefined}})[2].state,'UNKNOWN');
assert.equal(readinessFacts({...data,asyncSummary:{...data.asyncSummary,runtime:{publisher:{state:'READY'},consumer:{state:'BLOCKED',reason:'毒消息暂停'}}}})[2].state,'BLOCKED');
assert.equal(readinessFacts({...data,asyncSummary:{...data.asyncSummary,pendingConsumption:1}})[2].state,'BLOCKED');
let seen;
const api=createApi(async(path,options)=>{seen={path,options};return {ok:true,status:200,headers:{get:()=>null},text:async()=>JSON.stringify(path.includes('/login')?{token:'test-memory-only',role:'ADMIN'}:{status:'UP'})}},()=>{},'http://127.0.0.1:18180');
await api.login('fixture','not-persisted');await api.request('/actuator/health',{gateway:true});
assert.equal(seen.path,'http://127.0.0.1:18180/actuator/health');assert.equal(seen.options.headers.Authorization,undefined);
assert.throws(()=>createApi(undefined,undefined,'https://external.invalid'),/回环/);
console.log('PASS E三态/真实后端门禁证明/Worker与Consumer缺证据不绿/积压不假成功/数据库未验证/隔离指标端口及不发管理Token');

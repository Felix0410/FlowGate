/** V11-A真实ES模块与模拟DOM契约；不是负责人浏览器体验。 */
import assert from 'node:assert/strict';
import {businessBody, saveBusiness, businessPage, editBusiness, renderBusiness, credentialsPlaceholder} from '../flowgate-control/src/main/resources/static/console/openapi-business.js';
const requests=[];
const fixtures={developers:[{id:1,code:'customer-a',name:'客户甲',status:'ACTIVE'}],apps:[{id:2,developerId:1,developerName:'客户甲',code:'app-a',name:'应用甲',status:'ACTIVE'}],plans:[{id:3,code:'free',name:'Free',monthlyUnits:5,planVersion:1,status:'ACTIVE'}],subscriptions:[]};
let fail=false;
const api={request:async(path,options={})=>{requests.push({path,...options});if(fail)throw new Error('暂时不可达');if(path.endsWith('/credentials')){const error=new Error('尚未启用');error.status=409;error.code='FEATURE_NOT_READY';throw error;}
 const match=path.match(/^\/admin\/(developers|apps|plans|subscriptions)(?:\/(\d+))?(?:\?.*)?$/);assert.ok(match,path);const [,kind,id]=match;
 if(options.method)return {id:id?Number(id):10,...options.body};return id?fixtures[kind].find(row=>row.id===Number(id)):fixtures[kind];}};
await assert.rejects(saveBusiness(api,'OPERATOR','developers',{code:'x',name:'x',status:'ACTIVE'}),/ADMIN/);
await assert.rejects(saveBusiness(api,'VIEWER','plans',{}),/ADMIN/);
for(const [kind,values] of [['developers',{code:'a',name:'客户',status:'ACTIVE'}],['apps',{developerId:'1',code:'b',name:'应用',status:'ACTIVE'}],['plans',{code:'free',name:'Free',monthlyUnits:'5',status:'ACTIVE'}],['subscriptions',{applicationId:'2',planId:'3',effectiveAt:'2026-11-01T00:00:00Z',expiresAt:'2026-12-01T00:00:00Z'}]]){
 await saveBusiness(api,'ADMIN',kind,values);assert.equal(requests.at(-1).path,'/admin/'+kind);assert.equal(requests.at(-1).method,'POST');}
assert.deepEqual(requests.at(-1).body,{applicationId:2,planId:3,effectiveAt:'2026-11-01T00:00:00Z',expiresAt:'2026-12-01T00:00:00Z'});
await saveBusiness(api,'ADMIN','plans',{code:'free',name:'Free新名称',monthlyUnits:'999',status:'SUSPENDED'},fixtures.plans[0]);
assert.equal(requests.at(-1).method,'PATCH');assert.deepEqual(requests.at(-1).body,{name:'Free新名称',status:'SUSPENDED'});
assert.throws(()=>businessBody('plans',{monthlyUnits:0}),/额度/);
assert.throws(()=>businessBody('subscriptions',{applicationId:'2',planId:'3',effectiveAt:'2026-11-01T08:00:00+08:00',expiresAt:'x'}),/UTC/);
await assert.rejects(saveBusiness(api,'ADMIN','subscriptions',{}, {id:1}),/不支持/);
await businessPage(api,'apps',20,{developerId:1});assert.equal(requests.at(-1).path,'/admin/apps?afterId=20&limit=20&developerId=1');

// 1. 构建最小原生DOM，执行真实客户/应用/套餐/订阅表单，而非查字符串判断成功。
class Element{
 constructor(tag){this.tagName=tag;this.attrs={};this.children=[];this.listeners={};this.value='';this.disabled=false;this.hidden=false;this.isConnected=true;this.open=false;this._text='';}
 setAttribute(k,v){this.attrs[k]=String(v);}getAttribute(k){return this.attrs[k];}
 append(...values){this.children.push(...values);}replaceChildren(...values){this.children=values;this._text='';}
 set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+this.children.map(c=>c.textContent).join('');}
 get options(){return this.children.filter(c=>c.tagName==='option').map(c=>({value:c.attrs.value}));}
 addEventListener(k,v){(this.listeners[k]||=[]).push(v);}async fire(k){for(const fn of this.listeners[k]||[])await fn({preventDefault(){}});}
 descendants(){return this.children.flatMap(c=>[c,...(c.descendants?.()||[])]);}
 querySelectorAll(selector){return this.descendants().filter(e=>selector==='button'?e.tagName==='button':/input:not/.test(selector)?['input','select'].includes(e.tagName)&&!e.disabled:false);}
 querySelector(selector){return this.querySelectorAll(selector)[0];}reportValidity(){return true;}focus(){}showModal(){this.open=true;}close(){this.open=false;this.onclose?.();}}
const dialog=new Element('dialog');globalThis.document={activeElement:null,getElementById:id=>id==='entity-dialog'?dialog:new Element('main'),createElement:tag=>new Element(tag),createTextNode:text=>({textContent:String(text)})};
let feedback='';const ctx={api,role:'ADMIN',heading(){},details(){},flash(text){feedback=text;},saved:async text=>{feedback=text;}};
for(const kind of Object.keys(fixtures)){
 await editBusiness(ctx,kind);assert.equal(dialog.open,true);const form=dialog.children[0];
 for(const control of form.descendants().filter(e=>e.tagName==='input'||e.tagName==='select')){
   const name=control.attrs.name;
   if(name==='name')control.value='新记录';if(name==='code')control.value='new';
   if(name==='developers')control.value='1';if(name==='apps')control.value='2';if(name==='plans')control.value='3';
 }
 await form.fire('submit');assert.equal(dialog.open,false);assert.match(feedback,/尚未启用/);assert.equal(requests.at(-1).method,'POST');
}
await editBusiness(ctx,'apps',fixtures.apps[0]);
const owner=dialog.descendants().find(e=>e.attrs?.name==='developers');assert.equal(owner.value,'1');assert.equal(owner.disabled,true);dialog.close();
await editBusiness(ctx,'plans',fixtures.plans[0]);assert.equal(dialog.descendants().find(e=>e.attrs?.name==='monthlyUnits').disabled,true);dialog.close();
ctx.role='VIEWER';const view=await renderBusiness(ctx,'developers');assert.equal(view.descendants().find(e=>e.tagName==='button'&&e.textContent==='编辑').disabled,true);
ctx.role='ADMIN';await credentialsPlaceholder(ctx,fixtures.apps[0]);assert.match(feedback,/A阶段尚未启用/);assert.equal(requests.at(-1).path,'/admin/apps/2/credentials');
fail=true;const unavailable=await renderBusiness(ctx,'developers');assert.match(unavailable.textContent,/读取失败/);assert.doesNotMatch(unavailable.textContent,/暂无记录/);
console.log('PASS V11-A真实模块/四类创建表单/名称关联/套餐不可变PATCH/分页过滤/ADMIN写限制/失败未知/凭证409不造Token');

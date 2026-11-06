'use strict';
// 不依赖 npm 的交互契约测试；实际浏览器布局与人工可用性须另行验收。
const fs=require('node:fs'), vm=require('node:vm'), assert=require('node:assert/strict');
const root=__dirname+'/../flowgate-control/src/main/resources/static/test-console/';
const html=fs.readFileSync(root+'index.html','utf8'), nodes=new Map(), sections=[];
class Element {
  constructor(id='') {this.id=id;this.value='';this.textContent='';this.dataset={};this.children=[];this.listeners={};this.disabled=false;this.attributes={};}
  addEventListener(name,fn) {this.listeners[name]=fn;}
  append(child) {this.children.push(child); if(child.id) nodes.set(child.id,child);}
  replaceChildren(...children) {this.children=children;this.value=children[0]?.value || '';}
  setAttribute(name,value) {this.attributes[name]=value;}
  hasAttribute(name) {return Object.hasOwn(this.attributes,name);}
  closest(tag) {return tag==='section'?sections[0]:this;}
}
for(const match of html.matchAll(/<(\w+)([^>]*)>([^<]*)/g)) {
  const [,,raw,text]=match, id=raw.match(/\bid="([^"]+)"/)?.[1];
  if(match[1]==='section') sections.push(new Element(id));
  if(!id) continue;
  const node=sections.find(s=>s.id===id)||new Element(id); nodes.set(id,node);
  node.value=raw.match(/\bvalue="([^"]*)"/)?.[1] || (match[1]==='textarea'?text:''); node.textContent=text || id;
  for(const attr of raw.matchAll(/(data-[\w-]+)(?:="([^"]*)")?/g)) {node.attributes[attr[1]]=attr[2]??'';node.dataset[attr[1].slice(5)]=attr[2]??'';}
}
nodes.get('policy-type').value='RATE_LIMIT'; nodes.get('policy-scope').value='ROUTE'; nodes.get('policy-enabled').value='true';
const requests=[];let status=200,data={};
const context={console,performance,Map,Set,Date,Option:class extends Element {constructor(text,value){super();this.textContent=text;this.value=value;}},
 confirm:()=>true,prompt:()=>null,
 document:{getElementById:id=>nodes.get(id)||sections.find(s=>s.id===id),createElement:()=>new Element(),querySelectorAll:selector=>
 selector==='main > section'?sections:selector==='[data-admin]'?[...nodes.values()].filter(n=>n.hasAttribute('data-admin')):
 selector==='#tabs button'?nodes.get('tabs').children:[]},
 fetch:async(path,options)=>{requests.push({path,options});const result=path==='/admin/releases/current'?{version:7,status:'PERSISTED'}:data;
 return {status,ok:status>=200&&status<300,headers:{get:()=> 'test-request-id'},text:async()=>JSON.stringify(result)};}};
vm.createContext(context);vm.runInContext(fs.readFileSync(root+'app.js','utf8'),context);
async function click(id) {await nodes.get(id).listeners.click();}
async function run(){
 assert.equal(sections.filter(s=>!s.hidden).length,1); assert.equal(sections[0].id,'quick');
 assert.equal(nodes.get('publish').disabled,true);
 nodes.get('username').value='test';nodes.get('password').value='private-test-password';data={token:'private-test-token',role:'ADMIN'};
 await nodes.get('login-form').listeners.submit({preventDefault(){}});
 assert.equal(nodes.get('password').value,''); assert.equal(nodes.get('expected').value,7);assert.equal(nodes.get('publish').disabled,false);
 data={ok:true}; await click('fast');assert.match(requests.at(-1).path,/\/api\/demo\/fast\/123\?tag=abc$/);
 assert.equal(requests.at(-1).options.headers.Authorization,undefined);
 status=429;data={code:'RATE_LIMITED',message:'速率预算已耗尽',token:'must-not-render'}; await click('slow');
 const card=nodes.get('response-slow')||sections[0].children.find(n=>n.id==='response-slow');
 assert.match(card.textContent,/429/);assert.match(card.textContent,/RATE_LIMITED/);assert.doesNotMatch(card.textContent,/must-not-render/);
 status=409;data={message:'预期版本已过期'};await click('publish');assert.equal(nodes.get('expected').value,7);
 status=400;data={message:'参数错误'};await click('echo');
 assert.match(nodes.get('response-echo').textContent,/400/);
 assert.doesNotMatch(nodes.get('result').textContent,/private-test-token|private-test-password|must-not-render/);
 assert.equal(nodes.get('publish').disabled,false);
 vm.runInContext("policyCatalog=[{id:99,code:'old',type:'RATE_LIMIT',scope:'ROUTE',parametersJson:'{\"capacity\":2,\"refillPerSecond\":1}',enabled:true}]",context);
 nodes.get('policy-existing').value='99';nodes.get('policy-existing').onchange();
 assert.equal(nodes.get('policy-target').disabled,true);assert.match(nodes.get('policy-status').textContent,/绑定关系保持不变/);
 const before=requests.length; status=200;data={id:99};await click('policy-save');
 assert.equal(requests.slice(before).some(r=>r.path.endsWith('/bindings')),false);
 console.log('PASS 控制台导航/登录与清密/角色按钮/当前版本/真实目标路径/429与400渲染/409不重试/摘要脱敏');
}
module.exports={context,nodes,requests,click,run,reply:(s,d)=>{status=s;data=d;}};
if(require.main===module) run().catch(error=>{console.error(error);process.exitCode=1;});

(function(){

function __b(n,a){if(typeof __fmBridge==='undefined'||!__fmBridge)return null;
try{return __fmBridge(n,JSON.stringify(a||[]))}catch(e){return null}}

function __s(r){return r==null?'':String(r)}

var console={log:function(){var m=[];for(var i=0;i<arguments.length;i++){
try{m.push(typeof arguments[i]==='string'?arguments[i]:JSON.stringify(arguments[i]))}
catch(e){m.push(String(arguments[i]))}}__b('log',[m.join(' ')])},
warn:function(){console.log.apply(null,arguments)},
error:function(){console.log.apply(null,arguments)},
info:function(){console.log.apply(null,arguments)},
debug:function(){},trace:function(){}};

try{globalThis.console=console}catch(e){}

globalThis.setTimeout=function(f,t){try{
if(typeof f==='function'){var r=f();return (r&&typeof r.then==='function')?null:r}}catch(e){return null}return null};

globalThis.clearTimeout=function(){};

globalThis.setInterval=function(){return 0};

globalThis.clearInterval=function(){};

globalThis.requestAnimationFrame=globalThis.setTimeout;

globalThis.alert=function(m){console.log(m)};

globalThis.btoa=function(s){var t=__b('b64e',[String(s)]);
if(t)return String(t);return ''};

globalThis.atob=function(s){var t=__b('b64d',[String(s)]);
if(t)return String(t);return ''};

var local={get:function(k){return __s(__b('getCache',[String(k)]))},

 set:function(k,v){__b('setCache',[String(k),String(v)]);return true},

 remove:function(k){__b('removeCache',[String(k)]);return true},

 clear:function(){__b('clearCache',[]);return true},

 getItem:function(k){return local.get(k)},

 setItem:function(k,v){return local.set(k,v)},

 removeItem:function(k){return local.remove(k)},

 clearAll:function(){return local.clear()}};

try{globalThis.local=local}catch(e){}

try{if(typeof localStorage==='undefined')globalThis.localStorage=local}catch(e){}

function md5X(s){return __s(__b('md5',[String(s)]))}

globalThis.md5X=md5X;globalThis.md5=md5X;globalThis.MD5=md5X;

globalThis.base64Encode=function(s){return __s(__b('b64e',[String(s)]))};

globalThis.base64Decode=function(s){return __s(__b('b64d',[String(s)]))};

globalThis.base64X=globalThis.base64Encode;

globalThis.aesX=function(a){var r=__b('aes',a||[]);return r==null?'':String(r)};

globalThis.desX=function(a){var r=__b('des',a||[]);return r==null?'':String(r)};

globalThis.rsaX=function(){return ''};

globalThis.gbkDecode=function(b){var r=__b('gbk',[String(b)]);return r==null?'':String(r)};

globalThis.s2t=function(s){return String(s==null?'':s)};

globalThis.t2s=function(s){return String(s==null?'':s)};

globalThis.fetch=function(u,o){var r=__b('req',[String(u),o||{}]);

 var body=(r&&typeof r==='object'&&r.body)?r.body:__s(r);

 return {ok:true,status:200,text:function(){return body},
json:function(){try{return JSON.parse(body)}catch(e){return null}}}};

globalThis.request=function(u,o){var r=__b('req',[String(u),o||{}]);

 return (r&&typeof r==='object'&&r.body)?r.body:__s(r)};

globalThis.joinUrl=function(b,p){if(!p)return b||'';

 if(/^https?:\/\//i.test(p))return p;

 if(p.charAt(0)==='/'){var m=/^(https?:\/\/[^\/]+)/i.exec(b||'');return (m?m[1]:'')+p}

 if(!b)return p;

 return b.replace(/\?.*$/,'').replace(/\/[^\/]*$/,'').replace(/\/$/,'')+'/'
+p.replace(/^\/+/,'')};

var VOID={br:1,img:1,input:1,meta:1,link:1,hr:1,source:1,area:1,base:1,col:1,embed:1,param:1,track:1,wbr:1};

function __attrs(s){var o={},r=/([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(["'])([\s\S]*?)\2/g,m;

 while((m=r.exec(s))){o[m[1].toLowerCase()]=m[3]}

 r=/([A-Za-z_:][-A-Za-z0-9_:.]*)(?=\s|$|\/)/g;

 while((m=r.exec(s))){if(!(m[1].toLowerCase() in o))o[m[1].toLowerCase()]=''}

 return o}

function __parse(h){var n=[],st=[],re=/<(\/?)([A-Za-z][A-Za-z0-9]*)([^>]*?)(\/?)>/g,m,last=0;

 while((m=re.exec(h))){var tag=m[2].toLowerCase(),close=m[1]==='/',self=m[4]==='/';

  if(close){for(var i=st.length-1;i>=0;i--){if(st[i].tag===tag){
st[i].e=m.index+m[0].length;st.splice(i,1);break}}}

  else{var e={tag:tag,attrs:__attrs(m[3]||''),s:m.index,e:h.length,
kids:[],parent:null,inner:'',text:'',html:''};

   if(st.length){var p=st[st.length-1];e.parent=p;p.kids.push(e)}

   if(!self&&!VOID[tag])st.push(e);else e.e=m.index+m[0].length;

   n.push(e)}}

 for(var j=0;j<n.length;j++){var x=n[j];

  if(x.e===undefined||x.e>x.s){var inner=x.inner!==undefined?x.inner:''}

  x.html=(x.e&&x.e>x.s)?h.substring(x.s,x.e):'';

  x.text=x.html.replace(/<[\s\S]*?>/g,'').replace(/&nbsp;/g,' ').replace(/&amp;/g,'&')}

 return n}

function J(list){this.list=list||[];this.length=this.list.length}

J.prototype.size=function(){return this.list.length};

J.prototype.eq=function(i){return new J([this.list[i]])};

J.prototype.first=function(){return this.eq(0)};

J.prototype.last=function(){return this.eq(this.list.length-1)};

J.prototype.each=function(f){for(var i=0;i<this.list.length;i++){
try{f.call(this.list[i],i,this.list[i])}catch(e){}}return this};

J.prototype.map=function(f){var o=[];for(var i=0;i<this.list.length;i++){
try{o.push(f.call(this.list[i],i,this.list[i]))}catch(e){}}return o};

J.prototype.text=function(){var t='';for(var i=0;i<this.list.length;i++){
t+=this.list[i].text||''}return t};

J.prototype.html=function(){var t='';for(var i=0;i<this.list.length;i++){
t+=this.list[i].html||''}return t};

J.prototype.attr=function(k){if(!this.list.length)return '';

 var v=this.list[0].attrs[k.toLowerCase()];return v===undefined?'':v};

J.prototype.find=function(sel){var o=[];

 var ps=String(sel||'').split(',');

 for(var p=0;p<ps.length;p++){var parts=ps[p].trim().split(/\s+/);

  if(!parts.length)continue;

  var last=parts[parts.length-1],cur=this.list;

  for(var q=0;q<parts.length-1;q++){var nx=[];

   for(var i=0;i<cur.length;i++){var d=__desc(cur[i],parts[q]);nx=nx.concat(d)}

   cur=nx}

  for(var i=0;i<cur.length;i++){var d=__desc(cur[i],last);o=o.concat(d)}}

 return new J(o)};

function __desc(node,sel){var out=[];

 var m=/^([A-Za-z][A-Za-z0-9]*)?((?:[.#][-\w]+)*)$/.exec(sel.trim());

 if(!m)return out;

 var tag=(m[1]||'').toLowerCase(),rest=m[2]||'';

 var cls=[],id='';

 var rr=/([.#])([-\w]+)/g,x;

 while((x=rr.exec(rest))){if(x[1]==='.')cls.push(x[2]);else id=x[2]}

 var kids=node.kids||[];

 for(var i=0;i<kids.length;i++){__scan(kids[i])}

 function __scan(k){

  if(tag&&k.tag!==tag){}else if(id&&k.attrs['id']!==id){}else{

   var ok=true;var kc=(' '+(k.attrs['class']||'')+' ');

   for(var c=0;c<cls.length;c++){if(kc.indexOf(' '+cls[c]+' ')<0){ok=false;break}}

   if(ok)out.push(k)}

  var ks=k.kids||[];for(var j=0;j<ks.length;j++){__scan(ks[j])}}

 return out}

globalThis.__fmParse=__parse;

function __wrap(h){var root={tag:'#root',attrs:{},kids:[],text:'',html:String(h)};

 var ns=__parse(String(h));

 for(var i=0;i<ns.length;i++){if(!ns[i].parent){ns[i].parent=root;root.kids.push(ns[i])}}

 return new J([root])}

globalThis.$=function(h){return __wrap(h)};

globalThis.jQuery=globalThis.$;

globalThis.jsoup=function(h){return __wrap(h)};

function __one(node,seg){var d=__desc(node,seg);return d.length?d[0]:null}

function __walk(root,rule){var segs=String(rule||'').split('&&');

 var target=root,lastAttr='';

 var lastSeg=segs[segs.length-1];

 var ai=lastSeg.indexOf('--');

 if(ai>=0){lastAttr=lastSeg.substring(ai+2);segs[segs.length-1]=lastSeg.substring(0,ai)}

 for(var i=0;i<segs.length;i++){var seg=segs[i].trim();if(!seg)continue;

  target=__one(target,seg);if(!target)return null}

 return {node:target,attr:lastAttr}}

globalThis.pdfh=function(h,rule){var r=__walk(__wrap(h).list[0],rule);

 if(!r||!r.node)return '';

 if(!r.attr||r.attr==='TEXT')return r.node.text||'';

 return r.node.attrs[r.attr.toLowerCase()]||''};

globalThis.pd=function(h,rule){return globalThis.pdfh(h,rule)};

globalThis.pdfl=function(h,rule){var r=__walk(__wrap(h).list[0],rule);

 if(!r||!r.node)return '';

 if(!r.attr||r.attr==='TEXT')return r.node.text||'';

 return r.node.attrs[r.attr.toLowerCase()]||''};

globalThis.pdfa=function(h,rule){var root=__wrap(h).list[0];

 var segs=String(rule||'').split('&&'),lastAttr='';

 var lastSeg=segs[segs.length-1],ai=lastSeg.indexOf('--');

 if(ai>=0){lastAttr=lastSeg.substring(ai+2);segs[segs.length-1]=lastSeg.substring(0,ai)}

 var cur=[root];

 for(var i=0;i<segs.length;i++){var seg=segs[i].trim();if(!seg)continue;var nx=[];

  for(var j=0;j<cur.length;j++){nx=nx.concat(__desc(cur[j],seg))}

  cur=nx}

 var out=[];

 for(var k=0;k<cur.length;k++){var n=cur[k];

  if(!lastAttr||lastAttr==='TEXT')out.push(n.text||'');

  else out.push(n.attrs[lastAttr.toLowerCase()]||'')}

 return out};

globalThis.pj=function(h,rule){return globalThis.pdfh(h,rule)};

globalThis.pjfa=function(h,rule){return globalThis.pdfa(h,rule)};

globalThis.pf=function(h,rule){return globalThis.pdfh(h,rule)};

})();
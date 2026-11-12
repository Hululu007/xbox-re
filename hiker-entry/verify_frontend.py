# -*- coding: utf-8 -*-
"""
verify_frontend.py —— 前端重写（录口令 → 生成站点 → 点站点播放）验证

验证目标（对应用户诉求）：
  1. 口令四种录入形态：单口令 / 多口令 / 含密码 / 导入
  2. 口令真实格式：云1~云10 通道映射、￥home_rule￥ 分享包装、base64 壳、
     裸规则 JSON / 数组、{sites:[]} 配置、纯文本行、netcut/云3 明确报错
  3. 站点生成与去重（buildSites / generate）
  4. 点击站点后的播放逻辑链：home → cate → search → detail → play → 播放器
  5. 规则执行真实产出（不截断、不硬编码结果），降级档明确标记不假成功
  6. ES5 语法兼容（无 let/const/箭头/模板串/可选链/class/async）
  7. 「不是后端数据可视化」：index.html 不含能力表/覆盖率/兼容性表/registry 注入/样本规则

运行：python3 verify_frontend.py
输出：verify_frontend_report.json
"""
import json, os, re, subprocess, sys, base64

ROOT = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(ROOT, "app", "assets", "hiker")
APP_JS = os.path.join(ASSETS, "hiker_app.js")
INDEX = os.path.join(ASSETS, "index.html")
REPORT = os.path.join(ROOT, "verify_frontend_report.json")

results = []


def check(cid, desc, cond, detail=""):
    ok = bool(cond)
    results.append({"id": cid, "desc": desc, "ok": ok, "detail": str(detail)})
    print(("  OK  " if ok else "  FAIL") + " %-5s %s" % (cid, desc) + (("  -> " + str(detail)) if detail and not ok else ""))
    return ok


# ---------------------------------------------------------------------------
# A. 静态断言：文件存在 / 引用顺序 / 无后端可视化 / ES5
# ---------------------------------------------------------------------------
print("\n=== A. 静态断言 ===")
appjs = open(APP_JS, "r", encoding="utf-8").read()
html = open(INDEX, "r", encoding="utf-8").read()

check("A01", "hiker_app.js 与 index.html 均存在且非空",
      os.path.isfile(APP_JS) and os.path.isfile(INDEX) and len(appjs) > 5000 and len(html) > 5000)

order = ["hiker_bridge_adapter.js", "hiker_scheme.js", "hiker_api_surface.js",
         "jst4.js", "hiker_runtime_shim.js", "hiker_compat.js", "hiker_app.js"]
pos = [html.find('src="%s"' % f) for f in order]
check("A02", "7 个运行时脚本引用顺序正确（应用逻辑层在最后）",
      all(p >= 0 for p in pos) and pos == sorted(pos),
      str(pos))

# 后端数据可视化特征词（旧面板元素）——新前端必须全部消失
backend_markers = ["兼容性", "能力表", "覆盖率", "apiCoverage", "btnCompat", "compatOut",
                   "registryOut", "btnInject", "注入到 App", "btnBoot", "bootOut",
                   "btnProbe", "syncNavigation", "btnRunAll", "SAMPLE_RULES",
                   "btnFetch", "btnImport\">粘贴导入", "六段链路", "桥检测中"]
html_no_comment = re.sub(r"/\*[\s\S]*?\*/", "", html)          # 去掉 JS 块注释
html_no_comment = re.sub(r"<!--[\s\S]*?-->", "", html_no_comment)  # 去掉 HTML 注释
html_no_comment = re.sub(r"(?m)^\s*//.*$", "", html_no_comment)    # 去掉行注释
hit = [m for m in backend_markers if m in html_no_comment]
check("A03", "index.html 已移除后端数据可视化/自测面板元素（剥离注释后）", len(hit) == 0, hit)

# 面向用户的四视图与四种口令模式
ui_markers = ['data-v="input"', 'data-v="sites"', 'data-v="content"', 'data-v="detail"',
              'data-m="single"', 'data-m="multi"', 'data-m="password"', 'data-m="import"',
              'id="btnGen"', 'id="btnSearch"', 'id="eps"', 'A.generate(', 'A.playEpisode(']
miss = [m for m in ui_markers if m not in html]
check("A04", "四视图 + 四种口令模式 + 生成/搜索/详情/播放按钮齐备", len(miss) == 0, miss)

# ES5 语法（两个前端新文件都不允许 ES6+ 语法）
es6_pats = [
    (r"\blet\s+[A-Za-z_$]", "let"),
    (r"\bconst\s+[A-Za-z_$]", "const"),
    (r"=>", "箭头函数"),
    (r"`", "模板字符串"),
    (r"\?\.", "可选链"),
    (r"\?\?", "空值合并"),
    (r"\bclass\s+[A-Za-z_$]", "class"),
    (r"\basync\s+function", "async"),
]
def es6_hits(txt):
    out = []
    # 去掉注释与字符串里的误报：先剥掉 /* */ 与 // 行注释
    clean = re.sub(r"/\*[\s\S]*?\*/", "", txt)
    clean = re.sub(r"(?m)^\s*//.*$", "", clean)
    for p, name in es6_pats:
        # 模板串 ` 只查非字符串拼接场景（这里简单计数，字符串里出现也算风险）
        if re.search(p, clean):
            out.append(name)
    return out
h1, h2 = es6_hits(appjs), es6_hits(html)
check("A05", "hiker_app.js 为 ES5（无 ES6+ 语法）", len(h1) == 0, h1)
check("A06", "index.html 内联脚本为 ES5（无 ES6+ 语法）", len(h2) == 0, h2)

# ---------------------------------------------------------------------------
# B. 运行时逻辑断言（node 加载 UMD，输出 JSON 结果）
# ---------------------------------------------------------------------------
print("\n=== B. 运行时链路（node）===")
sites_cfg = json.dumps({"sites": [
    {"key": "c1", "name": "配置站1", "type": 3, "api": "csp_X"},
    {"key": "c2", "name": "配置站2", "type": 3, "api": "csp_Y"}]}, ensure_ascii=False)
b64 = base64.b64encode(sites_cfg.encode("utf-8")).decode("ascii")

node_script = r'''
const A = require(%s);
A.wire(global);
const R = {};
function cap(o){ return {ok:o.ok, action:o.action, err:o.err, degrade:o.degrade}; }

/* 口令四种形态解析 */
R.modes = {};
['single','multi','password','import'].forEach(function(m){
  R.modes[m] = A.parseInput('云6oooole/xxxxxx/n1\n' + %s + '\nhttps://x.com/cfg.json', {mode:m, password:'pw'});
});

/* 云通道映射 */
R.clouds = {};
['云1oooole/raw/Ab12','云2oooole/apidb/key1','云5oooole/xx','云6oooole/xxxxxx/note1',
 '云7oooole/nt','云9oooole/id','云10oooole/share/a/b/k'].forEach(function(c){
  var u=A.cloudUrl(c,'secret'); R.clouds[c]=u.ok?{url:u.url,ch:u.channel}:{err:u.error};
});
R.cloud_netcut = A.cloudUrl('云2oooole/p/nc','');
R.cloud_3 = A.cloudUrl('云3oooole/x','');
R.cloud6_pwd = A.cloudUrl('云6oooole/xxxxxx/note1','pw');

/* 解包 */
R.unwrap_share = A.unwrap('海阔视界规则分享，小程序\uFFE5home_rule\uFFE5' + %s).ok;
R.unwrap_b64 = A.unwrap('base64://@标题@' + %s);
R.unwrap_json = A.unwrap(%s).ok;

/* 站点生成 */
R.sites_cfg = A.buildSites(%s, {}).map(function(x){return x.key;});
R.sites_dedup = A.buildSites(JSON.stringify({sites:[{key:'a',name:'A'},{key:'a',name:'A2'}]}),{}).length;
R.sites_rule = A.buildSites(%s, {}).length;

/* 全链路：录配置口令 → 生成站点 → home/cate/search/detail/play */
var g = A.generate(A.demoConfig(), {mode:'import'});
R.gen_count = g.count;
R.gen_notes = g.notes;
var st = g.sites[0];
R.has_rules = !!(st && st.rules && st.rules.home && st.rules.detail && st.rules.play);
var h = A.home(st); R.home = {classes:h.classes.length, vods:h.vods.length, action:h.action, err:h.err,
                              first:h.vods[0]?h.vods[0].title:''};
var c = A.cate(st,'动作'); R.cate = {vods:c.vods.length, titles:c.vods.map(function(v){return v.title;})};
var se = A.search(st,'影片C'); R.search = {vods:se.vods.length, action:se.action};
var se0 = A.search(st,'绝无此片ZZZ'); R.search_zero = se0.vods.length;
var d = A.detail(st, h.vods[0]); R.detail = {eps:d.episodes.length, action:d.action,
                                             first:d.episodes[0]?d.episodes[0].name:'', err:d.err};
var p = A.playEpisode(st, h.vods[0], d.episodes[0]); R.play = {ok:p.ok, url:p.url, channel:p.channel};
R.plays = global.__HK_PLAYS__ || [];

/* 单条分享口令 → 站点 → 点站点 home 真实产出 */
var share = '小程序\uFFE5home_rule\uFFE5' + JSON.stringify({title:'规则X',col_type:'flex',
  find_rule:'js:setResult({classes:["全部"],list:[{title:"影片X",url:"hiker://page/x"}]});'});
var gs = A.generate(share, {mode:'single'});
R.share_gen = gs.count;
R.share_home = gs.count ? (function(){var x=A.home(gs.sites[0]);return {vods:x.vods.length,first:x.vods[0]?x.vods[0].title:''};})() : null;

/* 降级识别：不假成功 */
R.degrade_rhino = cap(A.execRule('var x=new JavaAdapter(java.lang.Runnable,{run:function(){}});setResult(x);', {}));
R.degrade_py = cap(A.execRule('py: import requests; setResult(1)', {}));
R.degrade_sel = cap(A.execRule('@css:.item&&a', {}));
R.degrade_norule = cap(A.execRule('', {}));

/* 完整结果不截断：setResult 一个大数组 */
R.full_result = (function(){
  var code='var a=[];for(var i=0;i<300;i++)a.push({title:"影片"+i,url:"u"+i});setResult({list:a});';
  var r=A.execRule(code,{}); return {ok:r.ok, n:(r.ok&&r.value&&r.value.list)?r.value.list.length:-1};
})();

/* 离线云口令明确报错（不伪造内容） */
R.offline_cloud = A.generate('云6oooole/xxxxxx/n1', {mode:'single'});

process.stdout.write(JSON.stringify(R));
''' % (
    json.dumps(APP_JS),
    json.dumps('小程序\uFFE5home_rule\uFFE5' + sites_cfg),
    json.dumps(sites_cfg), json.dumps(b64), json.dumps(sites_cfg),
    json.dumps(sites_cfg),
    json.dumps(json.dumps({"title": "规则R", "col_type": "flex", "find_rule": "js:..."}, ensure_ascii=False)),
)

try:
    out = subprocess.check_output(["node", "-e", node_script], stderr=subprocess.STDOUT, timeout=90)
    R = json.loads(out.decode("utf-8"))
except Exception as e:
    print("node 执行失败:", e)
    R = {}

check("B01", "四种口令模式均可解析（single/multi/password/import）",
      all(m in R.get("modes", {}) and R["modes"][m]["count"] >= 1 for m in ["single", "multi", "password", "import"]),
      {m: R.get("modes", {}).get(m, {}).get("count") for m in ["single", "multi", "password", "import"]})
check("B02", "多口令/导入能拆出 3 条（云/分享/URL），单口令只 1 条",
      R.get("modes", {}).get("multi", {}).get("count") == 3 and
      R.get("modes", {}).get("import", {}).get("count") == 3 and
      R.get("modes", {}).get("single", {}).get("count") == 1,
      {m: R.get("modes", {}).get(m, {}).get("count") for m in ["single", "multi", "import"]})
check("B03", "含密码模式把密码应用到条目", R.get("modes", {}).get("password", {}).get("passwordApplied") is True)

clouds = R.get("clouds", {})
cloud_urls = {
    "云1oooole/raw/Ab12": "pastebin.com/raw/Ab12",
    "云2oooole/apidb/key1": "textdb.online/key1",
    "云5oooole/xx": "cmd.im/xx",
    "云6oooole/xxxxxx/note1": "pasteme.tyrantg.com",
    "云7oooole/nt": "note.ms/nt",
    "云9oooole/id": "txtpad.cn/id",
    "云10oooole/share/a/b/k": "hassdtebin.com/raw/k",
}
cloud_ok = all(frag in clouds.get(k, {}).get("url", "") for k, frag in cloud_urls.items())
check("B04", "云1/2/5/6/7/9/10 七个通道 URL 映射正确", cloud_ok, clouds)
check("B05", "netcut(/p/) 与 云3 明确报错不静默",
      not R.get("cloud_netcut", {}).get("ok") and not R.get("cloud_3", {}).get("ok"))
check("B06", "云6 带密码拼 (@pwd) 后缀", "(@pw)" in R.get("cloud6_pwd", {}).get("url", ""),
      R.get("cloud6_pwd", {}).get("url"))
check("B07", "分享包装/base64壳/裸JSON 三种解包正确",
      R.get("unwrap_share") is True and R.get("unwrap_b64", {}).get("ok") is True and
      R.get("unwrap_b64", {}).get("text") == sites_cfg and R.get("unwrap_json") is True)

check("B08", "buildSites 解析 {sites:[]} 并按 key 去重",
      R.get("sites_cfg") == ["c1", "c2"] and R.get("sites_dedup") == 1,
      {"cfg": R.get("sites_cfg"), "dedup": R.get("sites_dedup")})
check("B09", "generate(demoConfig) 生成 2 个站点且带完整规则",
      R.get("gen_count") == 2 and R.get("has_rules") is True,
      {"count": R.get("gen_count"), "has_rules": R.get("has_rules")})

h = R.get("home", {})
check("B10", "点站点 home 真实产出分类(4) 与影片(4) 且无错",
      h.get("action") == "run-in-webview" and h.get("classes") == 4 and h.get("vods") == 4 and not h.get("err"),
      h)
check("B11", "cate 分类过滤真实生效（动作=2 部，非恒真全量）",
      R.get("cate", {}).get("vods") == 2, R.get("cate"))
check("B12", "search 关键词命中 1 部、无命中为 0（不假成功）",
      R.get("search", {}).get("vods") == 1 and R.get("search_zero") == 0,
      {"hit": R.get("search"), "zero": R.get("search_zero")})
check("B13", "detail 产出 4 个选集且带剧集名",
      R.get("detail", {}).get("eps") == 4 and bool(R.get("detail", {}).get("first")), R.get("detail"))
check("B14", "play 解析播放地址并交给 App 播放器（离线 mock 留痕）",
      R.get("play", {}).get("ok") is True and ".m3u8" in R.get("play", {}).get("url", "") and
      len(R.get("plays", [])) >= 1, R.get("play"))
check("B15", "单条分享口令也能生成站点并点开产出影片",
      R.get("share_gen") == 1 and R.get("share_home", {}).get("vods") == 1,
      {"gen": R.get("share_gen"), "home": R.get("share_home")})

check("B16", "Rhino/Python/选择器/空规则均明确降级，不假成功",
      R.get("degrade_rhino", {}).get("action") == "degrade" and
      R.get("degrade_py", {}).get("action") == "degrade" and
      R.get("degrade_sel", {}).get("action") == "degrade" and
      R.get("degrade_norule", {}).get("action") == "no-rule",
      {"rhino": R.get("degrade_rhino"), "py": R.get("degrade_py"),
       "sel": R.get("degrade_sel"), "empty": R.get("degrade_norule")})
check("B17", "规则执行结果完整不截断（300 条全量返回）",
      R.get("full_result", {}).get("ok") is True and R.get("full_result", {}).get("n") == 300,
      R.get("full_result"))
check("B18", "离线云口令明确提示需联网、不伪造内容",
      R.get("offline_cloud", {}).get("count") == 0 and
      any("离线" in n or "联网" in n for n in R.get("offline_cloud", {}).get("notes", [])),
      R.get("offline_cloud", {}).get("notes"))

# ---------------------------------------------------------------------------
total = len(results)
passed = sum(1 for r in results if r["ok"])
report = {
    "title": "前端重写（录口令 → 生成站点 → 点站点播放）验证",
    "files": {"hiker_app.js": len(appjs), "index.html": len(html)},
    "total": total, "passed": passed, "failed": total - passed,
    "all_pass": passed == total,
    "checks": results,
}
with open(REPORT, "w", encoding="utf-8") as f:
    json.dump(report, f, ensure_ascii=False, indent=2)
print("\n=== 结果：%d/%d 通过 ===" % (passed, total))
print("报告：%s" % REPORT)
sys.exit(0 if passed == total else 1)

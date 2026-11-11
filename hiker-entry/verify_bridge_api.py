#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段八 · 补桥方法验证（离线可复现）

阶段七让官方 134 个 API「符号可见」，但 25 个 bridge 档只做 JS 转发、Java 侧返回空串。
本脚本验证阶段八把这 25+ 个 API 补成了「有语义」，共四组证据：

  A 组  Java 真编译 + 纯计算语义（md5 / AES / RSA / Base64 / QR / 文件 / M3U8 / 降级留痕）
  B 组  本地 HTTP 服务下的网络语义（Cookie / 缓存命中 / 并发批量 / POST / 下载 / 302 / 懒规则）
  C 组  QR 矩阵与 Python qrcode 官方实现逐格交叉验证（同 version / 同 mask / 强制 byte 模式）
  D 组  JS 转发链路（bridgeCall 优先 HikerBridge.call、参数显式序列化、数组还原、
        CryptoJS 兼容对象、降级留痕、FM_SHELL/fy_bridge_app 转发、离线无回退）

复现：python3.11 verify_bridge_api.py
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "app", "src", "main", "java", "com", "fongmi", "android", "tv", "hiker")
ASSETS = os.path.join(HERE, "app", "assets", "hiker")
CHROMIUM = "/usr/bin/chromium"
SCRIPTS = ["hiker_bridge_adapter.js", "hiker_scheme.js", "hiker_api_surface.js",
           "jst4.js", "hiker_runtime_shim.js", "hiker_compat.js"]

PURE_JAVA = ["HikerJson.java", "HikerQR.java", "HikerHttp.java",
             "HikerContract.java", "HikerApi.java"]

# ------------------------------------------------------------------ 本地测试服务

M3U8 = ("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nseg1.ts\n"
        "#EXT-X-DISCONTINUITY\n#EXTINF:5,\n/ad/adv.ts\n#EXTINF:10,\nseg2.ts\n")
PAGE = ('<html><body><div class="list"><a class="item" href="/v/1.html">影片A</a>'
        '</div></body></html>')
HITS = {"echo": 0, "a": 0, "b": 0, "post": 0, "cookie": 0}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, body, headers=None):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or []):
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        p = self.path.split("?", 1)[0]
        if p == "/page":
            HITS["cookie"] += 1
            self._send(200, PAGE, [("Set-Cookie", "sid=abc; Path=/"),
                                   ("Set-Cookie", "token=t1; Path=/")])
        elif p.startswith("/echo"):
            HITS["echo"] += 1
            self._send(200, "ECHO:" + self.path)
        elif p == "/a":
            HITS["a"] += 1
            self._send(200, "A")
        elif p == "/b":
            HITS["b"] += 1
            self._send(200, "B")
        elif p == "/list.m3u8":
            self._send(200, M3U8)
        elif p == "/file.bin":
            self._send(200, "BINARY-OK")
        elif p == "/final":
            self._send(200, "FINAL")   # 重定向落点，不污染 /echo 的缓存命中计数
        elif p == "/redirect":
            self.send_response(302)
            self.send_header("Location", "/final?x=redirected")
            self.end_headers()
        else:
            self._send(404, "not found")

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(n).decode("utf-8", "replace")
        HITS["post"] += 1
        self._send(200, "POST:" + body)


# ------------------------------------------------------------------ Java 驱动源码

BRIDGE_PROBE = r'''
import com.fongmi.android.tv.hiker.*;
import java.security.*;
import java.util.*;

public class BridgeProbe {
    static String args(Object... a) {
        List<Object> l = new ArrayList<Object>();
        for (Object x : a) l.add(x);
        return HikerJson.stringify(l);
    }
    static String rep(char c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }
    public static void main(String[] argv) throws Exception {
        String base = argv[0];
        Map<String, Object> o = new LinkedHashMap<String, Object>();

        /* --- 摘要 / 对称加密 --- */
        o.put("md5_hello", HikerApi.call("md5", args("hello")));
        o.put("md5_empty", HikerApi.call("md5", args("")));
        String enc = HikerApi.call("aesEncode", args("海阔规则", "mykey"));
        o.put("aes_enc", enc);
        o.put("aes_dec", HikerApi.call("aesDecode", args(enc, "mykey")));
        o.put("aes_wrong_key", HikerApi.call("aesDecode", args(enc, "other")));

        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        KeyPair kp = g.generateKeyPair();
        String pub = HikerJson.b64Encode(kp.getPublic().getEncoded());
        String pri = HikerJson.b64Encode(kp.getPrivate().getEncoded());
        String ct = HikerApi.call("rsaEncrypt", args("secret", pub));
        o.put("rsa_enc_len", ct.length());
        o.put("rsa_dec", HikerApi.call("rsaDecrypt", args(ct, pri)));

        /* --- Base64 / hex / 文件 --- */
        o.put("b64e", HikerJson.b64Encode("ABC".getBytes("UTF-8")));
        o.put("b64d", new String(HikerJson.b64Decode("QUJD"), "UTF-8"));
        o.put("writeHex", HikerApi.call("writeHexFile", args("a.bin", "48656c6c6f")));
        o.put("readHex", HikerApi.call("readFile", args("a.bin")));
        o.put("b64img", HikerApi.call("convertBase64Image",
                args(HikerJson.b64Encode("PNGDATA".getBytes("UTF-8")), "pic.png")));
        o.put("b64img_read", HikerApi.call("readFile", args("pic.png")));

        /* --- M3U8 --- */
        o.put("fixM3u8", HikerApi.call("fixM3u8",
                args("#EXTM3U\nseg1.ts\n", "http://a.com/x/list.m3u8")));
        String ad = "#EXTM3U\n#EXTINF:10,\nseg1.ts\n#EXT-X-DISCONTINUITY\n" +
                    "#EXTINF:5,\n/ad/adv.ts\n#EXTINF:10,\nseg2.ts\n";
        o.put("clearM3u8Ad", HikerApi.call("clearM3u8Ad", args(ad)));

        /* --- 二维码 --- */
        String uri = HikerApi.call("createQRCode", args("HELLO"));
        o.put("qr_prefix", uri.length() >= 26 ? uri.substring(0, 26) : uri);
        o.put("qr_len", uri.length());
        o.put("qr_overflow", HikerApi.call("createQRCode", args(rep('V', 300))));
        List<Object> qrs = new ArrayList<Object>();
        String[] ts = {"HELLO", "https://example.com/a?b=1", "海阔视界",
                       "1234567890123456789012345678901234567890", rep('A', 180), rep('V', 300)};
        for (String t : ts) {
            Map<String, Object> q = new LinkedHashMap<String, Object>();
            boolean[][] m = HikerQR.matrix(t, 0);
            q.put("text", t);
            q.put("size", m == null ? 0 : m.length);
            q.put("matrix", m == null ? "" : HikerQR.matrixString(m));
            qrs.add(q);
        }
        o.put("qr_cases", qrs);

        /* --- 网络语义 --- */
        o.put("fetchCookie", HikerApi.call("fetchCookie", args(base + "/page")));
        o.put("getCookie", HikerApi.call("getCookie", args(base + "/page")));
        o.put("fetchCache1", HikerApi.call("fetchCache", args(base + "/echo?x=1")));
        o.put("fetchCache2", HikerApi.call("fetchCache", args(base + "/echo?x=1")));
        o.put("batchFetch", HikerApi.call("batchFetch",
                args(Arrays.asList(base + "/a", base + "/b"))));
        o.put("postRequest", HikerApi.call("postRequest", args(base + "/post", "hello=1")));
        o.put("downloadFile", HikerApi.call("downloadFile", args(base + "/file.bin", "dl.bin")));
        o.put("downloadRead", HikerApi.call("readFile", args("dl.bin")));
        String mp = HikerApi.call("cacheM3u8", args(base + "/list.m3u8"));
        o.put("cacheM3u8", mp);
        o.put("cacheM3u8_body", HikerApi.call("readFile", args(mp)));
        o.put("parseLazyRule", HikerApi.call("parseLazyRule", args(base + "/page")));
        o.put("refreshVideoUrl", HikerApi.call("refreshVideoUrl", args(base + "/redirect")));

        /* --- 系统 / 降级 / 未知 --- */
        o.put("getIP", HikerApi.call("getIP", "[]"));
        o.put("getCpuAbi", HikerApi.call("getCpuAbi", "[]"));
        o.put("degraded_x5", HikerApi.call("fetchCodeByWebView", args(base + "/page")));
        o.put("degraded_x5Rule", HikerApi.call("x5Rule", args("x")));
        o.put("getCryptoJS", HikerApi.call("getCryptoJS", "[]"));
        o.put("unknown", HikerApi.call("no_such_api", "[]"));
        o.put("json_roundtrip", HikerJson.stringify(
                HikerJson.parse("{\"a\":[1,true,null,\"中\"],\"b\":{\"k\":\"v\"}}")));
        o.put("report", HikerApi.report());
        o.put("capabilities", HikerApi.capabilities());

        System.out.println(HikerJson.stringify(o));
    }
}
'''

JAVA_MOCK_BRIDGE = r"""
window.__HK_CALLS__ = [];
window.HikerBridge = {
  call: function(name, argsJson){
    window.__HK_CALLS__.push([name, argsJson]);
    if (name === 'md5') return '5d41402abc4b2a76b9719d911017c592';
    if (name === 'batchFetch') return '["A","B"]';
    if (name === 'aesEncode') return 'CIPHER';
    if (name === 'aesDecode') return 'PLAIN';
    if (name === 'getCryptoJS') return '';
    if (name === 'fetchCodeByWebView') return '';
    return '{}';
  },
  req: function(u,o){ return '<html><body><a class="item" href="/v/1.html">A</a></body></html>'; },
  request: function(u,o){ return this.req(u,o); },
  fetch: function(u,o){ return this.req(u,o); },
  report: function(t,v){}, log: function(m){},
  play: function(u,t,p){ return true; },
  readFile: function(p){ return ''; },
  getInternalJs: function(){ return ''; },
  getVar: function(k){ return ''; }, putVar: function(k,v){},
  getCache: function(k){ return ''; }, setCache: function(k,v){},
  capabilities: function(){ return JSON.stringify({bridge:'HikerBridge',sync:true,
        'return':'string',play:'local-push',port:9978,es5:true}); },
  apiCapabilities: function(){ return JSON.stringify({layer:'HikerApi',implemented:35,methods:[]}); }
};
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=HERE)
    a = ap.parse_args()

    report = {"stage": "阶段八 · 补桥方法", "groups": {}, "assertions": {}}
    passed = total = 0

    def chk(name, cond, detail=""):
        nonlocal passed, total
        total += 1
        if cond:
            passed += 1
        report["assertions"][name] = {"ok": bool(cond), "detail": str(detail)[:240]}
        print("  [%s] %-42s %s" % ("PASS" if cond else "FAIL", name, str(detail)[:100]))

    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = "http://127.0.0.1:%d" % srv.server_address[1]

    tmp = tempfile.mkdtemp(prefix="hiker_bridge_verify_")
    try:
        # ---- javac 真编译（纯 JDK 层） ----
        jsrc = [os.path.join(SRC, f) for f in PURE_JAVA]
        cp = subprocess.run(["javac", "-encoding", "UTF-8", "-d", tmp] + jsrc,
                            capture_output=True, text=True)
        chk("A javac 编译 5 个纯 JDK 类", cp.returncode == 0,
            (cp.stderr.strip() or "0 错误")[:100])

        probe = os.path.join(tmp, "BridgeProbe.java")
        open(probe, "w", encoding="utf-8").write(BRIDGE_PROBE)
        cp = subprocess.run(["javac", "-encoding", "UTF-8", "-cp", tmp, "-d", tmp, probe],
                            capture_output=True, text=True)
        chk("A Java 驱动编译", cp.returncode == 0, (cp.stderr.strip() or "0 错误")[:100])
        if cp.returncode != 0:
            print(cp.stderr[:2000])
            return

        run = subprocess.run(["java", "-cp", tmp, "BridgeProbe", base],
                             capture_output=True, text=True, timeout=120)
        chk("A Java 驱动运行", run.returncode == 0, (run.stderr.strip() or "exit 0")[:100])
        data = json.loads(run.stdout.strip().split("\n")[-1])
        report["groups"]["A_java"] = {k: v for k, v in data.items() if k != "qr_cases"}

        # ---- A 组：纯计算语义 ----
        print("\n[A 组] Java 层纯计算语义")
        chk("md5('hello') 正确", data["md5_hello"] == hashlib.md5(b"hello").hexdigest(),
            data["md5_hello"])
        chk("md5('') 正确", data["md5_empty"] == "d41d8cd98f00b204e9800998ecf8427e",
            data["md5_empty"])
        chk("AES 加密非明文", data["aes_enc"] and data["aes_enc"] != "海阔规则",
            data["aes_enc"][:32])
        chk("AES 解密回原文", data["aes_dec"] == "海阔规则", data["aes_dec"])
        chk("AES 错密钥不伪成功", data["aes_wrong_key"] != "海阔规则",
            "空串或乱码" if data["aes_wrong_key"] != "海阔规则" else "意外解出")
        chk("RSA 加密产出密文", data["rsa_enc_len"] > 20, data["rsa_enc_len"])
        chk("RSA 解密回原文", data["rsa_dec"] == "secret", data["rsa_dec"])
        chk("Base64 编码/解码", data["b64e"] == "QUJD" and data["b64d"] == "ABC",
            "%s / %s" % (data["b64e"], data["b64d"]))
        chk("writeHexFile 返回 hiker://files 路径",
            str(data["writeHex"]).startswith("hiker://files/"), data["writeHex"])
        chk("readFile 读回写入内容", data["readHex"] == "Hello", data["readHex"])
        chk("convertBase64Image 落盘可读", data["b64img_read"] == "PNGDATA",
            "%s -> %r" % (data["b64img"], data["b64img_read"]))
        chk("fixM3u8 相对分片转绝对", "http://a.com/x/seg1.ts" in data["fixM3u8"],
            data["fixM3u8"].replace("\n", " | ")[:90])
        chk("clearM3u8Ad 去广告且保留真实分片",
            "/ad/adv.ts" not in data["clearM3u8Ad"] and "seg2.ts" in data["clearM3u8Ad"],
            data["clearM3u8Ad"].replace("\n", " | ")[:90])
        chk("createQRCode 返回 data URI", str(data["qr_prefix"]).startswith("data:image/svg+xml"),
            "%s len=%s" % (data["qr_prefix"], data["qr_len"]))
        chk("createQRCode 超容量明确留空", data["qr_overflow"] == "", repr(data["qr_overflow"]))
        chk("getIP 返回 IPv4", re.match(r"^\d+\.\d+\.\d+\.\d+$", str(data["getIP"])) is not None,
            data["getIP"])
        chk("getCpuAbi 非空", bool(data["getCpuAbi"]), data["getCpuAbi"])
        chk("X5 依赖 API 明确空串", data["degraded_x5"] == "" and data["degraded_x5Rule"] == "",
            "不伪装成功")
        chk("getCryptoJS 由 JS 层承担（Java 留痕）", data["getCryptoJS"] == "", "owner-js")
        chk("未知 API 不假成功", data["unknown"] == "", repr(data["unknown"]))
        chk("HikerJson 往返保真",
            json.loads(data["json_roundtrip"])["a"][3] == "中", data["json_roundtrip"][:80])
        cap = json.loads(data["capabilities"])
        chk("HikerApi 实现数 35（官方 25 + 额外 10）", cap["implemented"] == 35,
            cap["implemented"])
        rep = json.loads(data["report"])
        ev = " ".join(rep["events"])
        chk("降级 / 未知 / owner 全部留痕",
            "degraded:fetchCodeByWebView" in ev and "unknown:no_such_api" in ev
            and "owner-js:getCryptoJS" in ev, ev[-100:])

        # ---- B 组：本地 HTTP 网络语义 ----
        print("\n[B 组] 本地 HTTP 服务下的网络语义")
        chk("fetchCookie 收集 Set-Cookie", "sid=abc" in data["fetchCookie"]
            and "token=t1" in data["fetchCookie"], data["fetchCookie"])
        chk("getCookie 复用同域 jar", "sid=abc" in data["getCookie"], data["getCookie"])
        chk("fetchCache 两次结果一致", data["fetchCache1"] == data["fetchCache2"],
            data["fetchCache1"])
        chk("fetchCache 第二次命中缓存（服务端仅 1 次）", HITS["echo"] == 1,
            "server echo hits=%d" % HITS["echo"])
        chk("batchFetch 并发返回数组", json.loads(data["batchFetch"]) == ["A", "B"],
            data["batchFetch"])
        chk("postRequest 回显 body", data["postRequest"] == "POST:hello=1", data["postRequest"])
        chk("downloadFile 落盘", str(data["downloadFile"]).startswith("hiker://files/"),
            data["downloadFile"])
        chk("downloadFile 内容可读", data["downloadRead"] == "BINARY-OK", data["downloadRead"])
        chk("cacheM3u8 改写分片为绝对地址",
            "http://127.0.0.1" in data["cacheM3u8_body"] and "seg1.ts" in data["cacheM3u8_body"],
            data["cacheM3u8_body"].replace("\n", " | ")[:90])
        chk("parseLazyRule 返回页面正文", "影片A" in data["parseLazyRule"],
            data["parseLazyRule"][:60])
        chk("refreshVideoUrl 跟随 302", "redirected" in data["refreshVideoUrl"],
            data["refreshVideoUrl"])
        report["groups"]["B_http"] = {"server_hits": dict(HITS), "base": base}

        # ---- C 组：QR 与 Python qrcode 逐格交叉验证 ----
        print("\n[C 组] QR 矩阵与 qrcode(python) 官方实现交叉验证")
        try:
            import qrcode
            from qrcode.constants import ERROR_CORRECT_M
            from qrcode.util import QRData, MODE_8BIT_BYTE
        except ImportError:
            qrcode = None
        qr_ok = 0
        qr_total = len(data["qr_cases"])
        for c in data["qr_cases"]:
            t, size, jm = c["text"], int(c["size"]), c["matrix"].split("\n") if c["matrix"] else []
            if size == 0:
                if qrcode is not None:
                    qr_ok += 1
                continue
            if qrcode is None:
                continue
            v = (size - 17) // 4
            q = qrcode.QRCode(version=v, error_correction=ERROR_CORRECT_M,
                              box_size=1, border=0, mask_pattern=0)
            q.add_data(QRData(t.encode("utf-8"), mode=MODE_8BIT_BYTE), optimize=0)
            q.make(fit=False)
            pm = ["".join("1" if x else "0" for x in row) for row in q.get_matrix()]
            if pm == jm:
                qr_ok += 1
            else:
                print("    QR DIFF: bytes=%d v=%d" % (len(t.encode("utf-8")), v))
        chk("QR 矩阵 %d 个用例逐格一致" % qr_total, qrcode is not None and qr_ok == qr_total,
            "%d/%d 与 python-qrcode 一致" % (qr_ok, qr_total))
        report["groups"]["C_qr"] = {"cases": qr_total, "matched": qr_ok,
                                    "versions": [((int(c["size"]) - 17) // 4) if c["size"] else None
                                                 for c in data["qr_cases"]]}
    finally:
        srv.shutdown()

    # ---- D 组：JS 转发链路 ----
    print("\n[D 组] JS 转发链路（Playwright / Chromium）")
    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as pw:
            b = pw.chromium.launch(executable_path=CHROMIUM, headless=True,
                                   args=["--no-sandbox", "--allow-file-access-from-files",
                                         "--disable-dev-shm-usage"])

            def make_page(java_mock=True):
                pg = b.new_page()
                pg.set_content("<!doctype html><html><body></body></html>")
                if java_mock:
                    pg.evaluate(JAVA_MOCK_BRIDGE)
                for s in SCRIPTS:
                    pg.evaluate("(new Function(%s))()" %
                                json.dumps(open(os.path.join(ASSETS, s), encoding="utf-8").read()))
                # 与 verify_v2.py 的 H 组保持同一装配口径：走 HikerCompat.install，
                # 它会补齐 delegate 档（getInternalJs / toDetailPage / setHomeResult …）。
                pg.evaluate("""() => HikerCompat.install({isolation:'strict',
                    page:{MY_URL:'https://example.com/s?wd=test', MY_KEYWORD:'test',
                          MY_PAGE:1, MY_RULE:{title:'测试规则'}},
                    offlineHtml:'<html><a class="item">A</a></html>',
                    onResult:function(v,j){}});""")
                return pg

            pg = make_page(True)
            chk("md5 走 Java 桥真返回值",
                pg.evaluate("md5('hello')") == "5d41402abc4b2a76b9719d911017c592",
                pg.evaluate("md5('hello')"))
            calls = pg.evaluate("window.__HK_CALLS__")
            chk("bridgeCall 优先 HikerBridge.call", any(c[0] == "md5" for c in calls),
                "%d 次 call" % len(calls))
            chk("参数显式 JSON 序列化", any(c[0] == "md5" and c[1] == '["hello"]' for c in calls),
                json.dumps(calls[:2], ensure_ascii=False))
            chk("batchFetch 还原成数组",
                pg.evaluate("JSON.stringify(batchFetch(['u1','u2']))") == '["A","B"]',
                pg.evaluate("JSON.stringify(batchFetch(['u1','u2']))"))
            chk("CryptoJS.MD5 走 Java 桥",
                pg.evaluate("getCryptoJS().MD5('x').toString()") ==
                "5d41402abc4b2a76b9719d911017c592",
                pg.evaluate("getCryptoJS().MD5('x').toString()"))
            chk("CryptoJS.AES.encrypt 走 Java 桥",
                pg.evaluate("getCryptoJS().AES.encrypt('t','k').toString()") == "CIPHER",
                pg.evaluate("getCryptoJS().AES.encrypt('t','k').toString()"))
            chk("CryptoJS.AES.decrypt 走 Java 桥",
                pg.evaluate("getCryptoJS().AES.decrypt('c','k').toString()") == "PLAIN",
                pg.evaluate("getCryptoJS().AES.decrypt('c','k').toString()"))
            chk("X5 降级有留痕而非静默空",
                pg.evaluate("fetchCodeByWebView('u')") == "" and
                pg.evaluate("JSON.stringify(HikerApiSurface.reports).indexOf('degrade')>=0"),
                pg.evaluate("JSON.stringify(HikerApiSurface.reports).slice(-120)"))
            chk("FM_SHELL.call 转发到 Java 通用入口",
                pg.evaluate("FM_SHELL.call('md5',{args:['x']})") ==
                "5d41402abc4b2a76b9719d911017c592",
                pg.evaluate("FM_SHELL.call('md5',{args:['x']})"))
            chk("fy_bridge_app.getCookie 转发到 Java 桥",
                any(c[0] == "getCookie" and c[1] == '["u"]' for c in pg.evaluate(
                    "(function(){fy_bridge_app.getCookie('u');return window.__HK_CALLS__;})()")),
                "getCookie 调用已记录")
            chk("HikerHost.capabilities 带出 API 实现数",
                pg.evaluate("HikerHost.capabilities().api.implemented") == 35,
                pg.evaluate("JSON.stringify(HikerHost.capabilities().api)"))
            chk("官方 134 API 仍 134/134 可见",
                pg.evaluate("HikerApiSurface.coverage(window).available") == 134,
                pg.evaluate("JSON.stringify(HikerApiSurface.coverage(window).levels)"))
            pg.close()

            pg2 = make_page(False)
            chk("离线形态不抛异常且 134/134 可见",
                pg2.evaluate("typeof md5 === 'function' && "
                             "HikerApiSurface.coverage(window).available === 134"),
                pg2.evaluate("JSON.stringify(HikerApiSurface.coverage(window).levels)"))
            pg2.close()
            b.close()
    except Exception as e:
        chk("D 组 Playwright 可用", False, str(e)[:120])

    report["summary"] = {"passed": passed, "total": total,
                         "all_pass": passed == total,
                         "java_api_implemented": 35,
                         "bridge_official": 25,
                         "bridge_extra": 10}
    rp = os.path.join(a.out, "verify_bridge_api_report.json")
    json.dump(report, open(rp, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("\n" + "=" * 78)
    print("结果: %d/%d 断言通过   JSON: %s" % (passed, total, rp))
    print("=" * 78)
    return 0 if passed == total else 1


if __name__ == "__main__":
    raise SystemExit(main())

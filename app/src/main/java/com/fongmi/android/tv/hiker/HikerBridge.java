package com.fongmi.android.tv.hiker;

import android.webkit.JavascriptInterface;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * 独立 WebView 的「同步字符串」Java 桥（addJavascriptInterface 注入，桥名 HikerBridge）。
 *
 * 与首页桥的本质区别（兼容性分析 A 组结论）：
 *   - 首页 HomeWebBridge.invoke() -> Task.execute 线程池 -> fongmiNative.resolve 回调，异步 Promise；
 *   - 本类每个 @JavascriptInterface 方法都在 JavaBridge 线程**同步返回一个 String**。
 *     这正是 v390 jar 的 JsReqBridge / FM_SHELL.call、以及海阔原版 fy_bridge_app.fetch 的语义。
 *   - 海阔规则形如 eval.call(null, fetch(url))、pdfh(fetch(url), ...)，
 *     拿到 Promise 会静默变成 "[object Object]" 或抛错（阶段二、四已实测）。
 *
 * 零耦合设计：不 import 任何 com.fongmi.android.tv.* 业务类。
 * 播放走宿主已有的本地 HTTP 通道 /action?do=push（Action.onPush -> ServerEvent.push ->
 * HomeActivity.onServerEvent(PUSH) -> VideoActivity.push），端口按 Server.start() 的 9978~9998 探测。
 * 因此本类可用 ecj + android-all.jar 单独真实编译，不需要整个工程。
 */
public class HikerBridge {

    private final HikerActivity activity;
    private final Map<String, String> cache = new HashMap<>();
    private volatile int port = -1;

    public HikerBridge(HikerActivity activity) {
        this.activity = activity;
    }

    /* ------------------------------------------------------------------ 同步网络 */

    /** 海阔 fetch / request：同步返回正文字符串。options 为 JSON 字符串（method/headers/body）。 */
    @JavascriptInterface
    public String req(String url, String options) {
        if (url == null || url.length() == 0) return "";
        Opt o = Opt.parse(options);
        Map<String, String> headers = o.headers;
        if ("POST".equalsIgnoreCase(o.method)) return HikerHttp.post(url, o.body, headers);
        return HikerHttp.get(url, headers);
    }

    /** 历史别名：不同版本规则里 fetch / request / ajax 都可能出现。 */
    @JavascriptInterface
    public String request(String url, String options) {
        return req(url, options);
    }

    @JavascriptInterface
    public String fetch(String url, String options) {
        return req(url, options);
    }

    /** 兼容 fy_bridge_app.fetch(url, obj) 的签名（obj 为 JSON 字符串）。 */
    @JavascriptInterface
    public String fetchSync(String url, String options) {
        return req(url, options);
    }

    /* ------------------------------------------------------------------ 结果 / 事件回传 */

    /** setResult 回传：页面也可直接用，Java 侧转发生命周期事件。 */
    @JavascriptInterface
    public void report(String type, String payload) {
        if (activity != null) activity.onBridgeEvent(type, payload);
    }

    @JavascriptInterface
    public void log(String message) {
        report(HikerContract.EV_LOG, message);
    }

    /* ------------------------------------------------------------------ 播放出口 */

    /**
     * 桥接 App 播放器：走本地 /action?do=push，最终由宿主 VideoActivity 播放。
     * 不直接调用 VideoActivity.start，避免与首页/播放器代码耦合。
     */
    @JavascriptInterface
    public boolean play(String url, String title, String pic) {
        if (url == null || url.length() == 0) return false;
        try {
            String target = "http://127.0.0.1:" + detectPort() + HikerContract.PUSH_PATH
                    + URLEncoder.encode(url, "UTF-8");
            String r = HikerHttp.get(target, null);
            report(HikerContract.EV_PLAY, url);
            return r != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 探测宿主本地服务端口（Server.start 从 9978 起试到 9998）。 */
    private int detectPort() {
        if (port > 0) return port;
        for (int p = HikerEntry.PORT_MIN; p <= HikerEntry.PORT_MAX; p++) {
            if (alive(p)) {
                port = p;
                return p;
            }
        }
        return HikerEntry.PORT_MIN;
    }

    private boolean alive(int p) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + p + "/").openConnection();
            c.setConnectTimeout(300);
            c.setReadTimeout(300);
            c.getResponseCode();   // 任何 HTTP 响应（含 404）都说明端口在监听
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* ------------------------------------------------------------------ 兼容 fy_bridge_app / JsT4 的周边 API */

    @JavascriptInterface
    public String getInternalJs() {
        return "";
    }

    @JavascriptInterface
    public String getVar(String key) {
        String v = cache.get(key);
        return v == null ? "" : v;
    }

    @JavascriptInterface
    public void putVar(String key, String value) {
        cache.put(key, value == null ? "" : value);
    }

    @JavascriptInterface
    public String getCache(String key) {
        return getVar(key);
    }

    @JavascriptInterface
    public void setCache(String key, String value) {
        putVar(key, value);
    }

    /** 能力表：页面 HikerCompat.probe() 可用它做二次确认。 */
    @JavascriptInterface
    public String capabilities() {
        return "{\"bridge\":\"HikerBridge\",\"sync\":true,\"return\":\"string\","
                + "\"play\":\"local-push\",\"port\":" + detectPort() + ",\"es5\":true}";
    }

    /* ------------------------------------------------------------------ options 解析（不依赖 org.json，保证可独立编译） */

    private static final class Opt {
        String method = "GET";
        String body = "";
        Map<String, String> headers = new HashMap<>();

        /** 极简 JSON 解析，只取 method / body / headers 三个键，避免引入 gson 依赖。 */
        static Opt parse(String json) {
            Opt o = new Opt();
            if (json == null || json.length() == 0) return o;
            String m = str(json, "method");
            if (m != null && m.length() > 0) o.method = m;
            String b = str(json, "body");
            if (b != null) o.body = b;
            String h = obj(json, "headers");
            if (h != null) {
                java.util.regex.Matcher mt = java.util.regex.Pattern
                        .compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(h);
                while (mt.find()) o.headers.put(mt.group(1), mt.group(2));
            }
            return o;
        }

        private static String str(String s, String key) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(s);
            if (!m.find()) return null;
            return m.group(1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
        }

        private static String obj(String s, String key) {
            int i = s.indexOf("\"" + key + "\"");
            if (i < 0) return null;
            int st = s.indexOf('{', i);
            if (st < 0) return null;
            int depth = 0;
            for (int j = st; j < s.length(); j++) {
                char c = s.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return s.substring(st + 1, j);
                }
            }
            return null;
        }
    }
}

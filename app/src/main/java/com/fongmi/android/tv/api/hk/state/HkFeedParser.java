package com.fongmi.android.tv.api.hk.state;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.hk.HkHttp;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.api.hk.HkRuleManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 口令解析层：把「输入 → 真实站点」整条链交给星辰 HkRuleManager 中央分发。
 *
 * <p><b>对齐星辰 8.83 / RuleImporterManager</b>：引擎 HkRuleManager 与星辰逐字节一致
 * （仅把 java.nio 换成经典 IO 以兼容 minSdk 24），它已经支持：
 * <ul>
 *   <li>云1/2/5/6/7/9/10 剪贴板口令，含<b>访问密码</b>（口令后空格跟密码、
 *       云6 {@code /xxxxxx/xxx(@密码)} 自动重试）</li>
 *   <li>明文分享口令 {@code …￥home_rule￥{json}} / home_rule_v2</li>
 *   <li>{@code base64://@标题@BASE64} 包装、裸规则 JSON、规则数组（逐条 validate）</li>
 *   <li>{@code js:} 规则文本、.zip/.hkzip 规则包（含 require.json/libs.zip/data.zip 资产）</li>
 * </ul>
 *
 * <p><b>本层修复前的问题</b>：自己预拉文本，云口令交给只实现云5 的
 * {@code fetchPasteRawText()}，返回 null 就直接判失败 —— 云1/2/6/7/9/10、
 * 带密码口令、zip 规则包、订阅列表全部走不到；URL 用 string() 读二进制 zip 也会变乱码。
 * 现在除「URL 下载」和「zip 落盘」外一律不再自建判断，直接调 parseJsonList()。
 */
public final class HkFeedParser {

    private static final String TAG = "HkFeedParser";
    private static final int MAX_DEPTH = 4;

    public static final class Parsed {
        public final List<HkSiteEntry> sites = new ArrayList<>();
        public final List<String> invalidNames = new ArrayList<>();
        public boolean fetchFailed;
        public String error = "";
    }

    private HkFeedParser() {}

    /** 规范化：trim + 剥 ￥home_sub￥ / ￥home_rule_url￥ / ¥…¥ 展示标识，用于同口令判重。 */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        for (String tag : new String[]{"￥home_sub￥", "￥home_rule_url￥", "¥home_sub¥", "¥home_rule_url¥"}) {
            int i = s.indexOf(tag);
            if (i >= 0) { s = s.substring(i + tag.length()).trim(); break; }
        }
        return s;
    }

    /** 口令集：一条记录里可有多个子口令；子口令内先去重、不解析重复项。 */
    public static List<String> subFeeds(String raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : (raw == null ? "" : raw).split("[\r\n]+")) {
            String n = normalize(line);
            if (!n.isEmpty()) out.add(n);
        }
        return new ArrayList<>(out);
    }

    /** 解析一条口令（可能含多个子口令）。在后台线程调用。 */
    public static Parsed parse(String doc, HkFeed feed) {
        Parsed out = new Parsed();
        List<String> subs = subFeeds(feed.raw);
        if (subs.isEmpty()) { out.fetchFailed = true; out.error = "口令为空"; return out; }
        Set<String> seenFingerprint = new LinkedHashSet<>();
        for (String sub : subs) {
            try {
                resolve(doc, feed.feedId, sub, seenFingerprint, out, 0);
            } catch (Throwable e) {
                Log.w(TAG, "parse sub failed: " + e);
                out.fetchFailed = true;
                out.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        return out;
    }

    /**
     * 单条输入分发。depth 只用于订阅列表递归，防循环引用把线程跑死。
     */
    private static void resolve(String doc, String feedId, String text,
                                Set<String> fp, Parsed out, int depth) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        if (depth >= MAX_DEPTH) { out.fetchFailed = true; out.error = "订阅层级过深"; return; }

        // ① URL：下载字节。zip 走包解析；文本按订阅列表或规则文本继续分发。
        if (t.startsWith("http://") || t.startsWith("https://")) {
            byte[] bytes = fetchBytes(t);
            if (bytes == null || bytes.length == 0) {
                out.fetchFailed = true; out.error = "拉取失败"; return;
            }
            if (isZip(bytes) || isZipUrl(t)) {
                if (!parseZipBytes(doc, feedId, bytes, fp, out)) {
                    out.fetchFailed = true; out.error = "规则包内没有合法规则";
                }
                return;
            }
            t = new String(bytes, StandardCharsets.UTF_8).trim();
            if (looksLikeFeedList(t)) {
                for (String line : t.split("[\r\n]+")) {
                    String s = feedFromLine(line);
                    if (!s.isEmpty()) resolve(doc, feedId, s, fp, out, depth + 1);
                }
                return;
            }
        }

        // ② 星辰中央分发：云口令（含密码）/ 明文口令 / base64 壳 / 裸 JSON / 规则数组。
        List<HkRuleManager.ParsedRule> parsed = null;
        Throwable err = null;
        try {
            parsed = HkRuleManager.get().parseJsonList(t);
        } catch (Throwable e) {
            err = e;
        }
        if (parsed != null && !parsed.isEmpty()) {
            collectParsed(doc, feedId, parsed, fp, out);
            collectInvalidNames(t, out);
            return;
        }

        // ③ js: 规则文本（parseJsonList 不认，星辰 importJsRule 语义）
        if (HkRuleManager.isJsRuleText(t)) {
            try {
                HkRule r = HkRuleManager.get().importJsRule(t, "feed_" + feedId + ".js");
                if (r != null && !TextUtils.isEmpty(r.getTitle())) addRule(doc, feedId, r, t, fp, out);
            } catch (Throwable e) { out.fetchFailed = true; }
            return;
        }

        // ④ 聚合类云口令（聚阅等）：通道里是接口列表文本而非规则 JSON，
        //    parseJsonList 按规则解析失败后取原文，按行重新分发（星辰 parsePaste 语义）。
        String raw = HkRuleManager.get().fetchPasteRawText(t);
        if (!TextUtils.isEmpty(raw) && !raw.equals(t)) {
            if (looksLikeFeedList(raw)) {
                for (String line : raw.split("[\r\n]+")) {
                    String s = feedFromLine(line);
                    if (!s.isEmpty()) resolve(doc, feedId, s, fp, out, depth + 1);
                }
                return;
            }
            try {
                List<HkRuleManager.ParsedRule> p2 = HkRuleManager.get().parseJsonList(raw);
                if (p2 != null && !p2.isEmpty()) { collectParsed(doc, feedId, p2, fp, out); return; }
            } catch (Throwable ignored) {}
        }

        out.fetchFailed = true;
        out.error = err == null ? "无法识别的订阅格式"
                : (err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage());
    }

    /** URL 拉取字节；走 HkHttp.client()（继承 OkHttp.selector() 代理总闸）。 */
    private static byte[] fetchBytes(String url) {
        try {
            OkHttpClient c = HkHttp.client();
            Request req = new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build();
            try (Response resp = c.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                return resp.body().bytes();
            }
        } catch (Throwable e) {
            Log.w(TAG, "fetch url failed: " + e);
            return null;
        }
    }

    private static boolean isZip(byte[] b) {
        return b != null && b.length >= 4 && b[0] == 'P' && b[1] == 'K'
                && (b[2] == 3 || b[2] == 5 || b[2] == 7);
    }

    private static boolean isZipUrl(String url) {
        String u = url.toLowerCase().split("\\?")[0];
        return u.endsWith(".zip") || u.endsWith(".hkzip");
    }

    /** .zip / .hkzip：星辰 importHkZip 同款，规则落盘 + require.json/libs/data.zip 资产落盘。 */
    private static boolean parseZipBytes(String doc, String feedId, byte[] bytes,
                                         Set<String> fp, Parsed out) {
        File tmp = null;
        try {
            File dir = App.get() == null ? null : App.get().getCacheDir();
            tmp = dir == null ? File.createTempFile("hkfeed-", ".zip")
                              : File.createTempFile("hkfeed-", ".zip", dir);
            try (FileOutputStream os = new FileOutputStream(tmp)) { os.write(bytes); }
            HkRuleManager mgr = HkRuleManager.get();
            HkRuleManager.HkZipData data = mgr.parseHkZip(tmp);
            List<HkRule> saved = new ArrayList<>();
            for (HkRuleManager.ParsedRule p : data.rules) {
                addRule(doc, feedId, p.rule, p.json, fp, out);
                saved.add(p.rule);
            }
            try { mgr.saveHkZipAssets(data, saved); } catch (Throwable ignored) {}
            return !data.rules.isEmpty();
        } catch (Throwable e) {
            Log.w(TAG, "parse zip failed: " + e);
            return false;
        } finally {
            if (tmp != null) try { tmp.delete(); } catch (Throwable ignored) {}
        }
    }

    private static void collectParsed(String doc, String feedId,
                                      List<HkRuleManager.ParsedRule> parsed,
                                      Set<String> fp, Parsed out) {
        for (HkRuleManager.ParsedRule p : parsed) {
            if (p == null || p.rule == null) continue;
            addRule(doc, feedId, p.rule, p.json, fp, out);
        }
    }

    private static void addRule(String doc, String feedId, HkRule r, String json,
                                Set<String> fp, Parsed out) {
        String name = nvl(r.getTitle());
        if (name.isEmpty()) { out.invalidNames.add("(无名称)"); return; }
        String key = fingerprint(name, nvl(r.getUrl()), nvl(r.getFindRule()));
        if (!fp.add(key)) return;   // 配置相同 → 单接口内谁先留谁
        try { HkRuleManager.get().saveRule(r, json); } catch (Throwable ignored) {}
        HkSiteEntry e = new HkSiteEntry();
        e.doc = doc; e.feedId = feedId; e.id = doc + "|" + feedId + "|" + key;
        e.title = name; e.displayName = name; e.mark = "正常"; e.origin = "auto";
        out.sites.add(e);
    }

    /** 订阅列表：多行、每行都是独立口令/URL；带 $ 分隔的取最后一段（常见「名称$口令」）。 */
    private static boolean looksLikeFeedList(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.startsWith("{") || s.startsWith("[")) return false;
        int n = 0;
        for (String line : s.split("[\r\n]+")) {
            String x = feedFromLine(line);
            if (x.isEmpty()) continue;
            if (x.startsWith("http") || HkRuleManager.isCloudCode(x)
                    || x.contains("￥") || x.contains("¥") || x.startsWith("base64://")
                    || x.startsWith("js:")) n++;
            else return false;   // 有说明行就不切，避免把明文口令截碎
        }
        return n >= 2;
    }

    private static String feedFromLine(String line) {
        String x = line == null ? "" : line.trim();
        int d = x.lastIndexOf('$');
        if (d >= 0) return x.substring(d + 1).trim();
        return x;
    }

    /** 收集规则数组里不合规条目的名字（无 title 或既无 url 又无 find_rule）。 */
    private static void collectInvalidNames(String t, Parsed out) {
        if (t == null || !t.trim().startsWith("[")) return;
        try {
            JsonArray arr = JsonParser.parseString(t).getAsJsonArray();
            for (JsonElement el : arr) {
                if (el == null || !el.isJsonObject()) { out.invalidNames.add("(非对象)"); continue; }
                JsonObject o = el.getAsJsonObject();
                String name = str(o, "title");
                boolean valid = !name.isEmpty() && (!str(o, "url").isEmpty() || !str(o, "find_rule").isEmpty());
                if (!valid) out.invalidNames.add(name.isEmpty() ? "(无名称)" : name);
            }
        } catch (Throwable ignored) {}
    }

    private static String str(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
            return e.getAsString();
        } catch (Throwable ignored) { return ""; }
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    private static String fingerprint(String title, String url, String findRule) {
        return md5(title + "|" + url + "|" + findRule);
    }

    public static String md5(String s) {
        try {
            byte[] b = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Throwable e) { return Integer.toHexString(s.hashCode()); }
    }
}

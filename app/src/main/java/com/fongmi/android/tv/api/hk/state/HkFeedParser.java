package com.fongmi.android.tv.api.hk.state;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.api.hk.HkRuleManager;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 口令解析层：同口令不解析直接合并；其余按形态拉取并逐条校验。
 *
 * <p><b>对齐星辰 8.83 / RuleImporterManager 中央分发</b>：文本一律先交给
 * {@link HkRuleManager#parseJsonList(String)}，它内部识别小程序明文口令
 * 「…小程序￥home_rule￥{json}」、base64:// 壳、云口令、单规则对象、规则数组
 * （一个文件多个小程序），并逐条 validate()。此前这里自建了一套只认
 * 「{ 或 [ 开头」的判断，把小程序分享口令判成「无法识别的订阅格式」，
 * 于是 0 站点、口令自动标失效 —— 就是「录入后列表看不到小程序」的根因。
 */
public final class HkFeedParser {

    private static final String TAG = "HkFeedParser";

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
                String body;
                if (HkRuleManager.isCloudCode(sub)) {
                    body = HkRuleManager.get().fetchPasteRawText(sub);
                    if (TextUtils.isEmpty(body)) { out.fetchFailed = true; continue; }
                } else if (sub.startsWith("http://") || sub.startsWith("https://")) {
                    body = OkHttp.string(sub);
                    if (TextUtils.isEmpty(body)) { out.fetchFailed = true; continue; }
                } else {
                    body = sub;
                }
                collect(doc, feed.feedId, body, seenFingerprint, out);
            } catch (Throwable e) {
                // Error（如 minSdk 24 上 java.nio 的 NoClassDefFoundError）也必须兜住，
                // 否则后台线程未捕获异常直接杀进程。
                Log.w(TAG, "parse sub failed: " + e);
                out.fetchFailed = true;
                out.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        return out;
    }

    private static void collect(String doc, String feedId, String body,
                                Set<String> seenFingerprint, Parsed out) {
        String t = body == null ? "" : body.trim();
        if (t.isEmpty()) { out.fetchFailed = true; out.error = "内容为空"; return; }

        // 星辰同款中央分发：小程序明文口令 / base64 壳 / 云口令 / 单规则 / 规则数组。
        List<HkRuleManager.ParsedRule> parsed = null;
        Throwable parseErr = null;
        try {
            parsed = HkRuleManager.get().parseJsonList(t);
        } catch (Throwable e) {
            parseErr = e;
        }

        if (parsed != null && !parsed.isEmpty()) {
            for (HkRuleManager.ParsedRule p : parsed) {
                if (p == null || p.rule == null) continue;
                HkRule r = p.rule;
                String name = nvl(r.getTitle());
                if (name.isEmpty()) { out.invalidNames.add("(无名称)"); continue; }
                String fp = fingerprint(name, nvl(r.getUrl()), nvl(r.getFindRule()));
                if (!seenFingerprint.add(fp)) continue;       // 配置相同 → 单接口内谁先留谁
                // 规则本体落盘，HkRouter 才能按 title 取到（星辰 importJsonList 语义）。
                try { HkRuleManager.get().saveRule(r, p.json); } catch (Throwable ignored) {}
                HkSiteEntry e = new HkSiteEntry();
                e.doc = doc; e.feedId = feedId; e.id = doc + "|" + feedId + "|" + fp;
                e.title = name; e.displayName = name;
                e.mark = "正常"; e.origin = "auto";
                out.sites.add(e);
            }
            // 数组里损坏/不合规的条目只记名字（部分有效场景），不影响其它。
            collectInvalidNames(t, out);
            return;
        }

        // js: 规则文本（星辰 parseJsRule 语义）
        if (HkRuleManager.isJsRuleText(t)) {
            try {
                HkRule r = HkRuleManager.get().importJsRule(t, "feed_" + feedId + ".js");
                if (r != null && !TextUtils.isEmpty(r.getTitle())) {
                    String fp = fingerprint(r.getTitle(), nvl(r.getUrl()), nvl(r.getFindRule()));
                    if (seenFingerprint.add(fp)) {
                        HkSiteEntry e = new HkSiteEntry();
                        e.doc = doc; e.feedId = feedId; e.id = doc + "|" + feedId + "|" + fp;
                        e.title = r.getTitle(); e.displayName = r.getTitle();
                        e.mark = "正常"; e.origin = "auto";
                        out.sites.add(e);
                    }
                }
            } catch (Throwable e) { out.fetchFailed = true; }
            return;
        }

        out.fetchFailed = true;
        out.error = parseErr == null ? "无法识别的订阅格式"
                : (parseErr.getMessage() == null ? parseErr.getClass().getSimpleName() : parseErr.getMessage());
    }

    /** 收集规则数组里不合规条目的名字（无 title 或既无 url 又无 find_rule）。 */
    private static void collectInvalidNames(String t, Parsed out) {
        if (!t.startsWith("[")) return;
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

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
 * 有效条目交给 HkRuleManager 落盘（运行时缓存），失效条目只回名字给更新器写 mark。
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
        for (String line : (raw == null ? "" : raw).split("[\\r\\n]+")) {
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
            } catch (Exception e) {
                Log.w(TAG, "parse sub failed: " + e.getMessage());
                out.fetchFailed = true;
                out.error = e.getMessage() == null ? "" : e.getMessage();
            }
        }
        return out;
    }

    private static void collect(String doc, String feedId, String body,
                                Set<String> seenFingerprint, Parsed out) throws Exception {
        String t = body.trim();
        JsonElement root = null;
        if (t.startsWith("{") || t.startsWith("[")) {
            try { root = JsonParser.parseString(t); } catch (Exception ignored) {}
        }
        if (root != null) {
            JsonArray arr = root.isJsonArray() ? root.getAsJsonArray() : wrap(root.getAsJsonObject());
            for (JsonElement el : arr) {
                if (el == null || !el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String name = str(o, "title");
                boolean valid = !name.isEmpty() && (!str(o, "url").isEmpty() || !str(o, "find_rule").isEmpty());
                String fp = fingerprint(o);
                if (!valid) { out.invalidNames.add(name.isEmpty() ? "(无名称)" : name); continue; }
                if (!seenFingerprint.add(fp)) continue;       // 配置相同 → 单接口内谁先留谁
                HkSiteEntry e = new HkSiteEntry();
                e.doc = doc; e.feedId = feedId; e.id = doc + "|" + feedId + "|" + fp;
                e.title = name; e.displayName = name;
                e.mark = "正常"; e.origin = "auto";
                out.sites.add(e);
            }
            // 有效条目交给引擎落盘，运行时 HkRouter 才能拿到规则本体
            try { HkRuleManager.get().importJsonList(new com.google.gson.Gson().toJson(root)); } catch (Exception ignored) {}
        } else {
            // js: 规则文本 / 云口令返回的非 JSON 文本
            if (HkRuleManager.isJsRuleText(t)) {
                try {
                    HkRule r = HkRuleManager.get().importJsRule(t, "feed_" + feedId + ".js");
                    if (r != null && r.getTitle() != null && !r.getTitle().isEmpty()) {
                        String fp = fingerprint(r.getTitle(), r.getUrl(), r.getFindRule());
                        if (seenFingerprint.add(fp)) {
                            HkSiteEntry e = new HkSiteEntry();
                            e.doc = doc; e.feedId = feedId; e.id = doc + "|" + feedId + "|" + fp;
                            e.title = r.getTitle(); e.displayName = r.getTitle();
                            e.mark = "正常"; e.origin = "auto";
                            out.sites.add(e);
                        }
                    }
                } catch (Exception e) { out.fetchFailed = true; }
            } else {
                out.fetchFailed = true;
                out.error = "无法识别的订阅格式";
            }
        }
    }

    private static JsonArray wrap(JsonObject o) {
        JsonArray a = new JsonArray(); a.add(o); return a;
    }

    private static String str(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
            return e.getAsString();
        } catch (Exception ignored) { return ""; }
    }

    private static String fingerprint(JsonObject o) {
        return fingerprint(str(o, "title"), str(o, "url"), str(o, "find_rule"));
    }

    private static String fingerprint(String title, String url, String findRule) {
        return md5(title + "|" + url + "|" + findRule);
    }

    public static String md5(String s) {
        try {
            byte[] b = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }
}

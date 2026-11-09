package com.fongmi.android.tv.utils;

import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在线订阅配置的「解析用副本」规范化。
 *
 * <p>远程配置是别人维护的，随时会更新、也会出现脏字段。这里只在交给解析器之前
 * 修一份内存副本：原始 json（网络返回 / 缓存 / Config 表）一个字节都不改，
 * 正常配置的字段值与次序也完全不变，只有确实会出问题的条目才被修正。</p>
 *
 * <p>与 {@link LocalScriptSource#buildJson(String)} 里本地脚本那份 parseSites 的区别：
 * 本地脚本站点由 app 自己生成，hide 一律强制 0；在线配置里 hide=1 是作者故意隐藏的
 * 站点，属于配置语义，这里<b>保留原值</b>，只把非法值（对象 / 数组 / 非数字）移除
 * 交给 getter 的默认值兜底。</p>
 */
public final class ConfigSanitizer {

    /** 必须是字符串的字段：非 primitive 会让 Gson 抛异常，整条站点被 objectFrom 吞掉。 */
    private static final String[] TEXT_FIELDS = {
            "key", "name", "api", "ext", "proxy", "jar", "click", "playUrl",
            "homePage", "home_page", "webHome", "web_home", "chromeMode"
    };

    /** 必须是整数的字段：非法值移除即可，Site 的 getter 有 0 / 1 兜底。 */
    private static final String[] NUMBER_FIELDS = {
            "type", "hide", "indexs", "timeout", "searchable", "changeable", "quickSearch"
    };

    /** 必须是数组的字段（写错类型会让 Gson 抛异常）。 */
    private static final String[] ARRAY_FIELDS = {"categories"};

    /** 必须是对象的字段（写错类型会让 Gson 抛异常）。 */
    private static final String[] OBJECT_FIELDS = {"style"};

    private ConfigSanitizer() {
    }

    /**
     * 安全取列表：兼容「数组」与「单个对象」两种写法，数组里混进非对象元素时跳过，
     * 字段不是数组 / 对象时返回空列表而不是抛异常。语义与 Json.safeListElement 一致，
     * 只是不再让整份配置因一个坏条目加载失败。
     */
    public static List<JsonElement> list(JsonObject object, String key) {
        List<JsonElement> result = new ArrayList<>();
        if (object == null || !object.has(key)) return result;
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return result;
        if (value.isJsonObject()) {
            result.add(value.getAsJsonObject());
        } else if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (item != null && item.isJsonObject()) result.add(item.getAsJsonObject());
            }
        }
        return result;
    }

    /**
     * sites 专用：在 {@link #list} 的基础上补齐主键、统一字段类型。
     * 保证每条都能被 Site.objectFrom 解析，且不会被 .distinct() 按 key 吞掉。
     */
    public static List<JsonElement> sites(JsonObject object) {
        List<JsonElement> raw = list(object, "sites");
        List<JsonElement> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int fixed = 0;
        int index = 0;
        for (JsonElement element : raw) {
            JsonObject copy = shallowCopy(element.getAsJsonObject());
            for (String field : TEXT_FIELDS) {
                if (!copy.has(field)) continue;
                JsonElement value = copy.get(field);
                if (value == null || value.isJsonNull()) {
                    copy.addProperty(field, "");
                    fixed++;
                } else if (!value.isJsonPrimitive()) {
                    copy.addProperty(field, value.toString());
                    fixed++;
                }
            }
            for (String field : NUMBER_FIELDS) {
                if (!copy.has(field)) continue;
                JsonElement value = copy.get(field);
                boolean bad = value == null || value.isJsonNull() || !value.isJsonPrimitive();
                if (!bad) {
                    try {
                        value.getAsInt();
                    } catch (Throwable ignored) {
                        bad = true;
                    }
                }
                if (bad) {
                    copy.remove(field);
                    fixed++;
                }
            }
            for (String field : ARRAY_FIELDS) {
                if (copy.has(field) && !copy.get(field).isJsonArray()) {
                    copy.remove(field);
                    fixed++;
                }
            }
            for (String field : OBJECT_FIELDS) {
                if (copy.has(field) && !copy.get(field).isJsonObject()) {
                    copy.remove(field);
                    fixed++;
                }
            }
            String key = text(copy, "key").trim();
            if (key.isEmpty()) {
                key = "site_" + index;
                copy.addProperty("key", key);
                fixed++;
            }
            if (!keys.add(key)) {
                key = key + "#" + index;
                copy.addProperty("key", key);
                fixed++;
            }
            if (text(copy, "name").trim().isEmpty()) {
                copy.addProperty("name", key);
                fixed++;
            }
            result.add(copy);
            index++;
        }
        SpiderDebug.log("config", "sites in=%s out=%s fixed=%s", raw.size(), result.size(), fixed);
        return result;
    }


    /**
     * spider 字段：猫源爬虫运行时，支持「地址;md5;校验值或 .md5 文件 URL」三段式。
     *
     * <p>JarLoader.parseJar 会 split(";md5;")：第三段以 http 开头时直接
     * OkHttp.string(md5) 拉取 .md5 文件内容，再与本地缓存文件的 MD5 比对，
     * 不一致才重新下载第一段（js / jar）。所以 index.js + index.js.md5 这类
     * 「两部分组成」的猫源写法是配置里 spider 字段的形态，不是接口地址本身。</p>
     *
     * <p>只修解析用副本：字段写成数组时取首个字符串、写成对象时拼 {url,md5}；
     * 三段各自 trim 并保留第三段 http 引用；单段原样返回，正常配置一个字节不变。
     * 字段缺失或无法兜底时返回空串（与 Json.safeString 的失败行为一致）。</p>
     */
    public static String spider(JsonObject object) {
        if (object == null || !object.has("spider")) return "";
        JsonElement value = object.get("spider");
        String raw = "";
        if (value != null && value.isJsonPrimitive()) {
            raw = value.getAsString();
        } else if (value != null && value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (item != null && item.isJsonPrimitive()) { raw = item.getAsString(); break; }
            }
        } else if (value != null && value.isJsonObject()) {
            JsonObject item = value.getAsJsonObject();
            String url = text(item, "url");
            if (url.trim().isEmpty()) url = text(item, "spider");
            String md5 = text(item, "md5");
            if (!url.trim().isEmpty()) raw = md5.trim().isEmpty() ? url : url + ";md5;" + md5;
        }
        if (raw == null || raw.trim().isEmpty()) return "";
        String[] parts = raw.split(";md5;", 2);
        if (parts.length == 1) return raw;
        String url = parts[0].trim();
        String md5 = parts[1].trim();
        if (url.isEmpty()) return "";
        SpiderDebug.log("config", "spider url=%s md5ref=%s", url, md5.startsWith("http"));
        return md5.isEmpty() ? url : url + ";md5;" + md5;
    }

    /** 浅拷贝：JsonElement 本身是只读共享的，这里只在副本上替换 / 删除字段。 */
    private static JsonObject shallowCopy(JsonObject source) {
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) copy.add(entry.getKey(), entry.getValue());
        return copy;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}

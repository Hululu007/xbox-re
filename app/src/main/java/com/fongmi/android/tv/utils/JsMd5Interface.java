package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 猫源「JS 运行时 + MD5 校验」单文件接口的自动合成。
 *
 * <p>用户把 {@code https://.../index.js.md5}（或 {@code index.js}）直接填进
 * 「新增点播 / 新增直播」时，它并不是一份配置 json：前者只是 32 字节校验文本，
 * 后者是 drpy / 猫源 JS 爬虫本体。这里在解析前按 URL 形态短路，合成一份
 * <b>内存配置</b>（不落盘、不改用户填的原始 url），让它像影视TV接口一样出站点。</p>
 *
 * <p>为什么把 {@code ;md5;} 放在条目的 {@code jar} 而不是顶层 {@code spider}：
 * 顶层 spider 会被 {@code BaseLoader.parseJar(spider, true)} 预加载，并且
 * {@link com.fongmi.android.tv.bean.Site#objectFrom} / Live.objectFrom 会把空 jar
 * 继承成 spider；放在条目 jar 上则由 {@code JsLoader.getSpider -> BaseLoader.dex(jar)}
 * 在站点首次使用时触发 {@code JarLoader.parseJar}：拉 .md5、比对
 * {@code Path.jar(jsUrl)} 缓存、不一致才重新下载 js。既复用了既有校验链，
 * 又不会把 664KB 的纯文本 js 当成 dex 去 init（createFun 的 catch 会兜住）。</p>
 *
 * <p>api 仍保持远程 http(s) 地址：{@code Module.fetch} 只认 http / assets / lib/，
 * 由它拉源码交给 quickjs 执行；{@code UrlUtil.convert} 对 http 原样返回。</p>
 */
public final class JsMd5Interface {

    private JsMd5Interface() {
    }

    /** 只认 http(s) 且末段为 .js 或 .js.md5 的地址，普通配置 json 不受影响。 */
    public static boolean matches(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String scheme = UrlUtil.scheme(url);
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        String path = UrlUtil.path(url).toLowerCase();
        return path.endsWith(".js") || path.endsWith(".js.md5");
    }

    /**
     * 合成一份只含 sites 或 lives 的内存配置。
     *
     * @param live true=直播入口（生成 lives），false=点播入口（生成 sites）
     */
    public static JsonObject build(String url, boolean live) {
        String raw = url.trim();
        String js = raw.endsWith(".md5") ? raw.substring(0, raw.length() - 4) : raw;
        String md5 = raw.endsWith(".js.md5") ? raw : js + ".md5";
        String jar = js + ";md5;" + md5;
        String hash = Util.md5(js);
        if (hash == null) hash = "";
        String key = "js_" + (hash.length() >= 8 ? hash.substring(0, 8) : hash);

        JsonObject item = new JsonObject();
        item.addProperty("name", displayName(js));
        item.addProperty("type", 3);
        item.addProperty("api", js);
        item.addProperty("ext", "");
        item.addProperty("jar", jar);   // 显式非空：避免 objectFrom 继承顶层 spider
        item.addProperty("searchable", 1);
        item.addProperty("changeable", 1);
        if (!live) item.addProperty("key", key);

        JsonArray list = new JsonArray();
        list.add(item);
        JsonObject root = new JsonObject();
        root.add(live ? "lives" : "sites", list);
        SpiderDebug.log("config", "js-md5 interface raw=%s js=%s md5=%s live=%s", raw, js, md5, live);
        return root;
    }

    private static String displayName(String js) {
        String name = UrlUtil.path(js);
        if (name.endsWith(".js")) name = name.substring(0, name.length() - 3);
        return TextUtils.isEmpty(name) ? "JS" : name;
    }
}

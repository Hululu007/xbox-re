package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.LocalScriptSource;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 站点级代理 / ext 配置。
 *
 * 参照 AVBox xbox 分支 util/SiteProxy.kt、SiteExt.kt 的判定语义，但按本项目口径
 * 「稍微有些不同」地落到配置本身：
 *   - 代理图标是循环开关：明 = 当前站点配置含代理条件，暗 = 不含；点一下即翻转，
 *     即使原本配置自带代理，也能点暗、再点亮。
 *   - 翻转直接写回配置：本地 json 接口（含 脚本扫描.json）写回文件本身；
 *     在线接口写本地缓存，配置重新解析时回填，绝不改写在线源数据。
 *   - json 写回规则：开启时若现值已是有效代理地址或 "proxy" 占位则保持，
 *     空 / 无参数才写 "proxy":"proxy"；关闭时统一写 ""；
 *     绝不把 app 默认代理地址（2080）写进 json。
 *   - 运行时归一：proxy 字段非空（占位或任意有效地址）一律使用默认代理地址。
 *   - 生效复用本项目 OkHttp.selector() + Proxy.bean；规则集按站点各自的 host 生成，
 *     因此切站点 / 搜索全站时各站点按自己的配置走代理，手动开关不串站。
 */
public final class SiteFeature {

    public static final String DEFAULT_PROXY_URL = "http://127.0.0.1:2080";
    public static final String PROXY_FLAG = "proxy";

    private static final String RULE_NAME = "site_proxy";
    private static final String K_URL = "site_feature_proxy_url";
    private static final String K_PROXY = "site_feature_proxy|";
    private static final String K_EXT = "site_feature_ext|";
    private static final Pattern HOST = Pattern.compile("(?i)https?://([^/\\s:]+)");

    private SiteFeature() {
    }

    /* ---------------- 默认代理地址 ---------------- */

    public static String proxyUrl() {
        String url = Prefers.getString(K_URL, "").trim();
        return TextUtils.isEmpty(url) ? DEFAULT_PROXY_URL : url;
    }

    public static void setProxyUrl(String url) {
        String value = url == null ? "" : url.trim();
        Prefers.put(K_URL, TextUtils.isEmpty(value) ? DEFAULT_PROXY_URL : value);
    }

    /* ---------------- 配置读写 ---------------- */

    private static boolean isLocal() {
        return LocalScriptSource.isLocalConfig(VodConfig.getUrl());
    }

    private static String cacheKey(String field, Site site) {
        return ("proxy".equals(field) ? K_PROXY : K_EXT) + VodConfig.getUrl() + "|" + site.getKey();
    }

    /** 配置重新解析后同步：本地 json 以文件为准，清掉旧缓存；在线接口回填本地缓存。 */
    public static void onConfigParsed(Site site) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        if (isLocal()) {
            Prefers.getPrefers().edit()
                    .remove(cacheKey("proxy", site))
                    .remove(cacheKey("ext", site))
                    .apply();
            return;
        }
        site.setProxy(Prefers.getString(cacheKey("proxy", site), site.getProxy()));
        site.setExt(Prefers.getString(cacheKey("ext", site), site.getExt()));
    }

    /**
     * 写回一个字段：本地 json 接口写文件；在线接口只写缓存。
     * 两种接口都同步内存中的 Site，保证图标与本次访问立即生效。
     */
    private static void write(String field, Site site, String value) {
        if (isLocal()) LocalScriptSource.updateSiteField(VodConfig.getUrl(), site.getKey(), field, value);
        Prefers.put(cacheKey(field, site), value);
        if ("proxy".equals(field)) site.setProxy(value);
        else site.setExt(value);
    }

    /* ---------------- 代理：循环开关 ---------------- */

    /** 明暗直接反映当前站点配置：proxy 非空即明，空即暗。 */
    public static boolean isOn(Site site) {
        return site != null && !TextUtils.isEmpty(site.getProxy());
    }

    /** 运行时归一：非空（占位或任意有效代理地址）一律取默认代理地址；app 后台替换，不写回 json。 */
    public static String resolve(Site site) {
        return isOn(site) ? proxyUrl() : "";
    }

    /**
     * 循环开关：明 → 暗、暗 → 明。
     * 开启：现值为空 / 无参数 → 写 "proxy"；现值已是有效地址或 "proxy" → 保持不动。
     * 关闭：无论 "proxy" 还是有效地址，统一写 ""。
     * json 层面绝不写入 2080 地址。
     */
    public static boolean toggle(Site site) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return false;
        boolean on = !isOn(site);
        String current = site.getProxy();
        String next = on ? (TextUtils.isEmpty(current) ? PROXY_FLAG : current) : "";
        write("proxy", site, next);
        apply();
        return on;
    }

    /* ---------------- ext ---------------- */

    /** 弹窗预填值：本地 json 接口就是文件里的值，在线接口是回填后的缓存值。 */
    public static String ext(Site site) {
        return site == null ? "" : site.getExt();
    }

    /**
     * 改过才写：有 ext 参数改值，无参数 / 空值则新增参数与值（JSONObject.put 自动新增）。
     * 本地 json 写回文件，在线接口写缓存，语义一致。
     */
    public static void setExt(Site site, String value) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        String text = value == null ? "" : value.trim();
        if (text.equals(site.getExt())) return;
        write("ext", site, text);
    }

    /* ---------------- 生效：按站点各自的 host 生成规则集 ---------------- */

    /**
     * 重建规则集：每个配置了代理的站点按自己 api/homePage/ext 里的 host 挂一条规则，
     * 于是切站点 / 搜索全站时各站点按各自配置走代理，手动开关不串站。
     * 本地脚本 / CSP 站点没有可提取的 http host（脚本内部请求 host 不定），
     * 仅当它是当前首页站点时退化为通配规则（与 xbox 的 applyGlobal 一致）。
     */
    public static void apply() {
        try {
            OkHttp.selector().remove(RULE_NAME);
            OkHttp.closeIdleConnections();
            List<Site> sites = VodConfig.get().getSites();
            String homeKey = VodConfig.get().getHome().getKey();
            JSONArray rules = new JSONArray();
            for (Site site : sites) {
                if (!isOn(site)) continue;
                JSONArray hosts = new JSONArray();
                for (String host : hostsOf(site)) hosts.put(host);
                if (hosts.length() == 0) {
                    if (!site.getKey().equals(homeKey)) continue;
                    hosts.put("*");
                }
                JSONObject rule = new JSONObject();
                rule.put("name", RULE_NAME);
                rule.put("hosts", hosts);
                rule.put("urls", new JSONArray().put(proxyUrl()));
                rules.put(rule);
            }
            if (rules.length() > 0) OkHttp.selector().addAll(Proxy.arrayFrom(Json.parse(rules.toString())));
        } catch (Throwable ignored) {
        }
    }

    private static List<String> hostsOf(Site site) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : new String[]{site.getApi(), site.getHomePage(), site.getJar(),
                site.getExt(), site.getClick(), site.getPlayUrl()}) {
            if (TextUtils.isEmpty(raw)) continue;
            Matcher m = HOST.matcher(raw);
            while (m.find()) {
                String host = m.group(1);
                if ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)) continue;
                out.add(host);
            }
        }
        return new ArrayList<>(out);
    }
}

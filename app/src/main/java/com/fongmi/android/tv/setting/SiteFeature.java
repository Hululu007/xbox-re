package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.LocalScriptSource;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Prefers;

/**
 * 站点级代理 / ext 覆盖。
 *
 * 代理判定参照 AVBox xbox 分支 util/SiteProxy.kt，按本项目口径归一：
 *   - proxy 字段为空        -> 不代理；
 *   - 非空（"proxy" 占位、或任意合法代理地址）-> 一律取「默认代理地址」；
 *   - 明暗 = 全局代理访问模式（手动点首页按钮） OR 当前站点配置启用代理。
 *
 * 生效方式复用本项目既有链路：OkHttp.selector() + Proxy.bean 通配规则，
 * 不改动 ProxySetting / ShellProxyDialog 的原有规则（规则名不同，互不覆盖）。
 */
public final class SiteFeature {

    public static final String DEFAULT_PROXY_URL = "http://127.0.0.1:2080";

    private static final String RULE_NAME = "site_proxy";
    private static final String K_URL = "site_feature_proxy_url";
    private static final String K_GLOBAL = "site_feature_proxy_global";
    private static final String K_EXT = "site_feature_ext|";

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

    /* ---------------- 代理判定 ---------------- */

    /** 站点配置里的 proxy 归一：空 -> ""，非空 -> 默认代理地址。运行时替换，不回写 json。 */
    public static String resolve(Site site) {
        if (site == null) return "";
        return TextUtils.isEmpty(site.getProxy().trim()) ? "" : proxyUrl();
    }

    public static boolean configured(Site site) {
        return !TextUtils.isEmpty(resolve(site));
    }

    public static boolean global() {
        return Prefers.getBoolean(K_GLOBAL, false);
    }

    public static void setGlobal(boolean on) {
        Prefers.put(K_GLOBAL, on);
    }

    /** 明暗：全局开关 OR 站点配置。 */
    public static boolean isOn(Site site) {
        return global() || configured(site);
    }

    /** 按当前站点应用 / 清除代理；站点级规则与 ProxySetting 的 "app" 规则互不影响。 */
    public static void apply(Site site) {
        String url = isOn(site) ? proxyUrl() : "";
        try {
            OkHttp.selector().remove(RULE_NAME);
            OkHttp.closeIdleConnections();
            if (!TextUtils.isEmpty(url)) {
                String text = "[{\"name\":\"" + RULE_NAME + "\",\"hosts\":[\"*\"],\"urls\":[\"" + url + "\"]}]";
                OkHttp.selector().addAll(Proxy.arrayFrom(Json.parse(text)));
            }
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- ext 覆盖 ---------------- */

    /** 在线接口按「当前配置地址 + 站点 key」存本地缓存；本地接口写回 json 文件本身。 */
    private static String cacheKey(String siteKey) {
        return K_EXT + VodConfig.getUrl() + "|" + siteKey;
    }

    public static String ext(Site site) {
        if (site == null) return "";
        String cached = Prefers.getString(cacheKey(site.getKey()), "");
        return TextUtils.isEmpty(cached) ? site.getExt() : cached;
    }

    public static void setExt(Site site, String value) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        String text = value == null ? "" : value.trim();
        // 本地 json 接口（含脚本导入生成的 脚本扫描.json）永久写回文件；在线接口返回 false，
        // 只靠下面的本地缓存覆盖，绝不改写在线源数据。
        LocalScriptSource.updateSiteField(VodConfig.getUrl(), site.getKey(), "ext", text);
        Prefers.put(cacheKey(site.getKey()), text);
        site.setExt(text);
    }

    /** 配置重新解析后回填本地 ext 覆盖。 */
    public static void applyExt(Site site) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        String value = Prefers.getString(cacheKey(site.getKey()), "");
        if (!TextUtils.isEmpty(value)) site.setExt(value);
    }
}

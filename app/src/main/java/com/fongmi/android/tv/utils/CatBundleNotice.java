package com.fongmi.android.tv.utils;

/**
 * 猫爪系 Node bundle 订阅（index.js.md5 / index.js）识别。
 * 这类地址的站点清单由 Node 进程 /config 动态生成，api 指向本地 Node 服务，
 * 本 app 不带 Node 运行时，直接解析会得到空站点，因此只做明确提示，不做假配置。
 */
public class CatBundleNotice {

    public static boolean isBundleUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        return u.endsWith(".js.md5") || u.endsWith("/index.js");
    }

    public static String message() {
        return "这是猫爪系 Node 订阅（.js.md5），站点由 Node 运行时动态生成，本版本不支持；请改填配套的 TVBox / 猫源 JSON 配置地址。";
    }
}

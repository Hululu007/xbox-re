package com.fongmi.android.tv.api.hk.state;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.bean.Config;

import java.util.List;

/** 一级接口（文件级）：文件名=接口名，只由用户添加；加入即注册进点播接口历史。 */
public final class HkInterface {

    public static final String SCHEME = "hk:/";

    private HkInterface() {}

    public static boolean isHkUrl(String url) {
        return url != null && url.startsWith(SCHEME);
    }

    public static boolean isHkKey(String key) {
        return key != null && key.startsWith("hk_");
    }

    public static String configUrl(String doc) {
        return SCHEME + Uri.encode(doc == null ? "" : doc);
    }

    public static String docOfUrl(String url) {
        if (!isHkUrl(url)) return "";
        try { return Uri.decode(url.substring(SCHEME.length())); } catch (Exception e) { return ""; }
    }

    public static List<String> docs() { return HkStateStore.docNames(); }

    /** 用户新建一级接口：只写 A + 注册点播历史，不自动塞任何口令。 */
    public static void create(String name) {
        if (TextUtils.isEmpty(name)) return;
        HkDoc d = HkStateStore.readDoc(name);
        d.name = name;
        HkStateStore.writeDoc(d);
        HkStateStore.setCurrentDoc(name);
        Config.create(0, configUrl(name), name);
    }

    /** 历史里删接口 = 取消注册，A 文件与 B 段全保留，可重新添加找回。 */
    public static void unregister(String name) {
        Config.delete(configUrl(name), 0);
    }
}

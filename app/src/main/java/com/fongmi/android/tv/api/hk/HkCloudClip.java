package com.fongmi.android.tv.api.hk;

/** 移植兼容层：云剪贴板分享在 xbox-re 精简版里不启用。 */
public final class HkCloudClip {

    private HkCloudClip() {
    }

    public static boolean supported(int clipNum) {
        return false;
    }

    public static String upload(String ruleJson, int clipNum) {
        return "";
    }

    public static String download(String code, int clipNum) {
        return "";
    }

    public static String name(int clipNum) {
        return "";
    }
}

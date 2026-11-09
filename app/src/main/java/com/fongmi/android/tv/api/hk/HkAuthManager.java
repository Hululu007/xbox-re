package com.fongmi.android.tv.api.hk;

/**
 * 移植兼容层：星辰的「规则敏感 API 授权弹窗」在 xbox-re 里不存在。
 * 默认放行，避免 HkJsRuntime 编译失败；如需拦截可在此接入 Notify / Dialog。
 */
public final class HkAuthManager {

    private HkAuthManager() {
    }

    /** 兼容签名：与 xingchen 的 checkAuth 完全一致。 */
    public static boolean checkAuth(String ruleTitle, String apiName,
                                    String description, Runnable onAllow) {
        if (onAllow != null) onAllow.run();
        return true;
    }

    public static boolean isAuthorized(String ruleTitle, String apiName) {
        return true;
    }

    public static void setAuthorized(String ruleTitle, String apiName, boolean allow) {
        // no-op
    }

    public static String currentRuleTitle() {
        return "";
    }
}

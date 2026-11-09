package com.fongmi.android.tv.api.hk;

/** 移植兼容层：更新日志弹窗在放弃星辰 UI 后不再展示。 */
public final class HkUpdateLogPopup {

    private HkUpdateLogPopup() {
    }

    public static void show(String jsonData) {
        // no-op：如需日志，可改为 com.fongmi.android.tv.utils.Notify.progress(...)
    }
}

package com.fongmi.android.tv.setting;

import androidx.appcompat.app.AppCompatDelegate;

import com.github.catvod.utils.Prefers;

/**
 * Chalk 黑白皮肤总开关。
 *
 * 与「星辰皮肤补丁」不同：这里开关是可读可写的，且设置页有可见入口。
 * 开启 = 强制浅色黑白（白底黑字、黑底白字选中态）；
 * 关闭 = 跟随系统深浅，但配色仍是纯黑白灰（values-night/styles.xml）。
 * 全局走 AppCompatDelegate，时机在 Application.onCreate，不依赖 Activity.setTheme。
 */
public final class ChalkSkin {

    private static final String KEY = "chalk_skin_enabled";

    private ChalkSkin() {
    }

    public static boolean isEnabled() {
        return Prefers.getBoolean(KEY, true);
    }

    public static void setEnabled(boolean enabled) {
        Prefers.put(KEY, enabled);
        applyMode();
    }

    /** 在 Application.onCreate 调用一次；切换开关后也会重新调用。 */
    public static void applyMode() {
        AppCompatDelegate.setDefaultNightMode(isEnabled()
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}

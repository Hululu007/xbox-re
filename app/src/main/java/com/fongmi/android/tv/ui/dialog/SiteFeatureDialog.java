package com.fongmi.android.tv.ui.dialog;

import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.SiteFeature;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** 默认代理地址 / 站点 ext 的单行输入弹窗，沿用 app 现成的 LightDialog 主题。 */
public final class SiteFeatureDialog {

    private SiteFeatureDialog() {
    }

    public static void proxyUrl(FragmentActivity activity, Runnable callback) {
        EditText input = input(activity, SiteFeature.proxyUrl(), R.string.setting_default_proxy_hint);
        new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.setting_default_proxy)
                .setView(box(activity, input))
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    SiteFeature.setProxyUrl(input.getText().toString());
                    SiteFeature.apply(VodConfig.get().getHome());
                    if (callback != null) callback.run();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    public static void ext(FragmentActivity activity, Site site, Runnable callback) {
        EditText input = input(activity, SiteFeature.ext(site), R.string.setting_default_proxy_hint);
        new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.menu_ext)
                .setView(box(activity, input))
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    SiteFeature.setExt(site, input.getText().toString());
                    if (callback != null) callback.run();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private static EditText input(FragmentActivity activity, String value, int hint) {
        EditText input = new EditText(activity);
        input.setText(value);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setSelection(input.getText().length());
        return input;
    }

    private static FrameLayout box(FragmentActivity activity, EditText input) {
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(activity);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        return box;
    }
}

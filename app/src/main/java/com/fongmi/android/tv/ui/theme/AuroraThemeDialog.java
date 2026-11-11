package com.fongmi.android.tv.ui.theme;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 原生「外观与主题」统一设置面板（mobile / leanback 共用同一份配置与同一份 UI）。
 *
 * <p>全部控件用代码构建，不新增 layout 资源，避免 viewBinding 生成与跨变体资源冲突。
 * 上半部是按当前配置即时渲染的预览卡片，下半部是分组选项；点「应用并刷新」
 * 立即关闭本弹窗并重建 Activity，同时在进程内广播配置版本让其它页面恢复时重建。
 */
public class AuroraThemeDialog extends DialogFragment {

    private static final String TAG = "AuroraThemeDialog";

    private static final int[] PRESET_LABELS = {
            R.string.aurora_preset_aurora, R.string.aurora_preset_paper,
            R.string.aurora_preset_ink, R.string.aurora_preset_glass,
            R.string.aurora_preset_system};
    private static final int[] ACCENT_LABELS = {
            R.string.aurora_accent_default, R.string.aurora_accent_jade,
            R.string.aurora_accent_azure, R.string.aurora_accent_amber,
            R.string.aurora_accent_rose, R.string.aurora_accent_violet,
            R.string.aurora_accent_cyan, R.string.aurora_accent_lime,
            R.string.aurora_accent_slate};
    private static final int[] CORNER_LABELS = {
            R.string.aurora_corner_default, R.string.aurora_corner_small,
            R.string.aurora_corner_large, R.string.aurora_corner_pill};
    private static final int[] TEXT_LABELS = {
            R.string.aurora_text_default, R.string.aurora_text_small,
            R.string.aurora_text_large};
    private static final int[] BUTTON_LABELS = {
            R.string.aurora_button_solid, R.string.aurora_button_pill,
            R.string.aurora_button_square, R.string.aurora_button_outline,
            R.string.aurora_button_soft};
    private static final int[] TILE_LABELS = {
            R.string.aurora_tile_soft, R.string.aurora_tile_square,
            R.string.aurora_tile_large, R.string.aurora_tile_circle,
            R.string.aurora_tile_poster, R.string.aurora_tile_wide};
    private static final int[] DIALOG_STYLE_LABELS = {
            R.string.aurora_dialog_style_solid, R.string.aurora_dialog_style_outline,
            R.string.aurora_dialog_style_soft, R.string.aurora_dialog_style_glass};
    private static final int[] DIALOG_POS_LABELS = {
            R.string.aurora_dialog_pos_center, R.string.aurora_dialog_pos_bottom,
            R.string.aurora_dialog_pos_top, R.string.aurora_dialog_pos_side};

    private Context ctx;
    private LinearLayout root;

    public static void show(FragmentActivity activity) {
        new AuroraThemeDialog().show(activity.getSupportFragmentManager(), TAG);
    }

    public static void show(Fragment fragment) {
        new AuroraThemeDialog().show(fragment.getChildFragmentManager(), TAG);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Activity.recreate() 会保存并恢复 Fragment 状态。若不主动退出，重建后本弹窗
        // 会自动重新弹出，用户看到的就是「点应用并刷新后窗口没关、像是没保存」。
        if (savedInstanceState != null) dismissAllowingStateLoss();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        ctx = requireContext();
        ScrollView scroll = new ScrollView(ctx);
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(ctx, 20);
        root.setPadding(padding, dp(ctx, 14), padding, dp(ctx, 6));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        rebuild();
        return new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.aurora_theme_title)
                .setView(scroll)
                .setPositiveButton(R.string.aurora_apply, (dialog, which) -> applyAndRefresh())
                .setNeutralButton(R.string.aurora_reset, (dialog, which) -> {
                    AuroraTheme.reset(ctx);
                    applyAndRefresh();
                })
                .setNegativeButton(R.string.aurora_close, null)
                .create();
    }

    /**
     * 关闭窗口 + 刷新界面。不销毁任务栈、不清栈、不销毁弹窗宿主之外的任何 Activity。
     * put() 已同步 commit 落盘，这里只负责让所有页面用新配置重建。
     */
    private void applyAndRefresh() {
        AuroraTheme.markChanged();
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        // recreate() 会保存并恢复 Fragment 状态。先把自己从 FragmentManager 移除且立即
        // 提交事务，否则重建后弹窗会被恢复出来，用户会以为「没保存 / 窗口没关」。
        try {
            dismissAllowingStateLoss();
            activity.getSupportFragmentManager().executePendingTransactions();
        } catch (Throwable ignored) {
            // 即使这里失败，onCreate() 里的 savedInstanceState 兜底仍会让重建后的弹窗自行退出。
        }
        try {
            Toast.makeText(activity, R.string.aurora_applied, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // Toast 失败与配置保存无关。
        }
        activity.recreate();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) AuroraTheme.decorateWindow(dialog.getWindow(), themed());
    }

    private Context themed() {
        return AuroraTheme.previewContext(ctx);
    }

    private void rebuild() {
        root.removeAllViews();
        buildPreview();
        addGroup(R.string.aurora_preset, AuroraTheme.PRESET_KEYS, PRESET_LABELS, AuroraTheme.KEY_PRESET, "aurora", false);
        addGroup(R.string.aurora_accent, AuroraTheme.ACCENT_KEYS, ACCENT_LABELS, AuroraTheme.KEY_ACCENT, "default", false);
        addGroup(R.string.aurora_corner, AuroraTheme.CORNER_KEYS, CORNER_LABELS, AuroraTheme.KEY_CORNER, "default", false);
        addGroup(R.string.aurora_text, AuroraTheme.TEXT_KEYS, TEXT_LABELS, AuroraTheme.KEY_TEXT, "default", false);
        addGroup(R.string.aurora_button, AuroraTheme.BUTTON_KEYS, BUTTON_LABELS, AuroraTheme.KEY_BUTTON, "solid", false);
        addGroup(R.string.aurora_tile, AuroraTheme.TILE_KEYS, TILE_LABELS, AuroraTheme.KEY_TILE, "soft", false);
        addGroup(R.string.aurora_dialog_style, AuroraTheme.DIALOG_STYLE_KEYS, DIALOG_STYLE_LABELS, AuroraTheme.KEY_DIALOG_STYLE, "solid", true);
        addGroup(R.string.aurora_dialog_pos, AuroraTheme.DIALOG_POS_KEYS, DIALOG_POS_LABELS, AuroraTheme.KEY_DIALOG_POS, "center", true);
    }

    private void buildPreview() {
        Context c = themed();
        float text = AuroraTheme.dimension(c, R.attr.auroraTextSize, 14);
        int onSurface = AuroraTheme.color(c, R.attr.auroraOnSurface, 0xFFE8EEEB);
        int onVariant = AuroraTheme.color(c, R.attr.auroraOnSurfaceVariant, 0xFFA8B5B0);
        int onAccent = AuroraTheme.onAccent(
                AuroraTheme.color(c, R.attr.auroraAccent, 0xFF5FC79A));

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 18);
        card.setPadding(p, p, p, p);
        card.setBackground(AuroraTheme.surface(c, AuroraTheme.get(c, AuroraTheme.KEY_DIALOG_STYLE, "solid")));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(c, 12);

        TextView title = new TextView(c);
        title.setText(R.string.aurora_preview_title);
        title.setTextColor(onSurface);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, text * 1.45f);
        title.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
        card.addView(title);

        TextView sub = new TextView(c);
        sub.setText(R.string.aurora_preview_sub);
        sub.setTextColor(onVariant);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, text * 0.92f);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(c, 4);
        subParams.bottomMargin = dp(c, 14);
        card.addView(sub, subParams);

        LinearLayout tiles = new LinearLayout(c);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        String tileShape = AuroraTheme.get(c, AuroraTheme.KEY_TILE, "soft");
        for (int i = 0; i < 3; i++) {
            View item = new View(c);
            item.setBackground(AuroraTheme.tile(c, tileShape));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 54), 1);
            lp.leftMargin = i == 0 ? 0 : dp(c, 8);
            tiles.addView(item, lp);
        }
        LinearLayout.LayoutParams tilesParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tilesParams.bottomMargin = dp(c, 14);
        card.addView(tiles, tilesParams);

        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView button = new TextView(c);
        button.setText(R.string.aurora_preview_button);
        button.setBackground(AuroraTheme.button(c, AuroraTheme.get(c, AuroraTheme.KEY_BUTTON, "solid")));
        button.setTextColor(onAccent);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(c, 18), dp(c, 9), dp(c, 18), dp(c, 9));
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, text);
        actions.addView(button);
        TextView tag = new TextView(c);
        tag.setText(R.string.aurora_preview_tag);
        tag.setTextColor(onVariant);
        tag.setTextSize(TypedValue.COMPLEX_UNIT_PX, text * 0.85f);
        tag.setPadding(dp(c, 14), 0, 0, 0);
        actions.addView(tag);
        card.addView(actions);

        root.addView(card, cardParams);

        // 真实生效状态（已保存配置 + 实际 setTheme + 探针结论），避免「改了但看不出有没有生效」。
        TextView status = new TextView(c);
        status.setText(AuroraTheme.status(c));
        status.setTextColor(onVariant);
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, text * 0.78f);
        status.setPadding(0, dp(c, 4), 0, dp(c, 4));
        root.addView(status);

        TextView note = new TextView(c);
        note.setText(R.string.aurora_note);
        note.setTextColor(onVariant);
        note.setTextSize(TypedValue.COMPLEX_UNIT_PX, text * 0.82f);
        root.addView(note);
    }

    private void addGroup(int titleRes, String[] keys, int[] labels, String prefKey, String def, boolean instantWindow) {
        Context c = themed();
        float text = AuroraTheme.dimension(c, R.attr.auroraTextSize, 14);
        TextView heading = new TextView(c);
        heading.setText(titleRes);
        heading.setTextColor(AuroraTheme.color(c, R.attr.auroraOnSurface, 0xFFE8EEEB));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, text * 0.92f);
        heading.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headingParams.topMargin = dp(c, 14);
        headingParams.bottomMargin = dp(c, 6);
        root.addView(heading, headingParams);

        String current = AuroraTheme.get(c, prefKey, def);
        LinearLayout row = null;
        for (int i = 0; i < keys.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                root.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            final String key = keys[i];
            boolean selected = key.equals(current);
            TextView option = new TextView(c);
            option.setText(labels[i]);
            option.setGravity(Gravity.CENTER);
            option.setSingleLine(true);
            option.setPadding(dp(c, 8), dp(c, 9), dp(c, 8), dp(c, 9));
            option.setTextSize(TypedValue.COMPLEX_UNIT_PX, text * 0.88f);
            option.setBackground(chip(c, selected));
            option.setTextColor(selected
                    ? AuroraTheme.color(c, R.attr.auroraAccent, 0xFF5FC79A)
                    : AuroraTheme.color(c, R.attr.auroraOnSurfaceVariant, 0xFFA8B5B0));
            option.setOnClickListener(view -> {
                AuroraTheme.put(ctx, prefKey, key);
                rebuild();
                if (instantWindow) {
                    Dialog dialog = getDialog();
                    if (dialog != null) AuroraTheme.decorateWindow(dialog.getWindow(), themed());
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            int margin = dp(c, 3);
            lp.setMargins(margin, margin, margin, margin);
            row.addView(option, lp);
        }
    }

    private GradientDrawable chip(Context c, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(AuroraTheme.dimension(c, R.attr.auroraCorner, 12) * 0.7f);
        if (selected) {
            drawable.setColor(AuroraTheme.color(c, R.attr.auroraAccentSoft, 0xFF1E3A30));
            drawable.setStroke(AuroraTheme.px(c, 1f), AuroraTheme.color(c, R.attr.auroraAccent, 0xFF5FC79A));
        } else {
            drawable.setColor(AuroraTheme.color(c, R.attr.auroraSurfaceHigh, 0xFF232C28));
            drawable.setStroke(AuroraTheme.px(c, 1f), AuroraTheme.color(c, R.attr.auroraOutline, 0xFF2E3A35));
        }
        return drawable;
    }

    private static int dp(Context context, int value) {
        return AuroraTheme.px(context, value);
    }
}

package com.fongmi.android.tv.ui.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityCrashBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.theme.AuroraTheme;
import com.github.catvod.utils.Prefers;

import java.util.Objects;

import cat.ereza.customactivityoncrash.CustomActivityOnCrash;

public class CrashActivity extends BaseActivity {

    private ActivityCrashBinding mBinding;

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityCrashBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setCrash();
    }

    @Override
    protected void initEvent() {
        mBinding.details.setOnClickListener(v -> showError());
        mBinding.restart.setOnClickListener(v -> CustomActivityOnCrash.restartApplication(this, Objects.requireNonNull(CustomActivityOnCrash.getConfigFromIntent(getIntent()))));
    }

    private void setCrash() {
        String log = CustomActivityOnCrash.getActivityLogFromIntent(getIntent());
        if (TextUtils.isEmpty(log)) return;
        String[] lines = log.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].isEmpty()) continue;
            if (lines[i].contains(HomeActivity.class.getSimpleName())) {
                Prefers.put("crash", true);
                break;
            }
        }
    }

    private void showError() {
        final String detail = CustomActivityOnCrash.getAllErrorDetailsFromIntent(this, getIntent());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.crash_details_title)
                .setMessage(detail)
                .setNeutralButton(R.string.crash_details_copy, (d, w) -> copy(detail))
                .setPositiveButton(R.string.crash_details_close, null)
                .show();
        // 崩溃页自给自足：不依赖被主题 overlay 改写的 framework 文字色。
        // 显式指定背景 + 按亮度取前景，保证四套预设下都 >= 4.5:1。
        try {
            int bg = AuroraTheme.color(this, R.attr.auroraSurfaceHigh, 0xFF22302A);
            int fg = AuroraTheme.onAccent(bg);
            Window window = dialog.getWindow();
            if (window != null) window.setBackgroundDrawable(new ColorDrawable(bg));
            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) {
                message.setTextColor(fg);
                message.setTextIsSelectable(true);
                message.setTextSize(12);
            }
            int titleId = getResources().getIdentifier("alertTitle", "id", getPackageName());
            TextView title = dialog.findViewById(titleId != 0 ? titleId : android.R.id.title);
            if (title != null) title.setTextColor(fg);
            for (int which : new int[]{AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
                android.widget.Button b = dialog.getButton(which);
                if (b != null) b.setTextColor(fg);
            }
        } catch (Throwable ignored) {
            // 取色失败也保留复制按钮与可选中文本。
        }
    }

    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("crash", text));
            Toast.makeText(this, R.string.crash_details_copied, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}

package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public abstract class BaseAlertDialog extends DialogFragment {

    /** 由 setWidth() 记录，show 之前就写进 window attributes */
    private float widthFactor;

    protected abstract ViewBinding getBinding();

    protected abstract MaterialAlertDialogBuilder getBuilder();

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = getBuilder().create();
        initView();
        initEvent();
        setupWindowBeforeShow(dialog);
        return dialog;
    }

    /**
     * show 之前配置窗口。子类可覆写补充自己的参数；默认应用 setWidth() 记录的宽度。
     * 这里用传入的 dialog，不依赖 getDialog()（此时 DialogFragment 还没赋值）。
     */
    protected void setupWindowBeforeShow(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null || widthFactor <= 0f) return;
        WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
        params.width = (int) (ResUtil.getScreenWidth() * widthFactor);
        dialog.getWindow().setAttributes(params);
    }

    @Override
    public void onStart() {
        // 覆盖所有子类，包括覆写了 onCreateDialog() 且不调 super 的那批：
        // show() 之前先把 decor 设透明，等子类 onStart() 里的窗口参数应用完再恢复。
        Dialog dialog = getDialog();
        View decor = dialog == null || dialog.getWindow() == null
                ? null : dialog.getWindow().getDecorView();
        if (decor != null && decor.getAlpha() != 0f) decor.setAlpha(0f);
        super.onStart(); // DialogFragment.onStart() 内部会 dialog.show()
        // post 排在消息队列里；子类 onStart() 中 super.onStart() 之后的窗口配置
        // 是同步代码，一定先跑完，所以恢复可见时窗口已是最终尺寸。
        if (decor != null) decor.post(() -> decor.setAlpha(1f));
    }

    protected MaterialAlertDialogBuilder builder() {
        return new MaterialAlertDialogBuilder(requireActivity());
    }

    protected void initView() {
    }

    protected void initEvent() {
    }

    protected void setWidth(float factor) {
        widthFactor = factor;
        if (getDialog() == null || getDialog().getWindow() == null) return;
        WindowManager.LayoutParams params = getDialog().getWindow().getAttributes();
        params.width = (int) (ResUtil.getScreenWidth() * factor);
        getDialog().getWindow().setAttributes(params);
    }
}

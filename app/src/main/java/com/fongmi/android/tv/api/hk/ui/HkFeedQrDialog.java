package com.fongmi.android.tv.api.hk.ui;

import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.api.hk.state.HkStateUpdater;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.QRCode;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import com.fongmi.android.tv.event.ServerEvent;

/**
 * TV 二次点击口令：完整原串只读展示 + 二维码，手机扫码在 tab4 输入新口令推回。
 * 一行字都不用打；收到 HK_FEED 后写回 A 并按 feed 增量刷新，不全部更新。
 */
public final class HkFeedQrDialog {

    private final FragmentActivity activity;
    private final String doc;
    private final String feedId;
    private final String target;
    private TextView status;
    private AlertDialog dialog;
    private boolean registered;

    private HkFeedQrDialog(FragmentActivity activity, String doc, String feedId, String raw) {
        this.activity = activity;
        this.doc = doc;
        this.feedId = feedId;
        this.target = doc + "|" + feedId;
        build(raw);
    }

    public static void show(FragmentActivity activity, String doc, String feedId, String raw) {
        new HkFeedQrDialog(activity, doc, feedId, raw);
    }

    private void build(String raw) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 16, 32, 16);

        TextView full = new TextView(activity);
        full.setText(raw == null ? "" : raw);
        full.setTextIsSelectable(true);
        full.setTextSize(13);
        box.addView(full);

        String url = Server.get().getAddress(4) + "&hk=" + Uri.encode(target);
        Bitmap qr = QRCode.getLightBitmap(url, 200, 0);
        if (qr != null) {
            ImageView img = new ImageView(activity);
            img.setImageBitmap(qr);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(360, 360);
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            lp.topMargin = 16;
            box.addView(img, lp);
        }

        TextView tip = new TextView(activity);
        tip.setText("使用手机扫描二维码，在网页「配置」框粘贴新口令后提交");
        tip.setTextSize(12);
        box.addView(tip);

        status = new TextView(activity);
        status.setText("等待手机推送…");
        status.setTextSize(12);
        box.addView(status);

        dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("口令原文 / 扫码更换")
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (registered) { EventBus.getDefault().unregister(this); registered = false; }
        });
        dialog.show();
        EventBus.getDefault().register(this);
        registered = true;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onHkFeed(ServerEvent event) {
        if (event.type() != ServerEvent.Type.HK_FEED) return;
        if (!target.equals(event.name())) return;       // 对不上丢弃，防串台
        String text = event.text() == null ? "" : event.text().trim();
        if (text.isEmpty()) return;
        HkStateUpdater.editFeed(doc, feedId, text);      // 写回 A 原串 + 按 feed 增量刷新
        status.setText("已更新");
    }
}
